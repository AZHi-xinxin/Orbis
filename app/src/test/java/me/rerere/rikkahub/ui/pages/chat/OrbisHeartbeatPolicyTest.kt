package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisHeartbeatPolicyTest {
    @Test fun onlyVisibleResumedAndMotionEnabledMayAnimate() {
        for (visible in listOf(false, true)) for (resumed in listOf(false, true)) {
            for (reduced in listOf(false, true)) for (system in listOf(false, true)) {
                assertEquals(visible && resumed && !reduced && system,
                    shouldAnimateOrbisHeartbeat(visible, resumed, reduced, system))
            }
        }
    }
}
