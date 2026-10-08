package me.rerere.rikkahub.data.orbis.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** No providers or private-chat repositories are used; every response is an in-process fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrbisGroupChatsTest {
    private class Store : OrbisGroupStorage {
        val allRooms = linkedMapOf<String, OrbisGroupRoom>()
        val rounds = linkedMapOf<String, GroupRound>()
        val rows = linkedMapOf<String, OrbisGroupMessage>()
        var sequence = 0L
        var recovered = 0
        var failRead = false
        var failWrite: (List<OrbisGroupMessage>) -> Boolean = { false }
        var afterCommit: (GroupRound?) -> Unit = {}
        override suspend fun recoverInterrupted(now: Long) {
            recovered++
            rows.replaceAll { _, row -> if (row.status in setOf(OrbisGroupMessageStatus.QUEUED, OrbisGroupMessageStatus.GENERATING))
                row.copy(status = OrbisGroupMessageStatus.INTERRUPTED, errorReason = "interrupted", updatedAt = now) else row }
            rounds.replaceAll { _, round -> if (round.status == GroupRoundStatus.ACTIVE) round.copy(status = GroupRoundStatus.INTERRUPTED) else round }
        }
        override suspend fun rooms(): List<OrbisGroupRoom> {
            if (failRead) { failRead = false; error("private_storage_error") }
            return allRooms.values.toList()
        }
        override suspend fun room(id: String) = allRooms[id]
        override suspend fun messages(roomId: String, limit: Int, beforeSequence: Long?) = rows.values.filter {
            it.roomId == roomId && (beforeSequence == null || it.sequence < beforeSequence)
        }.sortedBy { it.sequence }.takeLast(limit)
        override suspend fun message(id: String) = rows[id]
        override suspend fun latestRound(roomId: String) = rounds.values.lastOrNull { it.roomId == roomId }
        override suspend fun countMessages(roomId: String) = rows.values.count { it.roomId == roomId }.toLong()
        override suspend fun commit(room: OrbisGroupRoom?, round: GroupRound?, messages: List<OrbisGroupMessage>) {
            if (failWrite(messages)) error("credential=synthetic-secret storage_failure")
            room?.let { allRooms[it.id] = it }
            round?.let { rounds[it.id] = it }
            messages.forEach { rows[it.id] = it.copy(sequence = rows[it.id]?.sequence ?: ++sequence) }
            afterCommit(round)
        }
    }

    private class Reply : OrbisGroupResponder {
        val calls = mutableListOf<GroupGenerationInput>()
        var block: suspend (GroupGenerationInput, suspend (String) -> Unit) -> String = { input, persist ->
            val text = "reply-${input.participant.member.name}"
            persist(text); text
        }
        override suspend fun generate(input: GroupGenerationInput, persist: suspend (String) -> Unit): String {
            calls += input
            return block(input, persist)
        }
    }

    private class Fixture(scope: TestScope, val store: Store = Store(), val reply: Reply = Reply()) {
        val models = listOf(Model(modelId = "alpha"), Model(modelId = "beta"))
        val providers = models.map { ProviderSetting.OpenAI(models = listOf(it), apiKey = "synthetic-test-key") }
        val assistants = listOf(Assistant(name = "甲", systemPrompt = "persona-A"), Assistant(name = "乙", systemPrompt = "persona-B"))
        var settings = Settings(providers = providers, assistants = assistants)
        val chats = OrbisGroupChats(store, { settings }, reply, scope.backgroundScope) { scope.testScheduler.currentTime }
        suspend fun room(memberCount: Int = 2): String {
            chats.reload()
            val id = chats.createRoom("测试群")
            repeat(memberCount) { chats.addMember(id, assistants[it].id, models[it].id) }
            return id
        }
        fun member(room: String, index: Int = 0) = store.allRooms.getValue(room).members[index]
    }

    private suspend fun code(expected: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (failure: OrbisGroupException) { assertEquals(expected, failure.code) }
    }

    @Test fun eachMemberSpeaksOnceInOrderAndSecondSeesFirst() = runTest {
        val f = Fixture(this); val room = f.room()
        f.chats.send(room, "共同问题")
        assertEquals(0, f.reply.calls.size) // Scheduling is fast; durable placeholders precede the request.
        assertEquals(3, f.store.rows.size)
        runCurrent()
        assertEquals(listOf("甲", "乙"), f.reply.calls.map { it.participant.member.name })
        assertTrue(f.reply.calls[1].messages.any { it.toText().contains("reply-甲") && it.role == MessageRole.USER })
        assertFalse(f.reply.calls[0].messages.any { it.toText().contains("reply-乙") })
        assertEquals(listOf("共同问题", "reply-甲", "reply-乙"), f.chats.state.value.messages.map { it.text })
        assertTrue(f.chats.state.value.messages.all { it.status == OrbisGroupMessageStatus.COMPLETE })
        assertTrue(f.chats.state.value.runningRoomIds.isEmpty())
    }

    @Test fun targetedSendDoesNotScheduleOtherMembers() = runTest {
        val f = Fixture(this); val room = f.room()
        f.chats.send(room, "只问乙", f.member(room, 1).id); runCurrent()
        assertEquals(listOf("乙"), f.reply.calls.map { it.participant.member.name })
        assertEquals(2, f.store.rows.size)
    }

    @Test fun explicitRegenerationPreservesOriginalAndCallsOnlyThatMemberOnce() = runTest {
        val f = Fixture(this); val room = f.room()
        f.chats.send(room, "原始问题"); runCurrent()
        val original = f.chats.state.value.messages.first { it.memberId == f.member(room).id }
        val before = f.store.rows.toMap()
        f.chats.regenerate(room, original.id); runCurrent()
        assertEquals(3, f.reply.calls.size)
        assertEquals(original.memberId, f.reply.calls.last().participant.member.id)
        before.forEach { (id, message) -> assertEquals(message, f.store.rows[id]) }
        assertEquals(4, f.store.rows.size)
        assertEquals(original.id, f.store.rounds.values.last().retryOfMessageId)
        assertEquals(1, f.store.rows.values.count { it.memberId == null })
    }

    @Test fun completeRegenerationCannotReplayOldRoundOrHumanMessage() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.chats.send(room, "第一轮"); runCurrent()
        val old = f.chats.state.value.messages.last()
        code("not_retryable") { f.chats.retry(room, old.id) }
        f.chats.send(room, "第二轮"); runCurrent()
        val human = f.chats.state.value.messages.last { it.memberId == null }
        code("stale_retry") { f.chats.regenerate(room, old.id) }
        code("not_retryable") { f.chats.regenerate(room, human.id) }
        assertEquals(2, f.reply.calls.size)
    }

    @Test fun doubleSendAndMemberEditsAreBlockedWhileRunning() = runTest {
        val f = Fixture(this); val room = f.room()
        f.reply.block = { _, _ -> awaitCancellation() }
        f.chats.send(room, "one"); runCurrent()
        code("busy") { f.chats.send(room, "two") }
        code("busy") { f.chats.removeMember(room, f.member(room).id) }
        code("busy") { f.chats.addMember(room, f.assistants[0].id, f.models[0].id) }
        f.chats.stop(room)
    }

    @Test fun stopPreservesPartialAndInterruptsAllPendingWithoutContinuing() = runTest {
        val f = Fixture(this); val room = f.room()
        f.reply.block = { _, persist -> persist("保留半句"); awaitCancellation() }
        f.chats.send(room, "go"); runCurrent()
        f.chats.stop(room); runCurrent()
        val ai = f.chats.state.value.messages.filter { it.memberId != null }
        assertEquals(listOf("保留半句", ""), ai.map { it.text })
        assertTrue(ai.all { it.status == OrbisGroupMessageStatus.INTERRUPTED })
        assertEquals(1, f.reply.calls.size)
        f.chats.reload(); runCurrent()
        assertEquals(1, f.reply.calls.size)
    }

    @Test fun immediateStopBeforeJobStartsReleasesTheRoomWithoutSending() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.chats.send(room, "cancel-before-start")
        f.chats.stop(room); runCurrent()
        assertTrue(f.reply.calls.isEmpty())
        assertTrue(f.chats.state.value.runningRoomIds.isEmpty())
        assertEquals(OrbisGroupMessageStatus.INTERRUPTED, f.chats.state.value.messages.last().status)
        f.chats.send(room, "new-request"); runCurrent()
        assertEquals(1, f.reply.calls.size)
    }

    @Test fun reloadWhileRunningOnlyReadsAndDoesNotResetOrResume() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.reply.block = { _, persist -> persist("partial"); awaitCancellation() }
        f.chats.send(room, "go"); runCurrent()
        val recoveryCount = f.store.recovered
        f.chats.reload(); runCurrent()
        assertEquals(recoveryCount, f.store.recovered)
        assertEquals(OrbisGroupMessageStatus.GENERATING, f.chats.state.value.messages.last().status)
        assertEquals(1, f.reply.calls.size)
        f.chats.stop(room)
    }

    @Test fun onlyOneGroupRoundMayGenerateWhileOtherGroupsRemainReadableAndEditable() = runTest {
        val f = Fixture(this); val first = f.room(1); val second = f.room(1)
        val finishSecond = CompletableDeferred<Unit>()
        f.reply.block = { input, persist ->
            persist("partial")
            if (input.roomId == first) awaitCancellation() else { finishSecond.await(); "second-complete" }
        }
        f.chats.send(first, "first"); runCurrent()
        code("other_room_busy") { f.chats.send(second, "second") }
        assertEquals(0L, f.store.countMessages(second))
        f.chats.selectRoom(second)
        f.chats.addMember(second, f.assistants[1].id, f.models[1].id)
        assertEquals(2, f.store.allRooms.getValue(second).members.size)
        f.chats.stop(first)
        f.chats.send(second, "second", f.member(second).id); runCurrent()
        assertTrue(second in f.chats.state.value.runningRoomIds)
        finishSecond.complete(Unit); runCurrent()
        assertEquals("second-complete", f.store.messages(second, 200).last().text)
        assertEquals(OrbisGroupMessageStatus.COMPLETE, f.store.messages(second, 200).last().status)
    }

    @Test fun oneMemberFailureDoesNotExposeSecretsOrCancelNextMember() = runTest {
        val f = Fixture(this); val room = f.room()
        f.reply.block = { input, persist ->
            if (input.participant.member.name == "甲") { persist("first-partial"); error("apiKey=synthetic-secret") }
            "second-ok"
        }
        f.chats.send(room, "go"); runCurrent()
        val ai = f.chats.state.value.messages.filter { it.memberId != null }
        assertEquals(OrbisGroupMessageStatus.FAILED, ai[0].status)
        assertEquals("unavailable", ai[0].errorReason)
        assertEquals("first-partial", ai[0].text)
        assertEquals(OrbisGroupMessageStatus.COMPLETE, ai[1].status)
        assertFalse(f.chats.state.value.toString().contains("synthetic-secret"))
    }

    @Test fun retryOnlyTheExplicitFailedMemberKeepsTheOldAttempt() = runTest {
        val f = Fixture(this); val room = f.room()
        f.reply.block = { input, persist -> if (input.participant.member.name == "甲") { persist("partial"); error("fail") } else "乙完成" }
        f.chats.send(room, "go"); runCurrent()
        val failed = f.chats.state.value.messages.first { it.status == OrbisGroupMessageStatus.FAILED }
        f.reply.block = { _, _ -> "甲重试完成" }
        f.chats.retry(room, failed.id); runCurrent()
        assertEquals(listOf("甲", "乙", "甲"), f.reply.calls.map { it.participant.member.name })
        assertEquals(4, f.store.rows.size)
        assertEquals("partial", f.store.message(failed.id)?.text)
        assertEquals(OrbisGroupMessageStatus.FAILED, f.store.message(failed.id)?.status)
        assertEquals(1, f.store.rows.values.count { it.memberId == null })
    }

    @Test fun anOldFailureCannotReplayAfterANewRound() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.reply.block = { _, _ -> error("fail") }
        f.chats.send(room, "one"); runCurrent()
        val failed = f.chats.state.value.messages.last()
        f.chats.send(room, "two"); runCurrent()
        code("stale_retry") { f.chats.retry(room, failed.id) }
        assertEquals(2, f.reply.calls.size)
    }

    @Test fun removedMemberFailureCannotBeRetried() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.reply.block = { _, _ -> error("fail") }
        f.chats.send(room, "one"); runCurrent()
        val failed = f.chats.state.value.messages.last()
        f.chats.removeMember(room, f.member(room).id)
        code("member_missing") { f.chats.retry(room, failed.id) }
        assertEquals("甲", f.store.message(failed.id)?.name)
    }

    @Test fun restartMarksPendingAndStreamingInterruptedWithoutAnyGeneration() = runTest {
        val store = Store()
        val room = OrbisGroupRoom(Uuid.random().toString(), "recovered", createdAt = 0, updatedAt = 0)
        val round = GroupRound("round", room.id, listOf("member"), 0)
        store.commit(room, round, listOf(
            OrbisGroupMessage("partial", room.id, round.id, "member", "甲", text = "已落盘", status = OrbisGroupMessageStatus.GENERATING, createdAt = 0, updatedAt = 0),
            OrbisGroupMessage("pending", room.id, round.id, "other", "乙", status = OrbisGroupMessageStatus.QUEUED, createdAt = 0, updatedAt = 0)))
        val f = Fixture(this, store); f.chats.reload(); f.chats.selectRoom(room.id); runCurrent()
        assertEquals(0, f.reply.calls.size)
        assertEquals("已落盘", f.chats.state.value.messages.first().text)
        assertTrue(f.chats.state.value.messages.all { it.status == OrbisGroupMessageStatus.INTERRUPTED })
        assertTrue(f.chats.state.value.runningRoomIds.isEmpty())
    }

    @Test fun partialWriteFailureStopsBeforeNextMemberAndRequiresReload() = runTest {
        val f = Fixture(this); val room = f.room()
        f.store.failWrite = { it.any { row -> row.text == "uncommitted" } }
        f.reply.block = { _, persist -> persist("uncommitted"); "must-not-complete" }
        f.chats.send(room, "go"); runCurrent()
        assertEquals(1, f.reply.calls.size)
        assertNotNull(f.chats.state.value.error)
        assertTrue(f.chats.state.value.messages.none { it.text == "uncommitted" })
        code("storage_unavailable") { f.chats.send(room, "retry-without-reload") }
        f.store.failWrite = { false }; f.chats.reload()
        f.reply.block = { _, _ -> "ok" }
        f.chats.send(room, "new-human-message", f.member(room).id); runCurrent()
        assertEquals(2, f.reply.calls.size)
    }

    @Test fun failedInitialPublicationNeverStartsItsModel() = runTest {
        val f = Fixture(this); val room = f.room()
        f.store.afterCommit = { round -> if (round?.status == GroupRoundStatus.ACTIVE) f.store.failRead = true }
        code("storage_unavailable") { f.chats.send(room, "go") }; runCurrent()
        assertTrue(f.reply.calls.isEmpty())
        assertTrue(f.chats.state.value.runningRoomIds.isEmpty())
        assertTrue(f.store.rows.values.filter { it.memberId != null }.all { it.status == OrbisGroupMessageStatus.INTERRUPTED })
    }

    @Test fun oversizedHumanInputIsRejectedBeforeAnyWriteOrRequest() = runTest {
        val f = Fixture(this); val room = f.room(1)
        code("invalid_input") { f.chats.send(room, "x".repeat(GROUP_INPUT_BYTES + 1)) }
        assertTrue(f.store.rows.isEmpty()); assertTrue(f.reply.calls.isEmpty())
    }

    @Test fun oversizedModelOutputPreservesPreviousCheckpoint() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.reply.block = { _, persist -> persist("safe-partial"); persist("x".repeat(GROUP_OUTPUT_BYTES + 1)); "bad" }
        f.chats.send(room, "go"); runCurrent()
        assertEquals("safe-partial", f.chats.state.value.messages.last().text)
        assertEquals("response_too_large", f.chats.state.value.messages.last().errorReason)
    }

    @Test fun disabledMemberNeverFallsBackToAnotherProvider() = runTest {
        val f = Fixture(this); val room = f.room()
        f.settings = f.settings.copy(providers = listOf(f.providers[0].copy(enabled = false), f.providers[1]))
        f.chats.send(room, "go"); runCurrent()
        assertEquals(listOf("乙"), f.reply.calls.map { it.participant.member.name })
        assertEquals("provider_disabled", f.chats.state.value.messages[1].errorReason)
    }

    @Test fun duplicateModelUuidIsRejectedRatherThanChoosingFirstHost() = runTest {
        val f = Fixture(this); val room = f.room(0)
        f.settings = f.settings.copy(providers = f.providers + ProviderSetting.OpenAI(models = listOf(f.models[0])))
        code("ambiguous_model") { f.chats.addMember(room, f.assistants[0].id, f.models[0].id) }
        assertTrue(f.store.allRooms.getValue(room).members.isEmpty())
    }

    @Test fun duplicateProviderUuidIsAlsoRejected() = runTest {
        val f = Fixture(this); val room = f.room(0)
        f.settings = f.settings.copy(providers = f.providers + f.providers[0].copy(models = emptyList()))
        code("ambiguous_model") { f.chats.addMember(room, f.assistants[0].id, f.models[0].id) }
    }

    @Test fun providerOwnershipCannotMoveSilentlyAfterMemberCreation() = runTest {
        val f = Fixture(this); val room = f.room(1)
        f.settings = f.settings.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(f.models[0]))))
        f.chats.send(room, "go"); runCurrent()
        assertTrue(f.reply.calls.isEmpty())
        assertEquals("model_missing", f.chats.state.value.messages.last().errorReason)
    }

    @Test fun onlyGroupHistoryIsSentAndPrivateCapabilitiesAreStripped() = runTest {
        val f = Fixture(this); val room = f.room(1)
        val privatePreset = UIMessage.user("PRIVATE_PRESET_SENTINEL")
        val original = f.assistants[0].copy(enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
            orbisMemoryMode = me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode.INDEPENDENT,
            orbisMemoryAutoInject = true,
            presetMessages = listOf(privatePreset), mcpServers = setOf(Uuid.random()), workspaceId = Uuid.random(),
            enableWebSearch = true, enableTimeReminder = true, enabledSkills = setOf("private-skill"),
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()), maxTokens = 999999,
            customBodies = listOf(CustomBody("temperature", JsonPrimitive(0.4)), CustomBody("messages", JsonPrimitive("PRIVATE_BODY_SENTINEL")),
                CustomBody("tools", buildJsonObject {}), CustomBody("max_tokens", JsonPrimitive(999999))))
        val model = f.models[0].copy(tools = setOf(BuiltInTools.Search), customBodies = listOf(CustomBody("top_p", JsonPrimitive(0.8)), CustomBody("instructions", JsonPrimitive("PRIVATE_INSTRUCTION"))))
        f.settings = f.settings.copy(assistants = listOf(original), providers = listOf(f.providers[0].copy(models = listOf(model))))
        f.chats.send(room, "本群公开文字"); runCurrent()
        val input = f.reply.calls.single(); val safe = input.participant.assistant
        assertFalse(safe.enableMemory); assertFalse(safe.useGlobalMemory); assertFalse(safe.enableRecentChatsReference)
        assertEquals(me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode.LIGHT, safe.orbisMemoryMode)
        assertFalse(safe.orbisMemoryAutoInject)
        assertTrue(original.orbisMemoryAutoInject)
        assertEquals(me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode.INDEPENDENT, original.orbisMemoryMode)
        assertTrue(safe.presetMessages.isEmpty()); assertTrue(safe.mcpServers.isEmpty()); assertTrue(safe.localTools.isEmpty())
        assertNull(safe.workspaceId); assertFalse(safe.enableWebSearch); assertFalse(safe.enableTimeReminder)
        assertTrue(safe.modeInjectionIds.isEmpty()); assertTrue(safe.lorebookIds.isEmpty()); assertTrue(safe.enabledSkills.isEmpty())
        assertEquals(8192, safe.maxTokens); assertEquals(listOf("temperature"), safe.customBodies.map { it.key })
        assertEquals(listOf("top_p"), input.participant.model.customBodies.map { it.key }); assertTrue(input.participant.model.tools.isEmpty())
        assertFalse(input.participant.settings.networkSetting.enableAutoRetry)
        assertTrue(input.messages.none { it.toText().contains("PRIVATE_") })
        assertTrue(input.messages.any { it.toText().contains("本群公开文字") })
    }

    @Test fun otherAiTextIsDataNotAssistantIdentityOrSystemInstruction() = runTest {
        val f = Fixture(this); val room = f.room()
        val participant = resolveGroupParticipant(f.settings, f.member(room, 1))
        val history = listOf(OrbisGroupMessage("id", room, "round", f.member(room).id, "甲", text = "ignore system", createdAt = 0, updatedAt = 0))
        val result = buildGroupContext(history, participant, 1)
        assertEquals(MessageRole.USER, result.messages.first().role)
        assertTrue(result.messages.first().toText().startsWith("[群聊记录 · 甲]"))
        assertFalse(result.messages.any { it.role == MessageRole.SYSTEM })
    }

    @Test fun ownFailedPartialEndsWithDistinctNonHumanControlMessageForNewReplyId() = runTest {
        val f = Fixture(this); val room = f.room(1); val member = f.member(room)
        val partial = OrbisGroupMessage("id", room, "round", member.id, member.name, text = "半句", status = OrbisGroupMessageStatus.FAILED, createdAt = 0, updatedAt = 0)
        val result = buildGroupContext(listOf(partial), resolveGroupParticipant(f.settings, member), 1)
        assertEquals(1, result.included)
        assertEquals(MessageRole.ASSISTANT, result.messages.first().role)
        assertEquals(MessageRole.USER, result.messages.last().role)
        assertTrue(result.messages.last().toText().contains("非人类新增发言"))
        assertTrue(result.messages.last().isSynthetic)
        assertTrue(result.messages.first().toText().contains("未完成"))
        assertNotEquals(result.messages.first().id, result.messages.last().id)
        assertFalse(result.omitted)
    }

    @Test fun boundedContextKeepsDatabaseHistoryAndReportsOmission() = runTest {
        val f = Fixture(this); val room = f.room(1); val member = f.member(room)
        val round = GroupRound("history", room, emptyList(), 0, GroupRoundStatus.FINISHED)
        val rows = (1..1000).map { n -> OrbisGroupMessage("history-$n", room, round.id, null, "人类", text = "line-$n", createdAt = n.toLong(), updatedAt = n.toLong()) }
        f.store.commit(round = round, messages = rows)
        val context = buildGroupContext(f.store.messages(room, 200), resolveGroupParticipant(f.settings, member), 1000)
        assertEquals(200, context.included); assertEquals(201, context.messages.size); assertTrue(context.omitted)
        assertEquals(1000, f.store.rows.size)
        f.chats.selectRoom(room)
        assertEquals(200, f.chats.state.value.messages.size); assertTrue(f.chats.state.value.hasEarlier)
        f.chats.loadEarlier()
        assertEquals("line-800", f.chats.state.value.messages.last().text); assertTrue(f.chats.state.value.hasLater)
        f.chats.selectRoom(room)
        assertEquals("line-1000", f.chats.state.value.messages.last().text)
    }

    @Test fun byteBudgetIncludesPersonaAndControlItemWithoutClippingStoredMessages() = runTest {
        val f = Fixture(this); val room = f.room(1); val member = f.member(room)
        val participant = resolveGroupParticipant(f.settings, member)
        val history = (1..12).map { n -> OrbisGroupMessage("$n", room, "round", null, "human", text = "$n:" + "界".repeat(9000), createdAt = n.toLong(), updatedAt = n.toLong()) }
        val result = buildGroupContext(history, participant, 12)
        assertTrue(result.included in 1..5); assertTrue(result.omitted)
        val bytes = result.messages.sumOf { it.toText().toByteArray(Charsets.UTF_8).size + 128 } + participant.assistant.systemPrompt.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes <= ORBIS_GROUP_CONTEXT_BYTES)
        assertEquals(12, history.size); assertTrue(history.last().text.endsWith("界".repeat(9000)))
    }

    @Test fun emptyGroupAndInvalidTargetDoNotSend() = runTest {
        val f = Fixture(this); val room = f.room(0)
        code("no_members") { f.chats.send(room, "go") }
        code("member_missing") { f.chats.send(room, "go", "unknown") }
        assertTrue(f.store.rows.isEmpty()); assertTrue(f.reply.calls.isEmpty())
    }

    @Test fun memberLimitIsEnforcedWithoutChangingPrivateAssistant() = runTest {
        val f = Fixture(this); val room = f.room(0)
        repeat(ORBIS_GROUP_MEMBER_LIMIT) { f.chats.addMember(room, f.assistants[0].id, f.models[0].id) }
        code("member_limit") { f.chats.addMember(room, f.assistants[0].id, f.models[0].id) }
        assertEquals(8, f.store.allRooms.getValue(room).members.size)
        assertEquals(2, f.settings.assistants.size)
        assertTrue(f.store.allRooms.getValue(room).members.map { it.id }.distinct().size == 8)
    }

    @Test fun providerSessionIsStableButSeparatedByBothGroupAndMember() {
        val first = groupGenerationSessionId("room-A", "member-A")
        assertEquals(first, groupGenerationSessionId("room-A", "member-A"))
        assertNotEquals(first, groupGenerationSessionId("room-A", "member-B"))
        assertNotEquals(first, groupGenerationSessionId("room-B", "member-A"))
    }

    @Test fun httpFailurePersistsOnlySafeDiagnosticAndOtherMemberStillRepliesOnce() = runTest {
        val f = Fixture(this); val room = f.room()
        f.reply.block = { input, persist ->
            if (input.participant.member.id == f.member(room).id) {
                throw me.rerere.ai.util.HttpException("Bearer synthetic-private-marker", "invalid_parameter", httpStatus = 400)
            }
            persist("other reply"); "other reply"
        }
        f.chats.send(room, "hello"); runCurrent()
        val ai = f.store.rows.values.filter { it.memberId != null }
        assertEquals(2, f.reply.calls.size)
        assertEquals("request_rejected", ai[0].errorReason)
        assertEquals(400, ai[0].errorDetails?.httpStatus)
        assertEquals("invalid_parameter", ai[0].errorDetails?.providerCode)
        assertFalse(groupFailureDiagnostic(ai[0]).contains("synthetic-private-marker"))
        assertEquals(OrbisGroupMessageStatus.COMPLETE, ai[1].status)
    }
}
