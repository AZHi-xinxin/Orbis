package com.lover.connect

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

class CompanionAlarmVibrationTest {
    @Test fun silentImportanceLevelsCannotVibrateEvenWhenTheChannelFlagIsStillEnabled() {
        listOf(NotificationManager.IMPORTANCE_UNSPECIFIED, NotificationManager.IMPORTANCE_NONE,
            NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_LOW).forEach { importance ->
            assertFalse("importance=$importance", companionAlarmVibrationImportanceAllowed(importance))
            assertFalse(companionAlarmVibrationAllowed(true,
                companionAlarmVibrationImportanceAllowed(importance), true))
        }
    }

    @Test fun defaultAndHighImportanceAllowVibrationOnlyWithBothOptIns() {
        listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_HIGH).forEach { importance ->
            val importanceAllowsVibration = companionAlarmVibrationImportanceAllowed(importance)
            assertTrue("importance=$importance", importanceAllowsVibration)
            assertTrue(companionAlarmVibrationAllowed(true, importanceAllowsVibration, true))
            assertFalse(companionAlarmVibrationAllowed(false, importanceAllowsVibration, true))
            assertFalse(companionAlarmVibrationAllowed(true, importanceAllowsVibration, false))
        }
    }

    @Test fun everyAppOrChannelOptOutPreventsActiveVibration() {
        for (app in listOf(false, true)) {
            for (channel in listOf(false, true)) {
                for (vibration in listOf(false, true)) {
                    assertEquals(app && channel && vibration,
                        companionAlarmVibrationAllowed(app, channel, vibration))
                }
            }
        }
    }

    @Test fun disabledChannelDoesNotStartOrScheduleAVibrationLoop() {
        val f = Fixture(allowed = false)
        f.session.start()
        assertEquals(0, f.starts)
        assertFalse(f.vibrating)
        assertTrue(f.pending.isEmpty())
    }

    @Test fun enabledChannelStartsOnceAndKeepsOnlyOneCheckPending() {
        val f = Fixture()
        f.session.start()
        repeat(3) {
            assertEquals(1, f.pending.size)
            f.runCheck()
        }
        assertEquals(1, f.starts)
        assertTrue(f.vibrating)
        assertEquals(1, f.pending.size)
    }

    @Test fun disablingVibrationDuringARingCancelsWithoutRestarting() {
        val f = Fixture()
        f.session.start()
        f.allowed = false
        f.runCheck()
        assertFalse(f.vibrating)
        assertTrue(f.pending.isEmpty())
        f.allowed = true
        assertEquals(1, f.starts)
        // A later alarm can start again, but changing a setting does not restart the old alarm.
        f.session.start()
        assertEquals(2, f.starts)
    }

    @Test fun stopOrServiceDestructionCancelsLoopAndQueuedCheck() {
        val f = Fixture()
        f.session.start()
        val stale = f.pending.single()
        f.session.stop()
        stale.run()
        f.session.stop()
        assertFalse(f.vibrating)
        assertTrue(f.pending.isEmpty())
        assertEquals(1, f.starts)
    }

    @Test fun replacementCannotLeaveThePreviousLoopOrCheckRunning() {
        val f = Fixture()
        f.session.start()
        f.session.start()
        assertEquals(2, f.starts)
        assertEquals(1, f.pending.size)
        assertTrue(f.vibrating)
        assertEquals(2, f.cancels)
    }

    @Test fun vibratorFailureDoesNotLeavePlaybackOrAQueuedPermissionCheck() {
        val f = Fixture()
        f.failStart = true
        try {
            f.session.start()
            fail("Expected vibrator failure")
        } catch (_: IllegalStateException) {
            assertFalse(f.vibrating)
            assertTrue(f.pending.isEmpty())
        }
    }

    private class Fixture(var allowed: Boolean = true) {
        var starts = 0
        var cancels = 0
        var vibrating = false
        var failStart = false
        val pending = mutableListOf<Runnable>()
        val session = CompanionAlarmVibrationSession(
            isAllowed = { allowed },
            startVibrating = {
                starts++
                vibrating = true
                if (failStart) error("synthetic vibrator failure")
            },
            stopVibrating = { cancels++; vibrating = false },
            scheduleCheck = { pending.add(it) },
            cancelCheck = { check -> pending.removeAll { it === check } },
        )
        fun runCheck() { pending.removeAt(0).run() }
    }
}
