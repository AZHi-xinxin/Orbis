package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

class AppRestEventGateTest {
    private val app = "com.xingin.xhs"

    @Test fun callbackAfterResumeDoesNotDiscardThatAppsRealUsageEvent() {
        val gate = AppRestEventGate().apply { reset(0); expect(app, 110) }
        assertTrue(gate.allowsResume(app, 100, 120))
        assertFalse(gate.allowsResume("com.other.app", 100, 120))
    }

    @Test fun aHintAloneCannotProduceUsageEvidence() {
        val gate = AppRestEventGate().apply { reset(100); expect(app, 110) }
        assertFalse(gate.allowsResume(null, 110, 120))
        assertFalse(gate.allowsResume(app, 99, 120))
    }

    @Test fun chatSystemAndLockBarriersBlockOldDelayedResumes() {
        for (reason in listOf("chat", "system", "lock", "permission", "restart")) {
            val gate = AppRestEventGate().apply { reset(0); expect(app, 110); blockThrough(115) }
            assertFalse(reason, gate.allowsResume(app, 100, 120))
            assertFalse(reason, gate.allowsResume(app, 115, 120))
            gate.expect(app, 140)
            assertTrue(reason, gate.allowsResume(app, 130, 145))
        }
    }

    @Test fun latestWindowHintRejectsEarlierDifferentApp() {
        val gate = AppRestEventGate().apply { reset(0); expect(app, 110); expect("com.reader.app", 130) }
        assertFalse(gate.allowsResume(app, 100, 140))
        assertTrue(gate.allowsResume("com.reader.app", 125, 140))
        assertTrue(gate.allowsResume(app, 150, 155)) // A genuinely newer usage event.
    }

    @Test fun futureAndExcessivelyLateEvidenceIsUnknown() {
        val gate = AppRestEventGate().apply { reset(0); expect(app, 110) }
        assertFalse(gate.allowsResume(app, 200, 120))
        assertTrue(gate.allowsResume(app, 100, 15_100))
        assertFalse(gate.allowsResume(app, 100, 15_101))
    }
}
