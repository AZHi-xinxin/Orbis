package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.theme.selectablePresetThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run in both unit-test variants: Orbis identity is independent of debuggability. */
class OrbisBuildVariantContractTest {
    @Test fun orbisIsEnabledInThisBuildVariant() {
        assertTrue(BuildConfig.ORBIS_ENABLED)
    }

    @Test fun defaultThemeChoicesKeepTheOrbisAndDeepSeekViews() {
        assertEquals(listOf("orbis", "deepseek"), selectablePresetThemes().map { it.id })
    }

    @Test fun normalColdStartUsesTheFeatureFlagRatherThanDebuggability() {
        assertTrue(shouldShowOrbisStartup(BuildConfig.ORBIS_ENABLED, true, true))
    }

    @Test fun appIdentityDoesNotReplaceTheUpstreamApplication() {
        assertEquals(if (BuildConfig.DEBUG) "org.orbis.agent.dev" else "org.orbis.agent",
            BuildConfig.APPLICATION_ID)
    }
}
