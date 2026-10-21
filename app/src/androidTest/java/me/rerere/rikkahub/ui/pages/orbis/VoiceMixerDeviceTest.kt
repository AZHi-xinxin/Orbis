package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.pcm16MonoWav
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.tts.controller.*
import me.rerere.tts.model.*
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceMixerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun store(): SettingsStore {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, inst.targetContext.applicationContext.javaClass)
        return SettingsStore(inst.targetContext, AppScope().also { it.cancel() }).also {
            it.settingsFlow.value = Settings(providers = emptyList(), mcpServers = emptyList(), ttsProviders = emptyList())
        }
    }
    @Test fun fullScreenMixerHasReachableControlsAndDoesNotStartACall() {
        val store = store()
        VoiceMixer.apply(VoiceMixerConfig())
        compose.setContent { MaterialTheme { OrbisVoiceMixer({}, "本机试听", store) } }
        compose.onNodeWithText("语音台").assertIsDisplayed()
        compose.onNodeWithTag("voice-preview-text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice-preview-start").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        val window = compose.onNodeWithTag("companion-window").fetchSemanticsNode().boundsInRoot
        assertTrue(window.width > 300f && window.height > window.width)
        val bitmap = compose.onNodeWithTag("companion-window").captureToImage().asAndroidBitmap()
        val root = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(root, "phase123-voice-mixer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("mixer-语速").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-音高").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-音量").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-space-SMALL_ROOM").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-距离").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-空间回响").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mixer-filter-TELEPHONE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("启用声学提示").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复原声").assertIsDisplayed()
        assertFalse(OrbisVoiceCallRuntime.get(compose.activity).callState.value.isActive)
    }
    @Test fun changingEffectsAndMuteKeepsTheExistingPlaybackClock() = runBlocking {
        store()
        val player = withContext(Dispatchers.Main) { AudioPlayer(compose.activity) }
        val sampleRate = 16000
        val pcm = ByteArray(sampleRate * 2 * 8)
        // Quiet synthetic tone, entirely local. No microphone, provider, network or user audio.
        for (i in 0 until pcm.size / 2) {
            val value = (kotlin.math.sin(i * 2 * Math.PI * 220 / sampleRate) * 50).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        VoiceMixer.apply(VoiceMixerConfig())
        val playing = launch { player.play(TTSResponse(pcm, AudioFormat.PCM, sampleRate = sampleRate)) }
        try {
            withTimeout(15_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            delay(400)
            val before = player.playbackState.value.positionMs
            VoiceMixer.apply(VoiceMixerConfig(enabled = true, mix = VoiceMix(1.15f, -3f, .5f,
                space = VoiceSpace.HALL, distance = .3f, reverb = .7f, filter = VoiceFilter.TELEPHONE)))
            player.setOutputMuted(true)
            delay(650)
            val after = player.playbackState.value
            assertEquals(PlaybackStatus.Playing, after.status)
            assertTrue("Effects must not restart or freeze playback", after.positionMs > before)
            VoiceMixer.apply(VoiceMixerConfig())
            player.setOutputMuted(false)
            withTimeout(15_000) { playing.join() }
            assertEquals(PlaybackStatus.Ended, player.playbackState.value.status)
        } finally {
            playing.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            VoiceMixer.apply(VoiceMixerConfig())
        }
    }

    @Test fun savedGlobalEffectsReachFileBackedVoiceNotesAndEditsDoNotRestartThem() = runBlocking {
        store()
        val sampleRate = 16000
        val pcm = ByteArray(sampleRate * 2 * 5)
        for (i in 0 until pcm.size / 2) {
            val value = (kotlin.math.sin(i * 2 * Math.PI * 440 / sampleRate) * 40).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        val file = File.createTempFile("mixer-file-fixture-", ".wav", compose.activity.cacheDir)
        file.writeBytes(pcm16MonoWav(pcm, sampleRate))
        val saved = VoiceMixerConfig(enabled = true, mix = VoiceMix(
            space = VoiceSpace.SMALL_ROOM, distance = .2f, filter = VoiceFilter.RADIO))
        VoiceMixer.apply(Json.decodeFromString(Json.encodeToString(VoiceMixerConfig.serializer(), saved)))
        val player = withContext(Dispatchers.Main) { AudioPlayer(compose.activity).also { it.setOutputMuted(true) } }
        var starts = 0
        val job = launch { player.playFile(file) { starts++ } }
        try {
            withTimeout(15_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            delay(300)
            val before = player.playbackState.value.positionMs
            VoiceMixer.apply(saved.copy(mix = saved.mix.copy(speed = 1.25f, space = VoiceSpace.TUNNEL,
                distance = .6f, filter = VoiceFilter.ROBOT)))
            delay(600)
            assertEquals(PlaybackStatus.Playing, player.playbackState.value.status)
            assertTrue(player.playbackState.value.positionMs > before)
            withTimeout(15_000) { job.join() }
            assertEquals(1, starts)
            assertEquals(PlaybackStatus.Ended, player.playbackState.value.status)
        } finally {
            job.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            file.delete()
            VoiceMixer.apply(VoiceMixerConfig())
        }
    }

    private fun configuredStore(url: String): SettingsStore = store().also { store ->
        val provider = TTSProviderSetting.OpenAI(name = "隔离试听服务", baseUrl = url,
            apiKey = "synthetic-test-only", model = "fixture-tts", voice = "fixture-voice", strictEndpoint = true)
        store.settingsFlow.value = store.settingsFlow.value.copy(ttsProviders = listOf(provider), selectedTTSProviderId = provider.id)
    }

    private fun waitForPreviewText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun typedPreviewUsesConfiguredHttpTtsAndEditsWaitForTheNextPreview() {
        PrivateRoomLoopbackServer().use { server ->
            val sampleRate = 16000
            val pcm = ByteArray(sampleRate * 2 * 12)
            for (i in 0 until pcm.size / 2) {
                val value = (kotlin.math.sin(i * 2 * Math.PI * 220 / sampleRate) * 30).toInt()
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            // Real HTTP provider and Media3 decoder, but only loopback synthetic audio, never user data.
            repeat(2) { server.enqueue(PrivateRoomLoopbackResponse().setBody(pcm16MonoWav(pcm, sampleRate))
                .addHeader("Content-Type", "audio/wav")) }
            val store = configuredStore(server.url("/v1"))
            store.settingsFlow.value = store.settingsFlow.value.copy(voiceMixer = VoiceMixerConfig(enabled = true, mix = VoiceMix(.95f, -2f, .3f)))
            compose.setContent { MaterialTheme { OrbisVoiceMixer({}, store = store) } }
            compose.onNodeWithTag("voice-preview-text").performScrollTo().performTextReplacement("这是直接生成的试听，不是已有音频。")
            compose.onNodeWithTag("voice-preview-start").performScrollTo().performClick()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/audio/speech", request.path)
            val json = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("这是直接生成的试听，不是已有音频。", json.getValue("input").jsonPrimitive.content)
            assertEquals("fixture-tts", json.getValue("model").jsonPrimitive.content)
            assertEquals("fixture-voice", json.getValue("voice").jsonPrimitive.content)
            waitForPreviewText("停止试听")
            Thread.sleep(700) // Let the real decoder advance, not just the pre-play state change.
            compose.onNodeWithText("停止试听").assertExists()
            compose.onNodeWithTag("mixer-音高").performScrollTo().performTouchInput { click(centerRight) }
            assertEquals(1, server.requestCount)
            assertFalse(OrbisVoiceCallRuntime.get(compose.activity).callState.value.isActive)
            compose.onNodeWithTag("voice-preview-start").performScrollTo().performClick()
            waitForPreviewText("▷ 请求 TTS 试听")
            compose.onNodeWithTag("voice-preview-start").performClick()
            server.takeRequest()
            waitForPreviewText("停止试听")
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag("voice-preview-start").performClick()
            waitForPreviewText("▷ 请求 TTS 试听")
        }
    }

    @Test fun cancelPendingPreviewDiscardsLateAudioAndKeepsThePageUsable() {
        val store = configuredStore("http://127.0.0.1:1/v1")
        val response = CompletableDeferred<TTSResponse>()
        var requests = 0
        compose.setContent { MaterialTheme { OrbisVoiceMixer({}, store = store, previewSynthesize = { _, _ ->
            requests++
            withContext(NonCancellable) { response.await() }
        }) } }
        try {
            compose.onNodeWithTag("voice-preview-start").performScrollTo().performClick()
            waitForPreviewText("正在请求 TTS · 取消")
            compose.onNodeWithTag("voice-preview-start").performClick()
            waitForPreviewText("▷ 请求 TTS 试听")
            response.complete(TTSResponse(ByteArray(16000 * 2), AudioFormat.PCM, sampleRate = 16000))
            compose.waitForIdle()
            compose.onNodeWithText("停止试听").assertDoesNotExist()
            compose.onNodeWithTag("voice-preview-text").assertIsEnabled()
            compose.onNodeWithTag("voice-preview-start").assertIsEnabled()
            assertEquals(1, requests)
            assertFalse(OrbisVoiceCallRuntime.get(compose.activity).callState.value.isActive)
        } finally { response.completeExceptionally(CancellationException("fixture finished")) }
    }

    @Test fun failedHttpPreviewShowsSafeRetryInsteadOfRawProviderErrors() {
        PrivateRoomLoopbackServer().use { server ->
            server.enqueue(PrivateRoomLoopbackResponse().setResponseCode(503).setBody("sensitive-fixture-response"))
            val store = configuredStore(server.url("/v1"))
            compose.setContent { MaterialTheme { OrbisVoiceMixer({}, store = store) } }
            compose.onNodeWithTag("voice-preview-start").performScrollTo().performClick()
            server.takeRequest()
            waitForPreviewText("本次 TTS 合成或播放未完成，请检查当前语音服务后重试。聊天和通话未被修改。")
            compose.onNodeWithText("sensitive-fixture-response", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("voice-preview-start").assertIsEnabled()
            assertEquals(1, server.requestCount)
            assertFalse(OrbisVoiceCallRuntime.get(compose.activity).callState.value.isActive)
        }
    }

    @Test fun backgroundStopsPendingPreviewAndResumeDoesNotPlayTheLateResponse() {
        val store = configuredStore("http://127.0.0.1:1/v1")
        val response = CompletableDeferred<TTSResponse>()
        var requests = 0
        compose.setContent { MaterialTheme { OrbisVoiceMixer({}, store = store, previewSynthesize = { _, _ ->
            requests++
            withContext(NonCancellable) { response.await() }
        }) } }
        try {
            compose.onNodeWithTag("voice-preview-start").performScrollTo().performClick()
            waitForPreviewText("正在请求 TTS · 取消")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitForPreviewText("▷ 请求 TTS 试听")
            response.complete(TTSResponse(ByteArray(16000 * 2), AudioFormat.PCM, sampleRate = 16000))
            compose.waitForIdle()
            compose.onNodeWithText("停止试听").assertDoesNotExist()
            compose.onNodeWithTag("voice-preview-start").assertIsEnabled()
            assertEquals(1, requests)
        } finally { response.completeExceptionally(CancellationException("fixture finished")) }
    }
}
