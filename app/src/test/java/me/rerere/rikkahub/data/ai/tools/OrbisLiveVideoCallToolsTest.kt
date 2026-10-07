package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.data.orbis.contact.IncomingCallOutcome
import me.rerere.rikkahub.data.orbis.voice.OrbisVideoFrame
import me.rerere.rikkahub.service.MessageQueue
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionController
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrbisLiveVideoCallToolsTest {
    private fun accepted(callId: String = "call-a") = IncomingCallAttempt(
        id = "attempt-a", assistantId = "assistant-a", conversationId = "conversation-a",
        reason = "synthetic test", startedAtMs = 1, ringSeconds = 30,
        outcome = IncomingCallOutcome.CONNECTED, finishedAtMs = 2,
        connectedCallId = callId, video = true,
    )
    private val startArgs = buildJsonObject { put("reason", "synthetic test") }
    private val emptyArgs = buildJsonObject {}
    private fun List<UIMessagePart>.receipt() = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject
    private fun JsonObject.code() = getValue("reason_code").jsonPrimitive.content

    @Test fun `same frozen tool set captures the exact call its start accepted`() = runTest {
        val captured = mutableListOf<String>()
        val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", null,
            requestCall = { accepted() }, captureFrame = { callId ->
                captured += callId
                OrbisVideoFrame("frame-a", 3, 100)
            })
        assertEquals("video_call_not_bound", tools[1].execute(emptyArgs).receipt().code())
        assertTrue(captured.isEmpty())
        assertEquals("connected", tools[0].execute(startArgs).receipt().getValue("outcome").jsonPrimitive.content)
        val result = tools[1].execute(emptyArgs)
        assertEquals(listOf("call-a"), captured)
        assertEquals("orbis-video-frame://call-a/frame-a", (result[1] as UIMessagePart.Image).url)
    }

    @Test fun `only this assistant conversation and explicitly accepted video receipt can bind`() = runTest {
        val invalid = listOf(
            accepted().copy(assistantId = "assistant-b"),
            accepted().copy(conversationId = "conversation-b"),
            accepted().copy(video = false),
            accepted().copy(outcome = IncomingCallOutcome.REJECTED),
            accepted().copy(outcome = IncomingCallOutcome.NO_RESPONSE),
            accepted().copy(outcome = IncomingCallOutcome.FAILED),
            accepted().copy(outcome = IncomingCallOutcome.CONNECTING),
            accepted().copy(connectedCallId = null),
            accepted().copy(connectedCallId = " "),
        )
        for (receipt in invalid) {
            val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", null,
                requestCall = { receipt }, captureFrame = { error("must not capture an unaccepted call") })
            tools[0].execute(startArgs)
            val result = tools[1].execute(emptyArgs).receipt()
            assertEquals("video_call_not_bound", result.code())
            assertFalse(result.getValue("execution_performed").jsonPrimitive.boolean)
        }
    }

    @Test fun `late generation never retargets an existing or already accepted binding`() = runTest {
        for (initial in listOf(null, "call-old")) {
            var requestNumber = 0
            val captured = mutableListOf<String>()
            val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", initial,
                requestCall = { accepted("call-${++requestNumber}") }, captureFrame = { id ->
                    captured += id
                    error("exact old call is no longer active; another call is now live")
                })
            tools[0].execute(startArgs)
            tools[0].execute(startArgs)
            assertEquals("video_frame_unavailable", tools[1].execute(emptyArgs).receipt().code())
            assertEquals(listOf(initial ?: "call-1"), captured)
        }
    }

    @Test fun `camera pause or storage failure returns a completed receipt without exposing exception details`() = runTest {
        val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", "call-a",
            requestCall = { error("must not start") }, captureFrame = { error("private-sentinel") })
        val result = tools[1].execute(emptyArgs)
        assertEquals("video_frame_unavailable", result.receipt().code())
        assertFalse(result.toString().contains("private-sentinel"))
        assertEquals(JsonNull, result.receipt()["execution_performed"])
        assertFalse(result.receipt().getValue("frame_available").jsonPrimitive.boolean)
    }

    @Test fun `local capture timeout is a receipt while the generation remains active`() = runTest {
        val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", "call-a",
            requestCall = { error("must not start") }, captureFrame = {
                withTimeout(8_000) { delay(9_000); OrbisVideoFrame("never", 3, 100) }
            })
        assertEquals("video_frame_timeout", tools[1].execute(emptyArgs).receipt().code())
    }

    @Test fun `outer timeout and explicit cancellation still stop the tool chain`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", "call-a",
            requestCall = { error("must not start") }, captureFrame = {
                entered.complete(Unit)
                delay(10_000)
                OrbisVideoFrame("never", 3, 100)
            })
        try {
            withTimeout(100) { tools[1].execute(emptyArgs) }
            fail("outer timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        var continued = false
        val job = launch { tools[1].execute(emptyArgs); continued = true }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(continued)
    }

    @Test fun `same generation frame failure leaves queued BEGIN handshake able to connect`() = runTest {
        val queue = MessageQueue()
        val connectedReceipt = CompletableDeferred<IncomingCallAttempt>()
        val begin = CompletableDeferred<String?>()
        var ended = false
        val voice = VoiceSessionController(backgroundScope, { it.toString() }) { error("muted call") }
        val tools = createOrbisLiveVideoCallTools("assistant-a", "conversation-a", null,
            requestCall = { connectedReceipt.await() }, captureFrame = { error("camera warming up") })
        try {
            voice.start(createAsr = { error("muted call must not open microphone") }, speak = null,
                stopSpeaking = {}, initialMicrophoneEnabled = false,
                onConnected = {
                    // Same order as ChatService: durable acceptance releases the incoming tool,
                    // while BEGIN remains behind the generation that invoked that tool.
                    connectedReceipt.complete(accepted())
                    queue.enqueue(listOf(UIMessagePart.Text("BEGIN")), answer = false, reply = begin,
                        voiceCallId = "call-a", voiceCallKind = "begin")
                    begin.await()
                }, onEnded = { ended = true })
            advanceTimeBy(201); runCurrent()
            tools[0].execute(startArgs)
            assertEquals("video_frame_unavailable", tools[1].execute(emptyArgs).receipt().code())
            assertFalse(queue.state.value.paused)
            assertFalse(ended)
            val queued = checkNotNull(queue.takeNext())
            assertEquals("begin", queued.voiceCallKind)
            queued.reply!!.complete("")
            runCurrent()
            assertEquals(VoicePhase.Listening, voice.state.value.phase)
            assertFalse(ended)
            assertNull(voice.state.value.error)
        } finally { voice.stopAndJoin() }
    }
}
