package me.rerere.rikkahub.data.orbis.voice

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisVoiceCallTools
import me.rerere.rikkahub.testutil.IsolatedVoiceCallArchive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android AtomicFile, but exclusively synthetic data in a checked random cache directory. */
@RunWith(AndroidJUnit4::class)
class OrbisVoiceCallPersistenceTest {
    private lateinit var fixture: IsolatedVoiceCallArchive

    @Before fun createOnlyOwnedFixture() { fixture = IsolatedVoiceCallArchive() }
    @After fun removeOnlyOwnedFixture() { if (::fixture.isInitialized) fixture.close() }

    private fun record(id: String = "synthetic-call", assistant: String = "assistant-a", conversation: String = "window-a") =
        OrbisVoiceCallRecord(id, conversation, assistant, 1000, connectedAtMs = 2000,
            endedAtMs = 82000, durationMs = 80000, status = OrbisVoiceCallStatus.ENDED,
            archiveStatus = OrbisVoiceArchiveStatus.READY, summary = "合成摘要：约好明天散步。",
            modelTranscript = "用户：明天去散步。\nAI：好，明天见。",
            transcript = listOf(OrbisVoiceTranscriptEntry("user-turn", "user", "明天去散步。", 3000, "user-message"),
                OrbisVoiceTranscriptEntry("assistant-turn", "assistant", "好，明天见。", 4000, "assistant-message")),
            sourceMessageIds = listOf("source-node-user", "source-node-ai"),
            sourceNodesJson = "[{\"synthetic\":true,\"toolResult\":\"precise-original\",\"alternatives\":[1,2]}]")

    @Test fun atomicArchiveReopensWithExactOriginalAndSeparateAiAuthoredSummary() = runBlocking {
        val original = record()
        fixture.repository().create(original)
        val expectedPath = File(fixture.directory, "orbis-voice-calls/${original.id}.json")
        assertTrue(expectedPath.isFile)
        assertEquals(setOf("orbis-voice-calls/${original.id}.json"), fixture.bytes().keys)
        val before = fixture.bytes()
        val reopened = fixture.repository().get(original.id)!!
        assertEquals(original, reopened)
        assertEquals(original.transcript, reopened.transcript)
        assertEquals(original.sourceNodesJson, reopened.sourceNodesJson)
        assertEquals(original.modelTranscript, reopened.modelTranscript)
        assertEquals(original.summary, reopened.summary)
        assertEquals(before, fixture.bytes())
    }

    @Test fun uncommittedAtomicNewFileCannotReplaceTheLastCompleteArchive() = runBlocking {
        val original = record()
        fixture.repository().create(original)
        val base = File(fixture.directory, "orbis-voice-calls/${original.id}.json")
        val before = base.readBytes()
        val atomic = AtomicFile(base)
        val unfinished = atomic.startWrite()
        unfinished.write("{\"partial-synthetic\":".toByteArray(Charsets.UTF_8))
        unfinished.fd.sync()
        unfinished.close() // Simulate death before finishWrite; never touch the production archive.
        assertEquals(original, fixture.repository().get(original.id))
        assertArrayEquals(before, base.readBytes())
    }

    @Test fun interruptedCallRecoveryPreservesSourceAndLeavesUnknownTimesUnsetOnAndroidStorage() = runBlocking {
        val active = record().copy(status = OrbisVoiceCallStatus.ACTIVE, endedAtMs = null, durationMs = null,
            archiveStatus = OrbisVoiceArchiveStatus.PENDING, summary = null, modelTranscript = null)
        fixture.repository().create(active)
        val recovered = fixture.repository().recoverInterrupted().single()
        assertEquals(OrbisVoiceCallStatus.INTERRUPTED, recovered.status)
        assertNull(recovered.endedAtMs)
        assertNull(recovered.durationMs)
        assertEquals(active.transcript, recovered.transcript)
        assertEquals(active.sourceNodesJson, recovered.sourceNodesJson)
        assertEquals(recovered, fixture.repository().get(active.id))
    }

    @Test fun reopenedAndroidArchiveToolsStayScopedToAssistantAcrossWindows() = runBlocking {
        val repository = fixture.repository()
        repository.create(record("own-a", conversation = "window-a"))
        repository.create(record("own-b", conversation = "window-b"))
        repository.create(record("foreign", assistant = "assistant-b").copy(summary = "foreign-private-sentinel"))
        val before = fixture.bytes()
        val tools = createOrbisVoiceCallTools(fixture.repository(), "assistant-a").associateBy { it.name }
        val listed = tools.getValue("orbis_call_records").execute(buildJsonObject {})
        val listText = (listed.single() as UIMessagePart.Text).text
        val ids = Json.parseToJsonElement(listText).jsonObject.getValue("records").jsonArray
            .map { it.jsonObject.getValue("call_id").jsonPrimitive.content }.toSet()
        assertEquals(setOf("own-a", "own-b"), ids)
        assertFalse(listText.contains("foreign-private-sentinel"))
        val error = runCatching { tools.getValue("orbis_call_read").execute(buildJsonObject {
            put("call_id", "foreign"); put("assistant_id", "assistant-b")
        }) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(error!!.message.orEmpty().contains("foreign-private-sentinel"))
        assertEquals(before, fixture.bytes())
    }
}
