package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.orbis.spaces.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Plain Application + fresh cache fixture only. No Koin, settings, real conversations or private shelves. */
@RunWith(AndroidJUnit4::class)
class CompanionSpacesUiDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var fixtureRoot: File
    private val owner = "1c66025d-e84f-4c0e-9f08-b2ca044be81a"
    private val other = "dcc605c7-b98a-440b-bc57-a7c56f4a7908"
    private fun repo(id: String = owner) = OrbisCompanionSpacesStore(File(fixtureRoot, "orbis-companion-spaces/$id"))
    private val isolated = object : ExternalResource() {
        override fun before() {
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
            fixtureRoot = File(instrumentation.targetContext.cacheDir, "companion-ui-fixture-${UUID.randomUUID()}")
            check(fixtureRoot.mkdirs())
        }
        override fun after() {
            if (::fixtureRoot.isInitialized) {
                check(fixtureRoot.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile)
                check(fixtureRoot.name.startsWith("companion-ui-fixture-"))
                fixtureRoot.deleteRecursively()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    @Composable
    private fun SyntheticPreviewRoot(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("space-preview-root")) {
            content()
        }
    }

    /** Capture the drawn synthetic Compose node, not an Activity/window first frame. */
    private fun capturePreview(name: String, dialog: Boolean = false) {
        require(name.matches(Regex("space-[a-z-]+")))
        compose.waitForIdle()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("space-preview-root")
        val bitmap = node.assertIsDisplayed().captureToImage().asAndroidBitmap()
        val context = instrumentation.targetContext
        val base = (context.getExternalFilesDir(null) ?: context.cacheDir).canonicalFile
        val directory = File(base, "1005-previews")
        check(directory.canonicalFile.parentFile == base)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name.png")
        check(target.canonicalFile == target.absoluteFile)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(target.length() > 0L)
            println("ORBIS_SYNTHETIC_PREVIEW=${target.absolutePath}")
        }
        finally { bitmap.recycle() }
    }

    private fun syntheticPhoto(store: OrbisCompanionSpacesStore, seed: Int, width: Int = 32, height: Int = 32): SpaceMedia {
        val colors = intArrayOf(0xff6d89a4.toInt(), 0xffad8d9a.toInt(), 0xff89a595.toInt(), 0xffc9ad7f.toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / 32f, height / 32f)
        canvas.drawColor(colors[seed % colors.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfffff3d7.toInt() }
        canvas.drawCircle(22f, 9f, 4f, paint)
        paint.color = 0xff526070.toInt(); canvas.drawRect(0f, 24f, 32f, 32f, paint)
        paint.color = 0xffd0d9d8.toInt(); canvas.drawCircle(8f, 25f, 9f, paint)
        return try {
            val output = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            store.addMedia(output.toByteArray(), "image/png")
        } finally { bitmap.recycle() }
    }

    @Test fun visualPreviewSecretCards() {
        val store = repo()
        listOf("letter", "kraft", "floral", "midnight").forEachIndexed { index, paper ->
            val story = store.saveHumanStory(listOf("星海邮差", "雨天书店", "花园来信", "夜航日记")[index], "假如你们相遇在另一个世界。今晚，从一封没有署名的信开始。", paper)
            store.writeStoryBody(story.id, "星星落在旧窗台上，像一封寄错季节的信。\n他把信轻轻推到你面前：这一次，让故事慢一点。", story.revision)
        }
        compose.setContent { MaterialTheme { SyntheticPreviewRoot { SecretBaseContent(store.snapshot(), false, { _, _ -> }, {}) } } }
        compose.onNodeWithText("星海邮差").assertIsDisplayed()
        capturePreview("space-secret")
    }

    @Test fun visualPreviewSecretFullScreenEditor() {
        val store = repo()
        val story = store.saveHumanStory("星海邮差", "假如你们相遇在另一个世界。\n\n你是一座海边小镇的邮差，每天傍晚会收到一封寄自星空的信。今晚，那位从未露面的写信人终于敲响了门。\n\n请以一封温柔的回信，写下这段主线之外的相遇。", "letter")
        compose.setContent { MaterialTheme { SpaceStoryEditor(StoryEditor(story, "prompt"), false, {}, { _, _, _, _ -> }) } }
        compose.onNodeWithTag("space-story-text").assertIsDisplayed()
        capturePreview("space-editor", dialog = true)
    }

    @Test fun visualPreviewSharedFeedWithCoverImagesLikesAndReplies() {
        val store = repo(); val media = (0..3).map { syntheticPhoto(store, it) }
        store.setCover(media[0].id)
        val post = store.publishPost("ai", "把今天收进我们的小宇宙。\n日落、微风，还有一起走过的路。", media.map { it.id })
        store.likePost(post.id, "human", true)
        val comment = store.commentPost(post.id, "human", "这些小小的日常，都想留住。")
        store.commentPost(post.id, "ai", "那就把它们放在这里，慢慢看。", comment.id)
        compose.setContent { MaterialTheme { SyntheticPreviewRoot {
            SharedSpaceContent(store.snapshot(), store, "合成人类", Avatar.Emoji("🌼"), "合成助手", Avatar.Emoji("⭐"), false,
                onCover = {}, onLike = {}, onComment = { _, _ -> }, onDelete = {}, onImage = {})
        } } }
        compose.onNodeWithText("把今天收进我们的小宇宙。\n日落、微风，还有一起走过的路。").assertIsDisplayed()
        capturePreview("space-social")
        compose.onNodeWithText("合成助手 回复 合成人类：那就把它们放在这里，慢慢看。").performScrollTo()
        capturePreview("space-social-comments")
    }

    @Test fun visualPreviewPhotoWallFourSyntheticFronts() {
        val store = repo()
        val notes = listOf("一起收藏的落日", "晚风留在照片里", "花园里的好天气", "下一次还来这里")
        (0..3).forEach { store.addPhoto(syntheticPhoto(store, it).id, notes[it]) }
        store.setWallStyle("polaroid")
        compose.setContent { MaterialTheme { SyntheticPreviewRoot {
            PhotoWallContent(store.snapshot(), store, false, onStyle = {}, onAdd = {}, onNote = { _, _ -> }, onMove = { _, _ -> }, onDelete = {}, onImage = {})
        } } }
        compose.onNodeWithText("一起收藏的落日").assertIsDisplayed()
        capturePreview("space-photos")
        repeat(3) { compose.onNodeWithText("下一张 ›").performScrollTo().performClick(); compose.waitForIdle() }
        compose.onNodeWithText("下一次还来这里").assertIsDisplayed()
        capturePreview("space-photos-bottom")
    }

    @Test fun photoDisplayModesAreDistinctAndEditControlsStayInEditor() {
        val store = repo()
        listOf(96 to 32, 32 to 96, 48 to 48, 128 to 24, 24 to 128).forEachIndexed { index, (width, height) ->
            store.addPhoto(syntheticPhoto(store, index, width, height).id, "合成照片 ${index + 1}")
        }
        val before = store.snapshot().photos
        val state = mutableStateOf(store.snapshot())
        compose.setContent { MaterialTheme { SyntheticPreviewRoot {
            PhotoWallContent(state.value, store, false,
                onStyle = { store.setWallStyle(it); state.value = store.snapshot() }, onAdd = {},
                onNote = { _, _ -> }, onMove = { _, _ -> }, onDelete = {}, onImage = {})
        } } }
        listOf("拍立得" to "polaroid", "相册" to "album", "悬挂" to "hanging", "拼贴" to "collage").forEach { (title, style) ->
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithTag("space-photo-display-$style").assertIsDisplayed()
            compose.onNodeWithText("← 前移").assertDoesNotExist()
            compose.onNodeWithText("删除").assertDoesNotExist()
            capturePreview("space-photos-$style")
        }
        compose.onNodeWithTag("space-photo-edit-toggle").performClick()
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNodeWithTag("space-photo-display-collage").assertDoesNotExist()
        compose.onNodeWithTag("space-photo-edit-toggle").performClick()
        compose.onNodeWithTag("space-photo-display-collage").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, store.snapshot().photos) }
    }

    @Test fun emptyAlbumOffersAnAddPlaceWithoutWritingStorage() {
        val store = repo()
        var adds = 0
        compose.setContent { MaterialTheme {
            OrbisPhotoWallDisplay(emptyList(), "album", store, true, { adds++ }, {})
        } }
        compose.onNodeWithTag("space-photo-display-album").assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.snapshot().photos.isEmpty()); assertEquals(0, adds) }
    }

    @Test fun emptySecretBaseOffersHumanPremiseWithoutCreatingStorage() {
        compose.setContent { MaterialTheme { SecretBaseContent(repo().snapshot(), false, { _, _ -> }, {}) } }
        compose.onNodeWithText("写一封番外邀请").assertIsDisplayed()
        assertFalse(File(fixtureRoot, "orbis-companion-spaces").exists())
    }

    @Test fun fullScreenPremiseEditorSavesHumanTextAndPaperWithoutAiCall() {
        var saved: SpaceStory? = null
        compose.setContent { MaterialTheme {
            SpaceStoryEditor(StoryEditor(null, "prompt"), false, {}, { title, prompt, _, paper ->
                saved = repo().saveHumanStory(title, prompt, paper)
            })
        } }
        compose.onNodeWithTag("space-story-title").performTextInput("合成番外")
        compose.onNodeWithTag("space-story-text").performTextInput("在合成的星空下，写一个新的故事。")
        compose.onNodeWithText("牛皮纸").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertNotNull(saved); assertEquals("kraft", saved!!.paper)
            assertEquals("在合成的星空下，写一个新的故事。", repo().snapshot().stories.single().prompt)
            assertEquals("", repo().snapshot().stories.single().body)
            assertTrue(repo(other).snapshot().stories.isEmpty())
        }
    }

    @Test fun sharedFeedLikeAndCommentAreVisibleAndPersistUnderCorrectActors() {
        val store = repo(); val post = store.publishPost("ai", "这是用于验收的合成动态。")
        val state = mutableStateOf(store.snapshot())
        compose.setContent { MaterialTheme {
            SharedSpaceContent(state.value, store, "我", Avatar.Emoji("🌼"), "合成助手", Avatar.Emoji("⭐"), false,
                onCover = {}, onLike = { item -> store.likePost(item.id, "human", "human" !in item.likes); state.value = store.snapshot() },
                onComment = { item, _ -> store.commentPost(item.id, "human", "合成评论"); state.value = store.snapshot() }, onDelete = {}, onImage = {})
        } }
        compose.onNodeWithText("这是用于验收的合成动态。").assertIsDisplayed()
        compose.onNodeWithText("♡").performClick()
        compose.onNodeWithText("评论").performClick()
        compose.runOnIdle {
            val saved = store.snapshot().posts.single { it.id == post.id }
            assertEquals(setOf("human"), saved.likes)
            assertEquals("human", saved.comments.single().actor)
            assertEquals("合成评论", saved.comments.single().text)
            assertTrue(repo(other).snapshot().posts.isEmpty())
        }
        compose.onNodeWithText("我：合成评论").assertIsDisplayed()
    }

    @Test fun importedSyntheticPhotoFlipsAndSavesBackNote() {
        val store = repo()
        val original = File(fixtureRoot, "synthetic.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(96, 130, 179))
        original.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val media = runBlocking { importCompanionSpaceImage(instrumentation.targetContext, store, Uri.fromFile(original)) }
        val photo = store.addPhoto(media.id)
        assertTrue(original.delete())
        val state = mutableStateOf(store.snapshot())
        compose.setContent { MaterialTheme {
            PhotoWallContent(state.value, store, false, onStyle = { store.setWallStyle(it); state.value = store.snapshot() }, onAdd = {},
                onNote = { item, text -> store.setPhotoNote(item.id, text, item.revision); state.value = store.snapshot() },
                onMove = { item, position -> store.movePhoto(item.id, position); state.value = store.snapshot() }, onDelete = {}, onImage = {})
        } }
        compose.onNodeWithTag("space-photo-edit-toggle").performClick()
        compose.onNodeWithContentDescription("照片，点击翻到背面").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("space-photo-note-${photo.id}").performTextInput("合成照片背面的备注")
        compose.onNodeWithText("保存备注").performClick()
        compose.runOnIdle {
            assertEquals("合成照片背面的备注", repo().snapshot().photos.single().note)
            assertEquals("image/jpeg", store.imageBytes(media.id).first)
            assertTrue(store.mediaFile(media.id).exists())
            assertTrue(repo(other).snapshot().photos.isEmpty())
        }
    }
}
