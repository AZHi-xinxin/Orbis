package me.rerere.rikkahub.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic activity only; no app settings, real wallpaper, accounts or services. */
@RunWith(AndroidJUnit4::class)
class TransparentStatusBarAppearanceDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Suppress("DEPRECATION")
    @Test fun transparentAppearanceChangesOnlyContrastNotStatusVisibilityOrInsets() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { Box {} }
        compose.waitForIdle()
        compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            val beforeInsets = requireNotNull(ViewCompat.getRootWindowInsets(view))
            val beforeFlags = window.attributes.flags
            val beforeCutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else null
            val navigationBarColor = window.navigationBarColor
            val controller = WindowCompat.getInsetsController(window, view)
            val beforeNavigationIcons = controller.isAppearanceLightNavigationBars
            for (darkIcons in listOf(true, false, true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = true
                applyTransparentStatusBarAppearance(window, view, darkIcons)
                assertEquals(Color.TRANSPARENT, window.statusBarColor)
                assertEquals(darkIcons, controller.isAppearanceLightStatusBars)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assertFalse(window.isStatusBarContrastEnforced)
                assertEquals(beforeFlags, window.attributes.flags)
                if (Build.VERSION.SDK_INT >= 28) assertEquals(beforeCutoutMode, window.attributes.layoutInDisplayCutoutMode)
                assertEquals(navigationBarColor, window.navigationBarColor)
                assertEquals(beforeNavigationIcons, controller.isAppearanceLightNavigationBars)
                val afterInsets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                assertEquals(beforeInsets.getInsets(WindowInsetsCompat.Type.statusBars()),
                    afterInsets.getInsets(WindowInsetsCompat.Type.statusBars()))
                assertEquals(beforeInsets.isVisible(WindowInsetsCompat.Type.statusBars()),
                    afterInsets.isVisible(WindowInsetsCompat.Type.statusBars()))
            }
        }
    }
}
