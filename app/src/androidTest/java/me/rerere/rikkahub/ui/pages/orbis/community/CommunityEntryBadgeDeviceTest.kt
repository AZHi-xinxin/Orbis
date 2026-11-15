package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommunityEntryBadgeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun fullBadgeStaysInsideHeaderSlotForCountsAndLargeFonts() {
        var unread by mutableIntStateOf(0)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) {
                MaterialTheme { OrbisVisualTheme {
                    Box(Modifier.background(Color(0xFF9BB7D7)).padding(16.dp).testTag("badge-canvas")) {
                        CommunityEntryButton(unread,{},Modifier.testTag("community-entry"))
                    }
                } }
            }
        }
        for(scale in listOf(1f,1.6f,2f)) for(count in listOf(0,1,35,99,100,9999)) {
            compose.runOnIdle {fontScale=scale;unread=count}
            val entry=compose.onNodeWithTag("community-entry").fetchSemanticsNode().boundsInRoot
            if(count==0) compose.onNodeWithTag("community-unread-badge").assertDoesNotExist()
            else {
                compose.onNodeWithText(if(count>99) "99+" else count.toString()).assertIsDisplayed()
                val badge=compose.onNodeWithTag("community-unread-badge").fetchSemanticsNode().boundsInRoot
                val icon=compose.onNodeWithTag("community-entry-icon").fetchSemanticsNode().boundsInRoot
                assertTrue("Badge $count at font $scale must fit horizontally",badge.left>=entry.left && badge.right<=entry.right)
                assertTrue("Badge $count at font $scale must fit vertically",badge.top>=entry.top && badge.bottom<=entry.bottom)
                assertTrue("Badge $count at font $scale must not cover the icon",badge.bottom<=icon.top)
                compose.onNodeWithContentDescription("打开社区，$count 条未读群消息").assertHasClickAction()
                if(count==35 || count==100) {
                    val bitmap=compose.onNodeWithTag("badge-canvas").captureToImage().asAndroidBitmap()
                    val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"community-badge-$scale-$count.png")
                    file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
            }
        }
    }

    @Test fun badgeAreaAndAccessibleActionBothOpenCommunityOnce() {
        var clicks=0
        compose.setContent {MaterialTheme {OrbisVisualTheme {CommunityEntryButton(235,{clicks++})}}}
        compose.onNodeWithTag("community-unread-badge").performTouchInput {click()}
        compose.runOnIdle {assertEquals(1,clicks)}
        compose.onNodeWithContentDescription("打开社区，235 条未读群消息").performClick()
        compose.runOnIdle {assertEquals(2,clicks)}
    }
}
