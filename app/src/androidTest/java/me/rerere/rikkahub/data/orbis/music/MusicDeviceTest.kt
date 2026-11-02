package me.rerere.rikkahub.data.orbis.music

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.music.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic local audio only; no model, private conversations, or physical-phone fixture. */
class MusicDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = MusicRuntime.get(context)
    @Before fun resetPresentation() {
        runtime.library(false)
        runtime.store.appearance { it.copy(mode = "hidden", locked = false, style = "galaxy", x = .5f, y = .05f) }
    }
    @After fun closePresentation() { runtime.library(false) }
    private fun audio(name: String, seconds: Int = 16): MusicImportFile {
        val count = 8000 * seconds; val data = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        data.putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
        repeat(count) { data.putShort(0) }
        val file = File(context.cacheDir, "$name.wav"); file.writeBytes(data.array())
        return MusicImportFile(Uri.fromFile(file), "$name.wav")
    }
    private fun import(name: String, seconds: Int = 16): String = runBlocking {
        MusicImporter(context, runtime.store).import(listOf(audio(name, seconds))) { _, _ -> }.ids.single()
    }
    private fun waitFor(block: () -> Boolean) { compose.waitUntil(15000, block) }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> File(context.getExternalFilesDir(null), "music-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun importDeduplicatesPairsLyricsAndReportsBadFiles() = runBlocking {
        val source = audio("synthetic-import", 17)
        val lyric = File(context.cacheDir, "synthetic-import.lrc").also { it.writeText("[00:01]星河里的第一句\n[00:04]慢慢听一首歌") }
        val bad = File(context.cacheDir, "broken.mp3").also { it.writeText("not audio") }
        val first = MusicImporter(context, runtime.store).import(listOf(source, MusicImportFile(Uri.fromFile(lyric), lyric.name), MusicImportFile(Uri.fromFile(bad), bad.name))) { _, _ -> }
        assertEquals(1, first.ids.size); assertEquals(1, first.failed.size)
        assertTrue(runtime.store.track(first.ids.single()).lyrics.contains("星河"))
        val second = MusicImporter(context, runtime.store).import(listOf(source)) { _, _ -> }
        assertEquals(0, second.added); assertEquals(1, second.duplicate)
    }
    @Test fun playbackSurvivesHiddenPanelAndHumanPauseRejectsStaleAi() {
        val id = import("synthetic-play")
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { MusicEntry(); MusicOverlay("合成伙伴", Modifier.fillMaxSize().padding(top = 60.dp)) } } }
        runBlocking { runtime.command("play", listOf(id)); runtime.store.appearance { it.copy(mode = "expanded") } }
        waitFor { runtime.playback.value.playing }
        val old = runtime.playback.value.revision
        val start = runtime.playback.value.position
        compose.onNodeWithContentDescription("隐藏播放器，继续播放").performClick()
        waitFor { runtime.store.state.value.appearance.mode == "hidden" && runtime.playback.value.position > start + 500 }
        assertTrue(runtime.playback.value.playing)
        runBlocking { runtime.command("pause") }
        waitFor { !runtime.playback.value.playing && runtime.playback.value.revision > old }
        val failure = runBlocking { runCatching { runtime.command("resume", expected = old) }.exceptionOrNull() }
        assertEquals("music_revision_conflict", failure?.message)
        compose.onNodeWithContentDescription("打开星河音乐").performClick()
        waitFor { runtime.store.state.value.appearance.mode == "expanded" }
        screenshot("expanded")
        runBlocking { runtime.command("resume") }
        waitFor { runtime.playback.value.playing }
        val backgroundStart = runtime.playback.value.position
        compose.runOnIdle { compose.activity.moveTaskToBack(true) }
        waitFor { runtime.playback.value.position > backgroundStart + 1000 }
        val notifications = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertTrue(notifications.activeNotifications.any { it.notification.category == android.app.Notification.CATEGORY_TRANSPORT })
        runBlocking { runtime.command("pause") }

    }
    @Test fun toolGateAndPlaylistAttributionUseRealHostIdentity() = runBlocking {
        val id = import("synthetic-tool", 18)
        suspend fun call(enabled: Boolean, action: String, extra: JsonObject = buildJsonObject {}): JsonObject {
            val result = musicTool(context, "synthetic-owner", "星河伙伴") { enabled }.execute(JsonObject(extra + ("action" to JsonPrimitive(action))))
            return Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        }
        assertEquals("music_disabled", call(false, "search")["code"]?.jsonPrimitive?.content)
        val result = call(true, "playlist_create", buildJsonObject { put("name", "给今晚的你"); put("track_ids", JsonArray(listOf(JsonPrimitive(id)))) })
        assertEquals("ai:synthetic-owner", result["playlist"]!!.jsonObject["creatorId"]!!.jsonPrimitive.content)
        assertEquals("music_revision_required", call(true, "pause")["code"]?.jsonPrimitive?.content)
        runtime.command("state")
        delay(100)
        val revision = runtime.playback.value.revision
        val played = call(true, "play", buildJsonObject { put("track_ids", JsonArray(listOf(JsonPrimitive(id)))); put("expected_revision", revision) })
        assertTrue(played["ok"]!!.jsonPrimitive.boolean)
        withTimeout(10000) { while(!runtime.playback.value.playing) delay(50) }
        assertEquals("ai:synthetic-owner", runtime.store.state.value.selectedById)
        val queueState = call(true, "state")
        assertEquals(id, queueState["queue"]!!.jsonArray.single().jsonObject["track_id"]!!.jsonPrimitive.content)
        assertFalse(musicTool(context, "synthetic-owner", "星河伙伴") { true }.needsApproval(buildJsonObject { put("action", "play") }))
        runtime.command("pause")
        Unit
    }
    @Test fun mistStyleAtNarrowWidthAndLargeFontKeepsControls() {
        runtime.store.appearance { it.copy(style = "mist", mode = "expanded", locked = false) }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2.625f, 1.3f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(550.dp)) { MusicOverlay("合成伙伴", Modifier.fillMaxSize()) } }
            }
        }
        compose.onNodeWithContentDescription("缩小播放器").assertIsDisplayed()
        compose.onNodeWithText("我的曲库").assertIsDisplayed()
        screenshot("mist-320-large-font")
        compose.onNodeWithText("我的曲库").performClick()
        compose.waitForIdle(); screenshot("mist-library")
        runtime.library(false)
        runtime.store.appearance { it.copy(style = "galaxy") }
    }
    @Test fun speechDucksOnlyMusicAndHumanPauseSurvivesSpeechEnd() {
        val id = import("synthetic-music-focus", 20)
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val originalVolume = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        runBlocking { runtime.command("play", listOf(id)) }
        waitFor { runtime.playback.value.playing }
        val voice = runBlocking { withContext(Dispatchers.Main) { me.rerere.tts.controller.AudioPlayer(context) } }
        val file = File(audio("synthetic-voice", 5).uri.path!!)
        val voiceJob = runtime.scope.launch { voice.playFile(file) }
        try {
            waitFor { me.rerere.tts.controller.SpeechPlaybackActivity.active.value && runtime.playback.value.volume < .2f }
            runBlocking { runtime.command("pause") }
            voice.release(); voiceJob.cancel()
            waitFor { !me.rerere.tts.controller.SpeechPlaybackActivity.active.value && runtime.playback.value.volume == 1f }
            assertFalse(runtime.playback.value.playing)
            assertEquals(originalVolume, manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        } finally { voice.release(); voiceJob.cancel() }
    }
    @Test fun localQueueAdvancesWithoutModelAndEndsInOrderMode() {
        val one = import("queue-first", 2); val two = import("queue-second", 3)
        runBlocking { runtime.command("mode", repeat = "off"); runtime.command("play", listOf(one, two)) }
        waitFor { runtime.playback.value.trackId == one && runtime.playback.value.playing }
        waitFor { runtime.playback.value.trackId == two && runtime.playback.value.playing }
        waitFor { !runtime.playback.value.playing && runtime.playback.value.position > 2000 }
    }
    @Test fun fourFormsLyricsLockAndLibraryRemainUsable() {
        val underlyingClicks = java.util.concurrent.atomic.AtomicInteger()
        val id = import("星河试音", 19)
        runtime.store.lyrics(id, "[00:00]在星河里，听见彼此\n[00:10]把这一刻轻轻收藏")
        runtime.store.playlist("想放给你听", "夜晚", "ai:synthetic-owner", "合成伙伴", listOf(id), description = "今晚，留一点时间给音乐。")
        runBlocking { runtime.command("play", listOf(id)); runtime.store.appearance { it.copy(mode = "expanded", x = .5f, y = .05f) } }
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().clickable { underlyingClicks.incrementAndGet() }); MusicOverlay("合成伙伴", Modifier.fillMaxSize().padding(8.dp)) } } }
        waitFor { runtime.playback.value.playing }
        compose.onNodeWithContentDescription("缩小播放器").performClick()
        waitFor { runtime.store.state.value.appearance.mode == "mini" }; screenshot("mini")
        runtime.store.appearance { it.copy(mode = "lyrics") }
        compose.onNodeWithText("在星河里，听见彼此").assertExists()
        compose.onNodeWithContentDescription("锁定歌词位置").performClick()
        waitFor { runtime.store.state.value.appearance.locked }; screenshot("lyrics")
        compose.onNodeWithText("在星河里，听见彼此").performTouchInput { click() }
        assertEquals(1, underlyingClicks.get())
        // Locked lyric surface does not intercept chat touches; reopen through the music entry.
        runtime.store.appearance { it.copy(locked = false, mode = "expanded") }
        screenshot("expanded")

        compose.onNodeWithText("我的曲库").performClick()
        compose.onNodeWithText("创建歌单").assertDoesNotExist()
        compose.onNodeWithContentDescription("创建歌单").performClick()
        compose.onNodeWithText("歌单名称").performTextInput("星夜收藏")
        compose.onNodeWithText("保存歌单").performClick()
        waitFor { runtime.store.state.value.playlists.any { it.name == "星夜收藏" } }
        compose.waitForIdle(); screenshot("library")
        compose.onNodeWithText("想放给你听").performScrollTo().performClick()
        compose.onNodeWithText("今晚，留一点时间给音乐。").assertIsDisplayed()
        screenshot("playlist")
        compose.onNodeWithText("加入队列").performScrollTo().performClick()
        waitFor { runtime.playback.value.queue.contains(id) }
        compose.onNodeWithContentDescription("歌单操作").performClick()
        compose.onNodeWithText("编辑歌单").performClick()
        compose.onNodeWithText("歌单名称").assertExists()
        screenshot("playlist-editor")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("全部歌单").assertExists()
        runBlocking { runtime.command("pause") }
    }
}

class MusicRestoreDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun newProcessRestoresQueuePausedAndCanResumeImportedFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = MusicRuntime.get(context)
        assertTrue("previous instrumentation left a real imported queue", runtime.playback.value.queue.isNotEmpty())
        assertFalse(runtime.playback.value.playing)
        assertTrue(runtime.store.audio(runtime.store.track(runtime.playback.value.trackId!!)).isFile)
        runBlocking { runtime.command("resume") }
        compose.waitUntil(15000) { runtime.playback.value.playing }
        runBlocking { runtime.command("pause") }
    }
}
