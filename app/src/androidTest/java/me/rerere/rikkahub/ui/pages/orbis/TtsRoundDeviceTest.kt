package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.pcm16MonoWav
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer
import me.rerere.tts.controller.*
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loopback requests and silent PCM on the owned emulator; no real account, TTS, or microphone. */
@RunWith(AndroidJUnit4::class)
class TtsRoundDeviceTest {
    private fun context(): android.content.Context {
        assumeTrue(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, inst.targetContext.applicationContext.javaClass)
        return inst.targetContext
    }

    private fun provider(server: PrivateRoomLoopbackServer, voice: String) = TTSProviderSetting.OpenAI(
        baseUrl = server.url("/v1"), apiKey = "synthetic-test-only", model = "fixture-tts", voice = voice, strictEndpoint = true)

    private fun response(seconds: Int = 3) = PrivateRoomLoopbackResponse()
        .setBody(pcm16MonoWav(ByteArray(16_000 * 2 * seconds), 16_000)).addHeader("Content-Type", "audio/wav")

    private fun player(controller: TtsController): AudioPlayer = TtsController::class.java.getDeclaredField("audio")
        .apply { isAccessible = true }.get(controller) as AudioPlayer

    private fun snapshot(controller: TtsController): VoicePlaybackSnapshot = AudioPlayer::class.java.getDeclaredField("playbackSnapshot")
        .apply { isAccessible = true }.get(player(controller)) as VoicePlaybackSnapshot

    private fun engine(controller: TtsController): Any = AudioPlayer::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(player(controller))!!

    // Media3 is an implementation dependency of speech. Reflect its public Player API rather
    // than importing it here or invoking methods declared on a package-private implementation.
    private fun playbackSpeed(controller: TtsController): Float {
        val parameters = Class.forName("androidx.media3.common.Player")
            .getMethod("getPlaybackParameters").invoke(engine(controller))
        return Class.forName("androidx.media3.common.PlaybackParameters")
            .getField("speed").getFloat(parameters)
    }

    private fun playbackVolume(controller: TtsController): Float =
        Class.forName("androidx.media3.common.Player").getMethod("getVolume")
            .invoke(engine(controller)) as Float

    @Test fun completeUtteranceMakesOneRequestAndSettingsDuringSynthesisApplyOnlyNextRound() = runBlocking {
        val context = context()
        val previous = VoiceMixer.config.value
        PrivateRoomLoopbackServer().use { server ->
            val firstMix = VoiceMixerConfig(true, VoiceMix(.9f, -3f, .4f, VoiceSpace.FOREST, .3f, .5f, VoiceFilter.SOFT))
            val nextMix = VoiceMixerConfig(true, VoiceMix(1.25f, 5f, .8f, VoiceSpace.TUNNEL, .7f, .9f, VoiceFilter.ROBOT))
            val firstProvider = provider(server, "first")
            val nextProvider = provider(server, "next")
            val controller = withContext(Dispatchers.Main) { TtsController(context, TTSManager(context)).also {
                it.setProvider(firstProvider); it.setSpeed(1.1f); it.setOutputMuted(true)
            } }
            try {
                val text = "这一整轮要连贯地读出，不按一百六十字人为切断。\n\n".repeat(18)
                withContext(Dispatchers.Main) { controller.speak(text, mixerConfig = firstMix) }
                val request = withContext(Dispatchers.IO) { server.takeRequest() }
                assertEquals(text, Json.parseToJsonElement(request.body).jsonObject.getValue("input").jsonPrimitive.content)
                withContext(Dispatchers.Main) {
                    controller.setProvider(nextProvider); controller.setSpeed(.8f); VoiceMixer.apply(nextMix)
                }
                // Response is deliberately withheld until the user has changed all pending settings.
                server.enqueue(response())
                withTimeout(15_000) { controller.playbackState.first { it.status == PlaybackStatus.Playing } }
                withContext(Dispatchers.Main) {
                    assertEquals(VoicePlaybackSnapshot.capture(1.1f, firstMix), snapshot(controller))
                    assertEquals(.99f, playbackSpeed(controller), .0001f)
                    assertEquals(0f, playbackVolume(controller), 0f)
                }
                withTimeout(15_000) { controller.isSpeaking.first { !it } }
                assertNull(controller.error.value)
                assertEquals(1, server.requestCount)
                server.enqueue(response())
                withContext(Dispatchers.Main) { controller.speak("下一轮") }
                val next = withContext(Dispatchers.IO) { server.takeRequest() }
                assertEquals("next", Json.parseToJsonElement(next.body).jsonObject.getValue("voice").jsonPrimitive.content)
                withTimeout(15_000) { controller.playbackState.first { it.status == PlaybackStatus.Playing } }
                withContext(Dispatchers.Main) {
                    assertEquals(VoicePlaybackSnapshot.capture(.8f, nextMix), snapshot(controller))
                    assertEquals(1f, playbackSpeed(controller), .0001f)
                }
                withTimeout(15_000) { controller.isSpeaking.first { !it } }
                assertNull(controller.error.value)
                assertEquals(2, server.requestCount)
            } finally {
                withContext(Dispatchers.Main) { controller.dispose() }
                VoiceMixer.apply(previous)
            }
        }
    }

    @Test fun queuedRoundKeepsItsProviderEvenAfterPendingProviderIsRemoved() = runBlocking {
        val context = context()
        PrivateRoomLoopbackServer().use { server ->
            val controller = withContext(Dispatchers.Main) { TtsController(context, TTSManager(context)).also {
                it.setProvider(provider(server, "one")); it.setOutputMuted(true)
                it.speak("第一轮", mixerConfig = VoiceMixerConfig())
            } }
            try {
                val first = withContext(Dispatchers.IO) { server.takeRequest() }
                assertEquals("one", Json.parseToJsonElement(first.body).jsonObject.getValue("voice").jsonPrimitive.content)
                val secondMix = VoiceMixerConfig(true, VoiceMix(1.2f, -4f, .5f, VoiceSpace.HALL))
                withContext(Dispatchers.Main) {
                    controller.setProvider(provider(server, "two"))
                    controller.speak("排队的第二轮", flush = false, mixerConfig = secondMix)
                    controller.setProvider(null)
                }
                server.enqueue(response(1))
                server.enqueue(response())
                val second = withContext(Dispatchers.IO) { server.takeRequest() }
                val secondBody = Json.parseToJsonElement(second.body).jsonObject
                assertEquals("two", secondBody.getValue("voice").jsonPrimitive.content)
                assertEquals("排队的第二轮", secondBody.getValue("input").jsonPrimitive.content)
                withTimeout(15_000) { controller.playbackState.first { it.status == PlaybackStatus.Playing && it.currentChunkIndex == 2 } }
                withContext(Dispatchers.Main) { assertEquals(secondMix, snapshot(controller).config) }
                withTimeout(15_000) { controller.isSpeaking.first { !it } }
                assertNull(controller.error.value)
                assertEquals(2, server.requestCount)
                assertFalse(controller.isAvailable.value)
            } finally { withContext(Dispatchers.Main) { controller.dispose() } }
        }
    }
}
