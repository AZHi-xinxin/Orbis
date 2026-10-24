package me.rerere.rikkahub.data.orbis.body

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
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

    @Test fun retryAndRestartRecoverExactReceiptWithoutReplayingOrRefreshingDecay() = runBlocking {
        val f = Fixture(this); val h = f.human(); f.record(h)
        val first = f.turn(h)!!.report; val revision = f.repo.snapshot().revision
        f.clock = f.clock.copy(activeMs = 60_000)
        f.record(h.copy(parts = listOf(UIMessagePart.Text("改过的休息休息"))))
        assertEquals(first, f.turn(h)!!.report)
        assertEquals(first, f.newRuntime(this).begin("owner", "chat", listOf(h))!!.report)
        assertEquals(revision, f.repo.snapshot().revision)
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
