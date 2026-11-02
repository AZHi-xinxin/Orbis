package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.gallery.GalleryRepository
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic shelves only, using the same persistence calls as the native pin buttons. */
@RunWith(AndroidJUnit4::class)
class LibraryPinUiDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private val isolated = object : ExternalResource() {
        override fun before() {
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
            root = File(instrumentation.targetContext.cacheDir.canonicalFile, "pin-fixture-${UUID.randomUUID()}")
            check(root.mkdirs())
        }
        override fun after() {
            if (::root.isInitialized) {
                check(root.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile)
                check(root.name.startsWith("pin-fixture-"))
                root.deleteRecursively()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("pin-preview").captureToImage().asAndroidBitmap()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "library-pins")
        check(directory.isDirectory || directory.mkdirs())
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun galleryPinDoesNotOpenArtworkAndCanBeUndoneAfterReload() {
        val repo = GalleryRepository(File(root, "orbis-gallery/owner"))
        val item = repo.save("日常功能小工具", "html", "<p>一起保存的作品</p>")
        val state = mutableStateOf(item)
        val busy = mutableStateOf(false)
        var opened = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.width(180.dp).testTag("pin-preview")) {
                GalleryTile(state.value, busy.value, onClick = { opened++ }, onPin = {
                    repo.setPinned(item.id, !state.value.pinned)
                    state.value = GalleryRepository(File(root, "orbis-gallery/owner")).snapshot().items.single()
                })
            }
        } }
        compose.onNodeWithTag("gallery-pin-${item.id}").performClick()
        compose.onNodeWithText("已置顶").assertIsDisplayed()
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opened); assertTrue(repo.snapshot().items.single().pinned) }
        capture("gallery")
        compose.onNodeWithTag("gallery-pin-${item.id}").performClick()
        compose.onNodeWithText("已置顶").assertDoesNotExist()
        compose.runOnIdle { assertFalse(repo.snapshot().items.single().pinned); busy.value = true }
        compose.onNodeWithTag("gallery-pin-${item.id}").assertIsNotEnabled()
    }

    @Test fun secretPinMovesOldStoryToFirstCellAndUnpinRestoresContentOrder() {
        var tick = 100L
        val storeRoot = File(root, "orbis-companion-spaces/1c66025d-e84f-4c0e-9f08-b2ca044be81a")
        val repo = OrbisCompanionSpacesStore(storeRoot, now = { tick++ })
        val old = repo.saveHumanStory("常用设定", "保留这份世界设定", "midnight")
        val recent = repo.saveHumanStory("新故事", "今天的新灵感", "letter")
        val state = mutableStateOf(repo.snapshot())
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().testTag("pin-preview")) {
                SecretBaseContent(state.value, false, { _, _ -> }, {}, onPin = {
                    repo.setStoryPinned(it.id, !it.pinned)
                    state.value = OrbisCompanionSpacesStore(storeRoot).snapshot()
                })
            }
        } }
        fun assertBefore(first: String, second: String) {
            val a = compose.onNodeWithTag("story-$first").fetchSemanticsNode().boundsInRoot
            val b = compose.onNodeWithTag("story-$second").fetchSemanticsNode().boundsInRoot
            assertTrue("First cell should precede second", a.top < b.top || (a.top == b.top && a.left < b.left))
        }
        assertBefore(recent.id, old.id)
        compose.onNodeWithTag("story-pin-${old.id}").performClick()
        compose.onNodeWithText("已置顶 · 取消").assertIsDisplayed()
        assertBefore(old.id, recent.id)
        capture("secret")
        compose.onNodeWithTag("story-pin-${old.id}").performClick()
        compose.onNodeWithText("已置顶 · 取消").assertDoesNotExist()
        assertBefore(recent.id, old.id)
        compose.runOnIdle { assertEquals(old, repo.snapshot().stories.first { it.id == old.id }) }
    }
}
