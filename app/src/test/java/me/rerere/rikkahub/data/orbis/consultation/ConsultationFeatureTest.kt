package me.rerere.rikkahub.data.orbis.consultation

import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class ConsultationFeatureTest {
    @Test fun releaseCannotBeEnabledEvenByAnInternalOptIn() {
        assertFalse(consultationFeatureEnabled(debugBuild = false, internalOptIn = false))
        assertFalse(consultationFeatureEnabled(debugBuild = false, internalOptIn = true))
    }

    @Test fun ordinaryDebugIsClosedAndInternalDebugIsExplicit() {
        assertFalse(consultationFeatureEnabled(debugBuild = true, internalOptIn = false))
        assertTrue(consultationFeatureEnabled(debugBuild = true, internalOptIn = true))
    }

    @Test fun closedPolicyFailsBeforeProtectedActionsWithoutPrivateErrorDetails() {
        var performed = false
        val error = runCatching {
            ConsultationFeaturePolicy(false).requireEnabled()
            performed = true
        }.exceptionOrNull()
        assertFalse(performed)
        assertTrue(error is IllegalStateException)
        assertEquals("consultation_not_open", error?.message)
        assertNull(error?.cause)
    }

    @Test fun internalPolicyDoesNotSkipItsProtectedAction() {
        var performed = false
        ConsultationFeaturePolicy(true).requireEnabled()
        performed = true
        assertTrue(performed)
    }

    @Test fun productionPolicyMatchesBothImmutableBuildFields() {
        assertEquals(BuildConfig.DEBUG && BuildConfig.ORBIS_CONSULTATION_ENABLED, consultationFeature.enabled)
    }
}
