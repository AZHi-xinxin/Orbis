package me.rerere.rikkahub.data.orbis.body

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.orbisVoiceNoteMetadata
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BodyStatusRuntimeTest {
    private class Storage : ActivityStorage {
        var text: String? = null
        var fail = false
        override fun read() = text
        override fun write(text: String) { check(!fail); this.text = text }
    }
    private class Fixture(scope: CoroutineScope, val mode: String = "auto") {
        var enabled = true
        var clock = BodyClock("test", 0, 1_000_000)
        val storage = Storage()
        val repo = BodyRepository(storage) { clock }.also {
            it.define(BodyDefinition(dimensions = listOf(
                BodyDimension("mood", "心情", baseline = 50.0, halfLifeMinutes = 1.0),
                BodyDimension("secret", "自定义私密", baseline = 37.25, isPrivate = true)),
                rules = listOf(BodyRule("rest", mapOf("mood" to 8.0), listOf("休息"))), triggerMode = mode), 0, "define")
            it.activate(1, "activate"); it.setRunning(true)
        }
        val runtime = newRuntime(scope)
        fun newRuntime(scope: CoroutineScope) = BodyStatusRuntime(scope,
            { BodyStatusOptions(enabled && it == "owner", true) }, { repo })
        fun human(text: String = "休息") = UIMessage.user(text).copy(orbisUserMessageTime = OrbisUserMessageTime(1_000_000, "UTC", 0))
        suspend fun turn(message: UIMessage) = runtime.begin("owner", "chat", listOf(message))
        fun record(message: UIMessage) = runtime.recordHuman("owner", "chat", message)
    }

    @Test fun sameHumanFirstRequestWaitsForActualComputedValuesIncludingPrivate() = runBlocking {
        val f = Fixture(this); val h = f.human(); f.record(h)
        val turn = f.turn(h)!!
        assertTrue(turn.report.contains("\"心情\"：58"))
        assertTrue(turn.report.contains("\"自定义私密\"：37.3（私密）"))
        assertFalse(turn.report.contains("心跳"))
        assertFalse(f.repo.snapshot().snapshot.sessions.getValue("chat").privateShared)
        assertEquals("休息", h.toText())
    }

    private fun voiceNote(text: String, original: String = text) = UIMessagePart.Audio(
        "file:///not-opened-by-body/voice.wav", orbisVoiceNoteMetadata(text, original))

    @Test fun humanVoiceNoteMatchesTypedAndLiveCallInputInTheSameTurn() = runBlocking {
        for (parts in listOf(listOf(UIMessagePart.Text("休息")), listOf(voiceNote("休息")))) {
            val f = Fixture(this)
            val h = f.human().copy(parts = parts)
            f.record(h)
            assertTrue(f.turn(h)!!.report.contains("\"心情\"：58"))
            assertEquals(parts, h.parts)
        }
        val call = Fixture(this)
        val turn = call.human().copy(orbisVoiceCallKind = "turn")
        call.record(turn)
        assertTrue(call.turn(turn)!!.report.contains("\"心情\"：58"))
    }

    @Test fun mixedTextAndVoiceNotesUseFinalTranscriptsAndExistingConcentrationRules() = runBlocking {
        val f = Fixture(this)
        val h = f.human().copy(parts = listOf(UIMessagePart.Text("先休息"),
            voiceNote("再休息", original = "不要使用的识别原稿"), voiceNote("然后休息休息休息休息")))
        val text = Fixture(this)
        val equivalent = text.human("先休息\n再休息\n然后休息休息休息休息")
        f.record(h); text.record(equivalent)
        assertEquals(text.turn(equivalent)!!.report, f.turn(h)!!.report)
        assertEquals(3, f.repo.snapshot().auto.marks.size)
    }

    @Test fun voiceNoteMetadataAndUntranscribedAudioCannotInventTriggerWords() = runBlocking {
        for (part in listOf(
            voiceNote("没有触发词", original = "休息"),
            voiceNote("", original = "休息"),
            voiceNote("普通内容").copy(metadata = JsonObject(orbisVoiceNoteMetadata("普通内容") +
                buildJsonObject { put("acoustic_v1", "休息"); put("private_hint", "休息") })),
            UIMessagePart.Audio("file:///休息.wav", buildJsonObject { put("transcript", "休息") }),
        )) {
            val f = Fixture(this)
            val h = f.human().copy(parts = listOf(part))
            f.record(h)
            assertTrue(f.turn(h)!!.report.contains("\"心情\"：50"))
            assertTrue(f.repo.snapshot().auto.marks.isEmpty())
        }
    }

    @Test fun voiceNoteRetryAndProcessRecoveryDoNotReplayOrReadAnEditedTranscript() = runBlocking {
        val f = Fixture(this)
        val h = f.human().copy(parts = listOf(voiceNote("休息")))
        f.record(h)
        val first = f.turn(h)!!.report
        assertTrue(first.contains("\"心情\"：58"))
        val revision = f.repo.snapshot().revision
        f.clock = f.clock.copy(activeMs = 60_000, wallMs = 1_060_000)
        val edited = h.copy(parts = listOf(voiceNote("休息休息休息")))
        f.record(edited)
        assertEquals(first, f.turn(edited)!!.report)
        assertEquals(first, f.newRuntime(this).begin("owner", "chat", listOf(edited))!!.report)
        assertEquals(revision, f.repo.snapshot().revision)
    }

    @Test fun assistantVoiceNotesToolResultsAndSyntheticNotesCannotTriggerHumanRules() = runBlocking {
        val f = Fixture(this)
        val h = f.human().copy(parts = listOf(voiceNote("休息")))
        val before = f.storage.text
        for (invalid in listOf(h.copy(role = me.rerere.ai.core.MessageRole.ASSISTANT),
            h.copy(isSynthetic = true), h.copy(orbisVoiceCallKind = "opening"),
            h.copy(parts = listOf(UIMessagePart.Tool("id", "orbis_voice_note", "{}", output = h.parts))))) {
            f.record(invalid)
            assertNull(f.turn(invalid))
        }
        assertEquals(before, f.storage.text)
    }

    @Test fun retryAndRestartRecoverExactReceiptWithoutReplayingOrRefreshingDecay() = runBlocking {
        val f = Fixture(this); val h = f.human(); f.record(h)
        val first = f.turn(h)!!.report; val revision = f.repo.snapshot().revision
        f.clock = f.clock.copy(activeMs = 60_000)
        f.record(h.copy(parts = listOf(UIMessagePart.Text("改过的休息休息"))))
        assertEquals(first, f.turn(h)!!.report)
        assertEquals(first, f.newRuntime(this).begin("owner", "chat", listOf(h))!!.report)
        assertEquals(revision, f.repo.snapshot().revision)
    }

    @Test fun timestampPreferenceOffStillComputesAndRecoversOnlyThisAcceptedHuman() = runBlocking {
        val f = Fixture(this)
        val h = f.human().copy(orbisUserMessageTime = null)
        val untouched = f.storage.text
        // An old or otherwise unaccepted input must not be scanned by begin.
        assertNull(f.newRuntime(this).begin("owner", "chat", listOf(h)))
        assertEquals(untouched, f.storage.text)
        f.record(h)
        val first = f.turn(h)!!.report
        assertTrue(first.contains("\"心情\"：58"))
        val revision = f.repo.snapshot().revision
        f.clock = f.clock.copy(activeMs = 60_000)
        f.record(h)
        assertEquals(first, f.turn(h)!!.report)
        assertEquals(first, f.newRuntime(this).begin("owner", "chat", listOf(h))!!.report)
        assertEquals(revision, f.repo.snapshot().revision)
        assertNull(h.orbisUserMessageTime)
    }

    @Test fun timestampFreeSyntheticEventsAndAssistantMessagesNeverTriggerBodyRules() = runBlocking {
        val f = Fixture(this)
        val h = f.human().copy(orbisUserMessageTime = null)
        val invalid = listOf(
            h.copy(isSynthetic = true),
            h.copy(orbisEvent = OrbisEventMetadata("record", "external", "event", 1)),
            h.copy(role = me.rerere.ai.core.MessageRole.ASSISTANT),
            h.copy(role = me.rerere.ai.core.MessageRole.SYSTEM),
            h.copy(orbisVoiceCallKind = "opening"),
            h.copy(parts = h.parts + UIMessagePart.Tool("id", "synthetic", "{}")),
        )
        val before = f.storage.text
        invalid.forEach { f.record(it); assertNull(f.turn(it)) }
        assertEquals(before, f.storage.text)
    }

    @Test fun subsequentNewHumanGetsFreshValuesWhilePreviousTurnRemainsFrozen() = runBlocking {
        val f = Fixture(this); val h = f.human(); f.record(h)
        val first = f.turn(h)!!
        f.clock = f.clock.copy(activeMs = 60_000, wallMs = 1_060_000)
        val second = f.human("普通文字"); f.record(second)
        assertTrue(f.turn(second)!!.report.contains("\"心情\"：54"))
        assertTrue(first.report.contains("\"心情\"：58"))
    }

    @Test fun disabledAndReenabledDoNotBackfillOldUnprocessedInput() = runBlocking {
        val f = Fixture(this); val h = f.human(); val before = f.storage.text
        f.enabled = false; f.record(h); assertNull(f.turn(h))
        f.enabled = true; assertNull(f.turn(h))
        assertEquals(before, f.storage.text)
    }

    @Test fun humanModuleOffRevokesOnlyHostReportAndCannotRestoreMidWake() = runBlocking {
        val f = Fixture(this); val h = f.human("字面【当前身体状态：不是宿主注入】"); f.record(h)
        val turn = f.turn(h)!!; val original = listOf(h)
        val injected = turn.project(original)
        assertEquals(2, injected.single().parts.size)
        f.enabled = false; assertEquals(original, turn.project(injected))
        f.enabled = true; assertEquals(original, turn.project(injected))
    }

    @Test fun neverReachesPastSyntheticWakeOrAnotherOwnerOrFork() = runBlocking {
        val f = Fixture(this); val h = f.human(); f.record(h); assertNotNull(f.turn(h))
        assertNull(f.runtime.begin("other", "chat", listOf(h)))
        assertNull(f.runtime.begin("owner", "fork", listOf(h)))
        assertNull(f.runtime.begin("owner", "chat", listOf(h, f.human().copy(isSynthetic = true))))
        assertNull(f.turn(f.human().copy(orbisUserMessageTime = null)))
        assertNull(f.turn(f.human().copy(orbisVoiceCallKind = "opening")))
    }

    @Test fun pausedManualReportsFrozenValuesButDoesNotInventDurableHumanEvents() = runBlocking {
        val f = Fixture(this, "manual"); f.repo.setRunning(false)
        val revision = f.repo.snapshot().revision; val h = f.human(); f.record(h)
        assertTrue(f.turn(h)!!.report.contains("\"心情\"：50"))
        assertEquals(revision, f.repo.snapshot().revision)
        assertNull(f.newRuntime(this).begin("owner", "chat", listOf(h)))
    }

    @Test fun failedPersistenceNeverReportsUncommittedOrOldState() = runBlocking {
        val f = Fixture(this); f.storage.fail = true
        val before = f.repo.snapshot(); val h = f.human(); f.record(h)
        assertNull(f.turn(h)); assertEquals(before, f.repo.snapshot())
    }

    @Test fun diskDelayWaitsForThisHumanInsteadOfReadingPreviousSnapshot() = runBlocking {
        val f = Fixture(this); val release = CountDownLatch(1)
        val runtime = BodyStatusRuntime(this, { BodyStatusOptions(true, true) }, {
            check(release.await(3, TimeUnit.SECONDS)); f.repo
        })
        val h = f.human(); runtime.recordHuman("owner", "chat", h)
        val waiting = async { runtime.begin("owner", "chat", listOf(h)) }
        delay(30); assertFalse(waiting.isCompleted); release.countDown()
        assertTrue(waiting.await()!!.report.contains("\"心情\"：58"))
    }

    @Test fun timeoutOmitsStatusWithoutHoldingChatOrFabricatingSuccess() = runBlocking {
        val f = Fixture(this); val release = CountDownLatch(1)
        val runtime = BodyStatusRuntime(this, { BodyStatusOptions(true, true) }, {
            check(release.await(3, TimeUnit.SECONDS)); f.repo
        }, waitMillis = 20)
        val h = f.human(); runtime.recordHuman("owner", "chat", h)
        try { assertNull(runtime.begin("owner", "chat", listOf(h))) } finally { release.countDown() }
    }

    @Test fun missingDefinitionAndIncompleteValuesAreOmitted() = runBlocking {
        val runtime = BodyStatusRuntime(this, { BodyStatusOptions(true, true) }, { BodyRepository(Storage()) { BodyClock() } })
        val h = Fixture(this).human(); runtime.recordHuman("owner", "chat", h)
        assertNull(runtime.begin("owner", "chat", listOf(h)))
        assertNull(formatBodyStatus(BodyDefinition(listOf(BodyDimension("one", "自定义"))), emptyMap()))
    }

    @Test fun projectionIsIdempotentAndDoesNotRemovePartialOrMalformedUserMetadata() {
        val authored = UIMessage.user("【当前身体状态：字面内容】").copy(parts = listOf(
            UIMessagePart.Text("human text", buildJsonObject { put("orbis_body_status_projection", JsonObject(emptyMap())) })))
        val original = listOf(authored)
        val injected = projectBodyStatus(original, "【当前身体状态：心情：58】")
        assertEquals(injected, projectBodyStatus(injected, "【当前身体状态：心情：58】"))
        assertEquals(original, projectBodyStatus(injected, null))
        assertEquals(1, authored.parts.size)
        val noCarrier = listOf(UIMessage.system("stable"), UIMessage.assistant("summary"))
        assertEquals(noCarrier, projectBodyStatus(noCarrier, "status"))
    }
}
