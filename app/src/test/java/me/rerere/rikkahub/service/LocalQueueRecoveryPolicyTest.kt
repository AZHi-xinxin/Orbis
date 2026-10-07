package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class LocalQueueRecoveryPolicyTest {
    @Test fun `ordinary completed compatible provider may recover locally without control traffic`() {
        assertTrue(localOnlyQueueRecoveryAllowed(true, false, false))
    }
    @Test fun `current or historical gateway evidence forbids inferring direct provider`() {
        assertFalse(localOnlyQueueRecoveryAllowed(true, true, false))
    }
    @Test fun `existing gateway hold is not erased by missing response headers`() {
        assertFalse(localOnlyQueueRecoveryAllowed(true, false, true))
    }
    @Test fun `missing ended owner cannot manufacture local safety`() {
        assertFalse(localOnlyQueueRecoveryAllowed(false, false, false))
    }
}
