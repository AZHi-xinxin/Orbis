package me.rerere.rikkahub.data.orbis.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class OrbisGroupReadRepositoryTest {
    private class Store : OrbisGroupStorage {
        val allRooms = linkedMapOf<String, OrbisGroupRoom>()
        val rows = mutableListOf<OrbisGroupMessage>()
        var writes = 0
        var recoveries = 0
        var messageReads = 0
        var requestedLimit = 0
        var afterRead: suspend () -> Unit = {}
        var afterRoomList: () -> Unit = {}
        override suspend fun recoverInterrupted(now: Long) { recoveries++ }
        override suspend fun rooms() = allRooms.values.toList().also { afterRoomList() }
        override suspend fun room(id: String) = allRooms[id]
        override suspend fun messages(roomId: String, limit: Int, beforeSequence: Long?): List<OrbisGroupMessage> {
            messageReads++; requestedLimit = limit
            val result = rows.filter { it.roomId == roomId && (beforeSequence == null || it.sequence < beforeSequence) }.takeLast(limit)
            afterRead()
            return result
        }
        override suspend fun message(id: String) = error("single-message lookup is not a bridge capability")
        override suspend fun latestRound(roomId: String) = error("round lookup is not a bridge capability")
        override suspend fun countMessages(roomId: String) = error("unbounded count is not a bridge capability")
        override suspend fun commit(room: OrbisGroupRoom?, round: GroupRound?, messages: List<OrbisGroupMessage>) {
            writes++
            room?.let { allRooms[it.id] = it }
        }
    }
    private class Fixture(scope: TestScope) {
        val assistant = Assistant(name = "阿止")
        val outsider = Assistant(name = "阿止")
        val member = OrbisGroupMember(Uuid.random().toString(), assistant.id, Uuid.random(), Uuid.random(), assistant.name, joinedAt = 1)
        val room = OrbisGroupRoom(Uuid.random().toString(), "群", listOf(member), 1, 2)
        val store = Store().apply {
            allRooms[room.id] = room
            rows += (1..80).map { n -> OrbisGroupMessage(Uuid.random().toString(), room.id, "round", member.id, "阿止",
                text = "record $n", sequence = n.toLong(), createdAt = n.toLong(), updatedAt = n.toLong()) }
        }
        var settings = Settings(assistants = listOf(assistant, outsider))
        var generationCalls = 0
        val chats = OrbisGroupChats(store, { settings }, OrbisGroupResponder { _, _ -> generationCalls++; error("must not generate") }, scope.backgroundScope)
    }
    private suspend fun code(expected: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (failure: OrbisGroupReadException) { assertEquals(expected, failure.code) }
    }

    @Test fun explicitReadsDoNotInitializeRecoverPublishWriteOrGenerate() = runTest {
        val fixture = Fixture(this)
        // Constructor initialization is still scheduled; bridge reads themselves must not run it.
        val initial = fixture.chats.state.value
        fixture.chats.listReadableGroups(fixture.assistant.id, 10, null)
        fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false)
        assertEquals(0, fixture.store.recoveries)
        assertEquals(0, fixture.store.writes)
        assertEquals(0, fixture.generationCalls)
        assertEquals(initial, fixture.chats.state.value)
        assertEquals(21, fixture.store.requestedLimit)
        assertEquals(80, fixture.store.rows.size)
    }

    @Test fun outsiderWithSameNameIsDeniedBeforeReadingAnyMessageBody() = runTest {
        val fixture = Fixture(this)
        val list = fixture.chats.listReadableGroups(fixture.outsider.id, 10, null)
        assertTrue(list["groups"]!!.jsonArray.isEmpty())
        code("group_not_found_or_not_a_member") {
            fixture.chats.readReadableGroup(fixture.outsider.id, fixture.room.id, 20, null, false)
        }
        assertEquals(0, fixture.store.messageReads)
    }

    @Test fun removedMemberLosesAccessImmediatelyAndHistoryIsNotErased() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        fixture.chats.removeMember(fixture.room.id, fixture.member.id)
        val existing = fixture.store.rows.toList()
        val reads = fixture.store.messageReads
        val writes = fixture.store.writes
        code("group_not_found_or_not_a_member") {
            fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false)
        }
        assertEquals(reads, fixture.store.messageReads)
        assertEquals(writes, fixture.store.writes)
        assertEquals(existing, fixture.store.rows)
    }

    @Test fun deletedAssistantOrAmbiguousDuplicateCannotUseSavedToolBinding() = runTest {
        val fixture = Fixture(this)
        fixture.settings = fixture.settings.copy(assistants = emptyList())
        code("group_records_unavailable") { fixture.chats.listReadableGroups(fixture.assistant.id, 10, null) }
        code("group_records_unavailable") { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false) }
        fixture.settings = fixture.settings.copy(assistants = listOf(fixture.assistant, fixture.assistant.copy(name = "duplicate")))
        code("group_records_unavailable") { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false) }
        assertEquals(0, fixture.store.messageReads)
    }

    @Test fun assistantDeletionDuringStorageReadIsRecheckedBeforeReturning() = runTest {
        val fixture = Fixture(this)
        fixture.store.afterRead = { fixture.settings = fixture.settings.copy(assistants = emptyList()) }
        code("group_records_unavailable") { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false) }
        assertEquals(1, fixture.store.messageReads)
        assertEquals(0, fixture.store.writes)
    }

    @Test fun assistantDeletionDuringRoomListingIsRecheckedBeforeReturning() = runTest {
        val fixture = Fixture(this)
        fixture.store.afterRoomList = { fixture.settings = fixture.settings.copy(assistants = emptyList()) }
        code("group_records_unavailable") { fixture.chats.listReadableGroups(fixture.assistant.id, 10, null) }
    }

    @Test fun invalidReadLimitNeverStartsAHistoryQuery() = runTest {
        val fixture = Fixture(this)
        code("invalid_pagination") { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 51, null, false) }
        code("invalid_pagination") { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, 0, false) }
        assertEquals(0, fixture.store.messageReads)
    }

    @Test fun membershipMutationsAndReadSnapshotShareTheSameMutex() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.store.afterRead = { entered.complete(Unit); release.await() }
        val read = async { fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false) }
        entered.await()
        val remove = async { fixture.chats.removeMember(fixture.room.id, fixture.member.id) }
        runCurrent()
        assertFalse(remove.isCompleted)
        release.complete(Unit)
        assertEquals(20, read.await()["returned"]!!.jsonPrimitive.int)
        remove.await()
        code("group_not_found_or_not_a_member") {
            fixture.chats.readReadableGroup(fixture.assistant.id, fixture.room.id, 20, null, false)
        }
    }
}
