package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshHumanInputPolicyTest {
    @Test fun `none and detached accept ordinary fresh input without a legacy permit`() {
        assertFalse(FreshHumanRecoveryStatus.NONE.requiresFreshPermit())
        assertFalse(FreshHumanRecoveryStatus.DETACHED.requiresFreshPermit())
    }

    @Test fun `active changed owner and unavailable still require a fresh permit`() {
        for (status in listOf(FreshHumanRecoveryStatus.ACTIVE, FreshHumanRecoveryStatus.OWNER_CHANGED,
            FreshHumanRecoveryStatus.UNAVAILABLE)) {
            assertTrue(status.name, status.requiresFreshPermit())
        }
    }

    @Test fun `detachment does not authorize derived or automatic work from the old turn`() {
        assertTrue(freshHumanModeAllowsDerivedRequests(FreshHumanRecoveryStatus.NONE))
        for (status in FreshHumanRecoveryStatus.entries.filter { it != FreshHumanRecoveryStatus.NONE }) {
            assertFalse(status.name, freshHumanModeAllowsDerivedRequests(status))
        }
    }
}
