package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.activities.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PetOriginalRoomDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private class Storage:ActivityStorage {var text:String?=null;override fun read()=text;override fun write(text:String){this.text=text}}
    private fun setup():Pair<PetRepository,File> {
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        val ctx=inst.targetContext;assertEquals(Application::class.java,ctx.applicationContext.javaClass)
        val fixture=File(ctx.filesDir,"pet-art-fixtures/cat-black-v1.zip")
        val root=PetArtDownloads.store(ctx).install("cat-black-v1",fixture)
        val repo=PetRepository(Storage());repo.adopt("测试墨","cat-black-v1")
        return repo to root
    }
    private fun capture(name:String) {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("pet-room-scene").captureToImage().asAndroidBitmap().let {bitmap ->
            File(ctx.filesDir,"pet-room-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun sleepingIsOneIndependentPictureAndOtherCareReturnsToTheRoom() {
        val (repo,root)=setup()
        compose.setContent {MaterialTheme {val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,root) {id,color->repo.act(id,UUID.randomUUID().toString(),color)}}}}
        compose.onNodeWithTag("pet-original-awake").assertIsDisplayed()
        compose.onNodeWithText("小床").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("pet-rest-picture").fetchSemanticsNodes().isNotEmpty()}
        // Let the independent-scene crossfade finish before checking the moving actor is gone.
        compose.mainClock.advanceTimeBy(3200);compose.waitForIdle()
        compose.onNodeWithTag("pet-rest-picture").assertIsDisplayed()
        compose.onAllNodesWithTag("pet-original-awake").assertCountEquals(0)
        capture("sleep-sage")
        compose.onNodeWithText("换水").performClick()
        compose.mainClock.advanceTimeBy(1000);compose.waitForIdle()
        compose.onNodeWithTag("pet-original-awake").assertIsDisplayed()
        compose.onAllNodesWithTag("pet-rest-picture").assertCountEquals(0)
        assertEquals(listOf("sleep","water"),repo.snapshot().events.map {it.action})
        capture("water")
        compose.mainClock.advanceTimeBy(5000)
        compose.onAllNodesWithTag("pet-rest-picture").assertCountEquals(0)
        compose.onNodeWithText("添粮").performClick()
        compose.mainClock.advanceTimeBy(1000);capture("food")
        assertEquals(3,repo.snapshot().events.size)
    }
    @Test fun originalRoomChangesPaletteAndLightweightModeStillUsesItsOwnGeometry() {
        val (repo,root)=setup()
        compose.setContent {MaterialTheme {val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,root,onArtwork={repo.configureArtwork(it)}) {id,color->repo.act(id,UUID.randomUUID().toString(),color)}}}}
        compose.onNodeWithText("布置").performClick()
        compose.onNodeWithText("暖粉").performClick()
        compose.runOnIdle {assertEquals("rose",repo.snapshot().decoration)}
        compose.onNodeWithTag("pet-room-palette-rose",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("雾蓝").performClick()
        compose.runOnIdle {assertEquals("sky",repo.snapshot().decoration)}
        compose.onNodeWithTag("pet-room-palette-sky",useUnmergedTree=true).assertExists()
        compose.runOnIdle {repo.configureArtwork(false)}
        compose.onAllNodesWithTag("pet-original-awake").assertCountEquals(0)
        assertFalse(repo.snapshot().originalArtwork)
        assertEquals("cat-black-v1",repo.snapshot().pet!!.appearance)
    }
    @Test fun restingPictureAlsoMatchesChosenRoomPalette() {
        val (repo,root)=setup()
        repo.act("decorate","pink","rose");repo.act("sleep","rest")
        compose.setContent {MaterialTheme {val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,root) {id,color->repo.act(id,UUID.randomUUID().toString(),color)}}}}
        compose.onNodeWithTag("pet-rest-picture").assertIsDisplayed();capture("sleep-rose")
        compose.runOnIdle {repo.act("decorate","blue","sky")}
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("pet-rest-picture").assertIsDisplayed();capture("sleep-sky")
    }
}
