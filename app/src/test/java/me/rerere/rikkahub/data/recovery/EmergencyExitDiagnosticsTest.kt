package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class EmergencyExitDiagnosticsTest {
    private val packageName = "org.orbis.agent"
    private fun record(timestamp: Long, process: String? = packageName, reason: Int = 5) = EmergencyExitRecord(
        timestampMillis = timestamp,
        processName = process,
        reason = reason,
        status = 11,
        importance = 100,
        pssKb = 1234,
        rssKb = 2345,
    )

    private fun snapshot(records: List<EmergencyExitRecord> = emptyList()) = EmergencyExitDiagnosticsSnapshot(
        packageName = packageName,
        versionName = "2.6.12-rescue",
        versionCode = "275",
        buildType = "rescue",
        sdk = 37,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        pageSizeBytes = 16384,
        collectedAtMillis = 10000,
        currentProcessName = "$packageName:recovery",
        state = EmergencyExitHistoryState.COLLECTED,
        records = records,
    )

    @Test fun `safe metadata exposes platform cause and useful memory units`() {
        val text = formatEmergencyExitDiagnostics(snapshot(listOf(record(1000))))
        assertTrue(text.contains("sdk=37"))
        assertTrue(text.contains("page_size_bytes=16384"))
        assertTrue(text.contains("abis=arm64-v8a,armeabi-v7a"))
        assertTrue(text.contains("timestamp_epoch_ms=1000; timestamp_utc=1970-01-01T00:00:01Z"))
        assertTrue(text.contains("process=main"))
        assertTrue(text.contains("collector_process=recovery"))
        assertTrue(text.contains("reason=5; reason_name=native_crash"))
        assertTrue(text.contains("status=11; importance=100; pss_kb=1234; rss_kb=2345"))
    }

    @Test fun `sorts newest first and caps output`() {
        val text = formatEmergencyExitDiagnostics(snapshot((1..40).map { record(it.toLong()) }))
        assertEquals(16, Regex("exit\\[\\d+]").findAll(text).count())
        assertTrue(text.indexOf("timestamp_epoch_ms=40;") < text.indexOf("timestamp_epoch_ms=39;"))
        assertFalse(text.contains("timestamp_epoch_ms=24;"))
    }

    @Test fun `filters foreign packages and hides unknown local process names`() {
        val text = formatEmergencyExitDiagnostics(snapshot(listOf(
            record(1, "$packageName:secret-conversation-name"),
            record(2, "$packageName:recovery"),
            record(3, "com.another.app"),
            record(4, "${packageName}suffix"),
            record(5, null),
        )))
        assertTrue(text.contains("records_returned=2"))
        assertTrue(text.contains("process=recovery"))
        assertTrue(text.contains("process=other_app_process_redacted"))
        assertFalse(text.contains("secret-conversation-name"))
        assertFalse(text.contains("com.another"))
        assertFalse(text.contains("timestamp_epoch_ms=3;"))
    }

    @Test fun `unavailable and empty history do not claim the app never crashed`() {
        EmergencyExitHistoryState.entries.forEach { state ->
            val text = formatEmergencyExitDiagnostics(snapshot().copy(state = state))
            assertTrue(text.contains("history_state=${state.name.lowercase()}"))
            assertTrue(text.contains("empty_history_does_not_rule_out_crash=true"))
            assertTrue(text.contains("records_returned=0"))
        }
    }

    @Test fun `partial failure reports type only and unknown fields stay bounded`() {
        val text = formatEmergencyExitDiagnostics(snapshot(listOf(record(-1, reason = 999))).copy(
            state = EmergencyExitHistoryState.PARTIAL,
            failures = listOf("history:SecurityException", "query:password is secret\nother=data"),
            unreadableRecords = 2,
            versionName = "version\nsecret",
            supportedAbis = listOf("arm64-v8a", "private value"),
            pageSizeBytes = -1,
        ))
        assertTrue(text.contains("history_state=partial"))
        assertTrue(text.contains("history:SecurityException,failure:redacted"))
        assertTrue(text.contains("unreadable_records=2"))
        assertTrue(text.contains("installed_version=redacted"))
        assertTrue(text.contains("page_size_bytes=unknown"))
        assertTrue(text.contains("timestamp_utc=unknown"))
        assertTrue(text.contains("reason=999; reason_name=unrecognized"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("private value"))
    }

    @Test fun `exit self and user requested stay distinct from Java crash`() {
        val text = formatEmergencyExitDiagnostics(snapshot(listOf(
            record(1, reason = 1), record(2, reason = 10), record(3, reason = 4), record(4, reason = 6),
        )))
        assertTrue(text.contains("reason=1; reason_name=exit_self"))
        assertTrue(text.contains("reason=10; reason_name=user_requested"))
        assertTrue(text.contains("reason=4; reason_name=java_crash"))
        assertTrue(text.contains("reason=6; reason_name=anr"))
        assertTrue(text.contains("timestamps_must_be_compared_with_incident_and_backup_actions=true"))
        assertTrue(text.contains("installed_version_is_not_the_version_at_each_exit=true"))
    }

    @Test fun `all platform reason labels preserve verified SDK constants`() {
        val names = listOf(
            "unknown", "exit_self", "signaled", "low_memory", "java_crash", "native_crash", "anr",
            "initialization_failure", "permission_change", "excessive_resource_usage", "user_requested",
            "user_stopped", "dependency_died", "other", "freezer", "package_state_change", "package_updated",
        )
        names.forEachIndexed { reason, name ->
            val text = formatEmergencyExitDiagnostics(snapshot(listOf(record(1, reason = reason))))
            assertTrue("SDK reason $reason", text.contains("reason=$reason; reason_name=$name\n"))
        }
    }
}
