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

/** Exercises every downloaded appearance through the real destination, not just asset decoding. */
class PetAllCareScenesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Storage: ActivityStorage { var text:String?=null
        override fun read()=text
        override fun write(text:String) { this.text=text }
    }
    @Test fun everyAppearanceSleepsAndWakesForFoodAndWaterInAllThreePalettes() {
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        val ctx=inst.targetContext;assertEquals(Application::class.java,ctx.applicationContext.javaClass)
        val catalog=PetArtDownloads.catalog(ctx)
        assertEquals(40,catalog.packages.size)
        val cases=catalog.packages.keys.filter {it!="cat-black-v1"}.sorted().map {id ->
            assertTrue("$id must have independent care pictures",catalog.packages.getValue(id).files.containsKey("care.webp"))
            val root=PetArtDownloads.store(ctx).install(id,File(ctx.filesDir,"pet-art-fixtures/$id.zip"))
            val repo=PetRepository(Storage());repo.adopt(id,id)
            Triple(id,repo,root)
        }
        var current by mutableStateOf(cases.first())
        compose.setContent { MaterialTheme { key(current.first) {
            val repo=current.second;val root=current.third
            val state by repo.state.collectAsState()
            CompanionWindow({}) { PetInteractiveHome(state,false,root) {id,color -> repo.act(id,UUID.randomUUID().toString(),color)} }
        } } }
        fun frame(row:Int,col:Int) {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("pet-care-frame-$row-$col",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pet-care-frame-$row-$col",useUnmergedTree=true).assertIsDisplayed()
        }
        for(case in cases) {
            compose.runOnIdle {current=case}
            for((col,palette) in listOf("sage","rose","sky").withIndex()) {
                compose.runOnIdle {case.second.act("decorate",UUID.randomUUID().toString(),palette)}
                compose.onNodeWithTag("pet-caption-sleep",useUnmergedTree=true).performClick()
                frame(0,col)
                compose.mainClock.advanceTimeBy(800);compose.waitForIdle()
                compose.onAllNodesWithTag("pet-original-awake").assertCountEquals(0)
                if(col==0) {
                    val bitmap=compose.onNodeWithTag("pet-room-scene").captureToImage().asAndroidBitmap()
                    File(ctx.filesDir,"all-care-${case.first}-sleep.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                compose.onNodeWithText("换水").performClick();frame(2,col)
                compose.onNodeWithText("添粮").performClick();frame(1,col)
                compose.onNodeWithText("摸摸").performClick()
                compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
                compose.onNodeWithTag("pet-original-awake").assertIsDisplayed()
                compose.mainClock.advanceTimeBy(5000);compose.waitForIdle()
                compose.onAllNodesWithTag("pet-rest-picture").assertCountEquals(0)
            }
            assertEquals(3,case.second.snapshot().events.count {it.action=="water"})
            assertEquals(3,case.second.snapshot().events.count {it.action=="feed"})
        }
    }
}
