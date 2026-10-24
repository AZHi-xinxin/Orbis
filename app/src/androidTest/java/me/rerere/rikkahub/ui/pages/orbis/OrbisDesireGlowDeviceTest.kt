package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrbisDesireGlowDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    @Test fun countBoundaryAndOverlayNeverInterceptsControls() {
        var count by mutableIntStateOf(9)
        var clicks=0
        compose.setContent { MaterialTheme {Box(Modifier.size(300.dp)) {
            Button(onClick={clicks++}) {Text("星图操作")}
            OrbisDesireGlow(count=count,animate=false,modifier=Modifier.fillMaxSize())
        }} }
        compose.onNodeWithText("想要的有很多…").assertDoesNotExist()
        compose.runOnIdle{count=10}
        compose.onNodeWithText("想要的有很多…").assertDoesNotExist()
        compose.runOnIdle{count=11}
        compose.onNodeWithText("想要的有很多…").assertIsDisplayed()
        compose.onNodeWithText("星图操作").performTouchInput{click()}
        compose.runOnIdle{assertEquals(1,clicks);count=0}
        compose.onNodeWithText("想要的有很多…").assertDoesNotExist()
    }
}
