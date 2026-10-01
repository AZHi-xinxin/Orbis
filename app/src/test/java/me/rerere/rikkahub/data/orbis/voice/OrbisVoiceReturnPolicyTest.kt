package me.rerere.rikkahub.data.orbis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisVoiceReturnPolicyTest {
    private val ready = OrbisVoiceCallRecord(
        id = "call-1",
        conversationId = "conversation-1",
        assistantId = "assistant-1",
        startedAtMs = 1000,
        connectedAtMs = 1100,
        endedAtMs = 6100,
        durationMs = 5000,
        status = OrbisVoiceCallStatus.ENDED,
        archiveStatus = OrbisVoiceArchiveStatus.READY,
        summary = "双方约好明天上午见面。",
        modelTranscript = "用户：明天上午见。\n助手：好，明天见。",
    )

    private fun phase(
        record: OrbisVoiceCallRecord?,
        ending: Boolean = false,
        runtimeError: String? = null,
        loadFailed: Boolean = false,
        visible: Boolean = false,
    ) = orbisVoiceReturnPhase(record, ending, runtimeError, loadFailed, visible)

    @Test fun `initial missing record waits regardless of ending or summary observation`() {
        for (ending in listOf(false, true)) for (visible in listOf(false, true)) {
            assertEquals(OrbisVoiceReturnPhase.WAITING, phase(null, ending = ending, visible = visible))
        }
    }

    @Test fun `ready archive requires visible local card but not destructive chat replacement`() {
        for (committed in listOf(false, true)) for (visible in listOf(false, true)) {
            for (ending in listOf(false, true)) {
                val expected = if (visible) OrbisVoiceReturnPhase.COMPLETE else OrbisVoiceReturnPhase.WAITING
                assertEquals("committed=$committed visible=$visible ending=$ending", expected,
                    phase(ready.copy(chatCommitted = committed), ending = ending, visible = visible))
            }
        }
    }

    @Test fun `archive then commit then chat observation completes only at the last step`() {
        val phases = listOf(
            phase(ready.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING), ending = true),
            phase(ready, ending = true),
            phase(ready.copy(chatCommitted = true), ending = true),
            phase(ready.copy(chatCommitted = true), ending = true, visible = true),
        )
        assertEquals(listOf(OrbisVoiceReturnPhase.WAITING, OrbisVoiceReturnPhase.WAITING,
            OrbisVoiceReturnPhase.WAITING, OrbisVoiceReturnPhase.COMPLETE), phases)
    }

    @Test fun `local card observation completes without changing model history`() {
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(ready, visible = true))
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(ready.copy(chatCommitted = true), visible = true))
    }

    @Test fun `pending and generating cannot complete even with optimistic commit and UI flags`() {
        for (status in listOf(OrbisVoiceArchiveStatus.PENDING, OrbisVoiceArchiveStatus.GENERATING)) {
            assertEquals(OrbisVoiceReturnPhase.WAITING,
                phase(ready.copy(archiveStatus = status, chatCommitted = true), visible = true))
        }
    }

    @Test fun `durable failed archive is reported even while ending and without an error string`() {
        val failed = ready.copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED, chatCommitted = true)
        for (ending in listOf(false, true)) for (visible in listOf(false, true)) {
            assertEquals(OrbisVoiceReturnPhase.FAILED, phase(failed, ending = ending, visible = visible))
        }
    }

    @Test fun `legacy page commit error does not hide a durable archive visible in the new local card`() {
        val failedCommit = ready.copy(error = "chat_commit_failed")
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(failedCommit, ending = true, visible = true))
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(failedCommit, ending = false, visible = true))
        assertEquals(OrbisVoiceReturnPhase.COMPLETE,
            phase(failedCommit.copy(error = null, chatCommitted = true), visible = true))
    }

    @Test fun `runtime error waits during ending but fails even before a record can be loaded`() {
        for (record in listOf(null, ready)) {
            assertEquals(OrbisVoiceReturnPhase.WAITING,
                phase(record, ending = true, runtimeError = "archive_failed"))
            assertEquals(OrbisVoiceReturnPhase.FAILED,
                phase(record, ending = false, runtimeError = "archive_failed"))
        }
    }

    @Test fun `explicit raw only returns after local card observation without claiming summary success`() {
        val raw = ready.copy(archiveStatus = OrbisVoiceArchiveStatus.PENDING, summary = null,
            modelTranscript = null, archiveFailureCode = "model_not_configured")
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(raw, visible = true))
        assertEquals(OrbisVoiceReturnPhase.WAITING, phase(raw))
        assertEquals(OrbisVoiceReturnPhase.WAITING, phase(raw.copy(archiveFailureCode = null), visible = true))
        assertEquals(OrbisVoiceReturnPhase.WAITING, phase(raw.copy(status = OrbisVoiceCallStatus.ACTIVE), visible = true))
    }

    @Test fun `load failure is explicit and cannot be hidden by stale complete flags`() {
        for (record in listOf(null, ready, ready.copy(chatCommitted = true))) {
            for (ending in listOf(false, true)) {
                assertEquals(OrbisVoiceReturnPhase.FAILED,
                    phase(record, ending = ending, loadFailed = true, visible = true))
            }
        }
        assertEquals(OrbisVoiceReturnPhase.COMPLETE,
            phase(ready.copy(chatCommitted = true), loadFailed = false, visible = true))
    }

    @Test fun `nonblank errors fail incomplete return after ending but blank errors are ignored`() {
        val committed = ready.copy(chatCommitted = true)
        assertEquals(OrbisVoiceReturnPhase.COMPLETE, phase(ready.copy(error = "commit_failed"), visible = true))
        assertEquals(OrbisVoiceReturnPhase.FAILED, phase(committed, runtimeError = "archive_failed", visible = false))
        for (blank in listOf(null, "", " \n\t ")) {
            assertEquals(OrbisVoiceReturnPhase.COMPLETE,
                phase(committed.copy(error = blank), runtimeError = blank, visible = true))
        }
    }

    @Test fun `completed archive and observed summary win over old runtime error after ending`() {
        for (ending in listOf(false, true)) {
            assertEquals(OrbisVoiceReturnPhase.COMPLETE,
                phase(ready.copy(chatCommitted = true), ending = ending,
                    runtimeError = "voice_service_failed_before_successful_archive", visible = true))
        }
    }

    @Test fun `load failure still wins over completed archive with an old runtime error`() {
        assertEquals(OrbisVoiceReturnPhase.FAILED,
            phase(ready.copy(chatCommitted = true), runtimeError = "old_runtime_error",
                loadFailed = true, visible = true))
    }
}
