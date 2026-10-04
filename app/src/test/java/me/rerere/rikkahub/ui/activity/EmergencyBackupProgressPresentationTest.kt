package me.rerere.rikkahub.ui.activity

import org.junit.Assert.*
import org.junit.Test

class EmergencyBackupProgressPresentationTest {
    @Test fun eachBackupPhaseHasItsOwnHumanReadableTitle() {
        val phases = listOf("scan", "copy", "manifest", "rescan", "rehash", "final_scan", "verify", "sync", "source_verify", "source_hash", "source_recheck", "save", "readback")
        assertEquals(phases.size, phases.map { emergencyBackupProgressPresentation(it, 0, 100).title }.distinct().size)
        phases.forEach { assertEquals(it, emergencyBackupProgressPresentation(it, 0, 100).stage) }
    }

    @Test fun scanningShowsVisitedEntriesWithoutPretendingToKnowTheTotal() {
        for (phase in listOf("scan", "rescan", "final_scan")) {
            val value = emergencyBackupProgressPresentation(phase, 40, 100, 274, 0)
            assertNull(value.percent)
            assertTrue(value.detail.contains("274 项"))
        }
    }

    @Test fun unknownOrInconsistentTotalsNeverInventAPercentage() {
        for ((completed, total) in listOf(0L to 0L, 10L to -1L, -1L to 10L, 11L to 10L)) {
            assertNull(emergencyBackupProgressPresentation("copy", completed, total).percent)
        }
        assertNull(emergencyBackupProgressPresentation("scan", 5, 10).percent)
        assertNull(emergencyBackupProgressPresentation("complete", 10, 10).percent)
    }

    @Test fun percentageIsExplicitlyPerStageAndHandlesLargeCounters() {
        val progress = emergencyBackupProgressPresentation("readback", Long.MAX_VALUE / 2, Long.MAX_VALUE)
        assertEquals(50, progress.percent)
        assertTrue(progress.detail.startsWith("本阶段"))
        assertFalse(progress.detail.contains("已保存"))
    }

    @Test fun unknownPhaseCannotExposeUntrustedTextOrPaths() {
        val value = emergencyBackupProgressPresentation("/private/source/token=synthetic", 0, 0)
        assertEquals("prepare", value.stage)
        assertFalse(value.toString().contains("synthetic"))
        assertFalse(value.toString().contains("/private"))
    }

    @Test fun archiveCompletionDoesNotClaimExternalBackupSuccess() {
        val value = emergencyBackupProgressPresentation("complete", 100, 100)
        assertTrue(value.detail.contains("不代表外部备份已经完成"))
    }

    @Test fun displayUpdatesAndSafetyChecksHaveIndependentThrottles() {
        val throttle = EmergencyBackupProgressThrottle()
        assertTrue(throttle.shouldDisplay("copy", 0))
        assertTrue(throttle.needsSafetyCheck("copy", 0))
        throttle.safetyCheckFinished("copy", 800)
        assertTrue(throttle.shouldDisplay("copy", 900))
        assertFalse(throttle.needsSafetyCheck("copy", 900))
        assertFalse(throttle.needsSafetyCheck("copy", 2799))
        assertTrue(throttle.needsSafetyCheck("copy", 2800))
    }

    @Test fun phaseChangeCompletionAndClockResetAreNeverThrottledAway() {
        val throttle = EmergencyBackupProgressThrottle()
        throttle.shouldDisplay("copy", 1000)
        throttle.safetyCheckFinished("copy", 1000)
        assertFalse(throttle.shouldDisplay("copy", 1001))
        assertTrue(throttle.shouldDisplay("rehash", 1001))
        assertTrue(throttle.needsSafetyCheck("rehash", 1001))
        throttle.safetyCheckFinished("complete", 1002)
        assertTrue(throttle.needsSafetyCheck("complete", 1003))
        assertTrue(throttle.shouldDisplay("complete", 1003))
        assertTrue(throttle.needsSafetyCheck("copy", 999))
    }

    @Test fun reconstructionKeepsWhitelistedFailureCodeAndFixedExplanation() {
        val code = "ORBIS-RESCUE/E_PATH_POLICY stage=scan"
        val saved = emergencyBackupSavedDiagnostic(code)
        val view = emergencyBackupRebuiltPresentation(false, false, saved)
        assertTrue(view.failed)
        assertEquals(code, view.diagnostic)
        assertTrue(view.message.contains("文件名或恢复位置未通过安全检查"))
        assertTrue(view.message.contains("不会自动重试"))
    }

    @Test fun reconstructionOfBusyWorkNeverReusesOldFailureOrClaimsWorkResumed() {
        val value = emergencyBackupRebuiltPresentation(true, false, "ORBIS-RESCUE/E_PATH_POLICY stage=scan")
        assertTrue(value.failed)
        assertNull(value.diagnostic)
        assertTrue(value.message.contains("无法在此确认上一操作结果"))
        assertTrue(value.message.contains("不能据此视为完成"))
        assertTrue(value.message.contains("不会自动重新开始"))
    }

    @Test fun pickerStateDoesNotKeepAnOldSuccessOrFailure() {
        val value = emergencyBackupRebuiltPresentation(false, true, "ORBIS-RESCUE/E_INTEGRITY stage=save")
        assertFalse(value.failed)
        assertNull(value.diagnostic)
        assertEquals(EMERGENCY_PICKER_WAITING_MESSAGE, value.message)
        assertTrue(value.message.contains("本次尚未确认"))
    }

    @Test fun unknownCodesAndPrivatePayloadsAreNotSavedOrReconstructed() {
        for (value in listOf(
            "ORBIS-RESCUE/E_UNKNOWN_CREDENTIAL stage=scan",
            "ORBIS-RESCUE/E_PATH_POLICY stage=private_source_path",
            "ORBIS-RESCUE/E_PATH_POLICY stage=scan\nsynthetic-private-secret",
            "raw /data/user/0/private synthetic-private-secret",
        )) {
            assertNull(emergencyBackupSavedDiagnostic(value))
            val rebuilt = emergencyBackupRebuiltPresentation(false, false, value)
            assertNull(rebuilt.diagnostic)
            assertFalse(rebuilt.message.contains("synthetic-private"))
        }
    }

    @Test fun pickerFailureCanBeRestoredAndIdleNeverRestoresAnOldSuccess() {
        val code = emergencyBackupSavedDiagnostic("ORBIS_RECOVERY_PICKER_UNAVAILABLE · picker")
        assertEquals("ORBIS-RESCUE/E_PICKER_UNAVAILABLE stage=destination", code)
        assertTrue(emergencyBackupRebuiltPresentation(false, false, code).message.contains("文件选择器"))
        val idle = emergencyBackupRebuiltPresentation(false, false, null)
        assertTrue(idle.message.contains("没有恢复上次操作的成功状态"))
    }
}
