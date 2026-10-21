package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupAttemptDeviceTest {
    @Test fun uploadPhaseDoesNotReuseAnotherTargetOrAConnectionCheckReceipt() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.applicationContext.javaClass == Application::class.java)
        val target = "webdav-synthetic"
        try {
            AiBackupOperations.receipt(context, "owner", target, "not_completed_check_connection_or_complete_version")
            BackupAttempt.write(context, target, "remote_publish_and_verify")
            assertEquals("remote_publish_and_verify", BackupAttempt.status(context, target)["phase"]?.jsonPrimitive?.content)
            assertEquals("not_started", BackupAttempt.status(context, "different")["phase"]?.jsonPrimitive?.content)
            assertEquals("not_completed_check_connection_or_complete_version", AiBackupOperations.read(context, "owner", target))
            BackupAttempt.write(context, target, "remote_failed", "http_503")
            assertEquals("http_503", BackupAttempt.status(context, target)["failure"]?.jsonPrimitive?.content)
            BackupAttempt.write(context, target, "remote_attempt_finished")
            assertFalse(BackupAttempt.status(context, target).containsKey("failure"))
        } finally {
            context.getSharedPreferences("orbis-backup-attempt", 0).edit().clear().commit()
            context.getSharedPreferences("orbis-ai-backup-operations", 0).edit().clear().commit()
        }
    }

    @Test fun explicitRevisionExistsBeforePendingCheckWithoutObserverDebounce() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.applicationContext.javaClass == Application::class.java)
        ContinuousBackup.initialize(context)
        val before = ContinuousBackup.progress.value.revision
        ContinuousBackup.complete(context, "synthetic", before, 1L)
        assertFalse(ContinuousBackup.progress.value.pending("synthetic"))
        ContinuousBackup.requested(context)
        assertEquals(before + 1, ContinuousBackup.progress.value.revision)
        assertTrue(ContinuousBackup.progress.value.pending("synthetic"))
        assertEquals(1L, ContinuousBackup.progress.value.targets["synthetic"]?.completedAt)
    }
}
