package me.rerere.rikkahub.ui.components.ui.permission

import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Activity only; no real camera, permission prompt, chat, or settings writes. */
@RunWith(AndroidJUnit4::class)
class PermissionHostDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launches = 0
        var lastCode = 0
        var lastInput: Any? = null
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            launches++
            lastCode = code
            lastInput = input
        }
    }

    @Test fun activityAndNestedThemeContextsOpenWithoutRequestingCamera() {
        val depth = mutableIntStateOf(0)
        val registry = Registry()
        val activity = compose.activity
        val contexts = listOf<Context>(activity, ContextThemeWrapper(activity, android.R.style.Theme_Material),
            ContextWrapper(ContextThemeWrapper(activity, android.R.style.Theme_Material)))
        lateinit var state: PermissionState
        compose.setContent {
            CompositionLocalProvider(LocalContext provides contexts[depth.intValue],
                LocalActivityResultRegistryOwner provides registry) {
                state = rememberPermissionState(PermissionCamera)
                Text("Chat is open")
            }
        }
        for (index in contexts.indices) {
            compose.runOnIdle { depth.intValue = index }
            compose.onNodeWithText("Chat is open").assertExists()
            compose.runOnIdle {
                state.refreshPermissionStates()
                assertTrue(state.permissionStates.containsKey(PermissionCamera.permission))
                assertEquals(0, registry.launches)
            }
        }
    }

    @Test fun dialogWrappedContextCanRequestCameraAndReceiveResult() {
        val registry = Registry()
        val context = ContextThemeWrapper(compose.activity, android.R.style.Theme_Material)
        lateinit var state: PermissionState
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalActivityResultRegistryOwner provides registry) {
                Dialog(onDismissRequest = {}) {
                    MaterialTheme {
                        state = rememberPermissionState(PermissionCamera)
                        Button(onClick = { state.requestPermissions() }) { Text("Camera") }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertFalse(state.allRequiredPermissionsGranted)
            assertEquals(0, registry.launches)
        }
        compose.onNodeWithText("Camera").performClick()
        compose.runOnIdle {
            assertEquals(1, registry.launches)
            assertEquals(PermissionCamera.permission, registry.lastInput)
            assertTrue(registry.dispatchResult(registry.lastCode, true))
        }
        compose.runOnIdle { assertTrue(state.allRequiredPermissionsGranted) }
    }
}
