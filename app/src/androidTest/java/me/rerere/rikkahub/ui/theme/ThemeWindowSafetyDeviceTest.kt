package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic contexts only; no real theme/settings/model initialization or user data. */
@RunWith(AndroidJUnit4::class)
class ThemeWindowSafetyDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun activityAndNestedThemedWrappersResolveTheSameHost() {
        val activity = compose.activity
        assertSame(activity, themeActivityOrNull(activity))
        val themed = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light)
        assertSame(activity, themeActivityOrNull(ContextWrapper(themed)))
    }

    @Test fun applicationAndMissingBaseHaveNoWindowInsteadOfClassCastFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertNull(themeActivityOrNull(context))
        assertNull(themeActivityOrNull(ContextWrapper(context)))
        assertNull(themeActivityOrNull(ContextWrapper(null)))
    }

    @Test fun cyclicWrapperChainTerminatesWithoutTouchingAWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cycle = object : ContextWrapper(context) {
            override fun getBaseContext(): Context = this
        }
        assertNull(themeActivityOrNull(cycle))
    }

    @Test fun malformedExcessiveNestingHasABoundedTraversal() {
        var context: Context = compose.activity
        repeat(70) { context = ContextWrapper(context) }
        assertNull(themeActivityOrNull(context))
    }
}
