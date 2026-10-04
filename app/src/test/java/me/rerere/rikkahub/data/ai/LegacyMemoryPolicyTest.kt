package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMemoryPolicyTest {
    @Test fun importedEnabledFlagCannotReactivateRetiredOrbisMemory() {
        assertFalse(legacyMemoryEnabled(true, orbisEnabled = true))
        assertFalse(legacyMemoryEnabled(false, orbisEnabled = true))
    }

    @Test fun nonOrbisCompatibilityDoesNotChangeStoredSetting() {
        assertTrue(legacyMemoryEnabled(true, orbisEnabled = false))
        assertFalse(legacyMemoryEnabled(false, orbisEnabled = false))
    }
}
