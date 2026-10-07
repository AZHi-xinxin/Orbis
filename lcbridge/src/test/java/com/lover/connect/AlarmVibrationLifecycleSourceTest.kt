package com.lover.connect

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring guard complements the executable session tests without ringing any real alarm. */
class AlarmVibrationLifecycleSourceTest {
    private fun source(): String = listOf(
        File("src/main/java/com/lover/connect/AlarmRingService.kt"),
        File("lcbridge/src/main/java/com/lover/connect/AlarmRingService.kt"),
    ).first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun stopReplacementAndDestroyAllReleaseOwnedVibrationSession() {
        val text = source()
        assertTrue(text.contains("releasePlayback(\"notification_stop\")"))
        assertTrue(text.contains("releasePlayback(\"replaced_by_next_alarm\")"))
        assertTrue(text.contains("releasePlayback(\"service_destroyed\")"))
        val release = text.substringAfter("private fun releasePlayback(").substringBefore("private fun startRinging")
        assertTrue(release.contains("vibrationSession.stop()"))
        assertTrue(release.contains("ringtonePlayer.stop()"))
        assertTrue(text.contains("vibrationHandler.removeCallbacks(it)"))
        assertTrue(text.contains("runCatching { vibrationSession.start() }"))
    }

    @Test fun existingChannelAndReadFailureCannotRestoreDisabledVibration() {
        val text = source()
        val create = text.substringAfter("private fun createChannel()").substringBefore("private fun buildAlarmNotification")
        assertTrue(create.contains("if (nm.getNotificationChannel(CHANNEL_ID) != null) return"))
        val read = text.substringAfter("private fun isVibrationAllowed()").substringBefore("private fun startVibrating")
        assertTrue(read.contains("?: return@runCatching false"))
        assertTrue(read.contains("getOrDefault(false)"))
        assertTrue(read.contains("manager.areNotificationsEnabled()"))
        assertTrue(read.contains("channel.shouldVibrate()"))
        assertTrue(read.contains("companionAlarmVibrationImportanceAllowed(channel.importance) && !groupBlocked"))
    }
}
