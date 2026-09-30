package me.rerere.rikkahub.data.orbis.consultation

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionCredential
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsultationLocalStatusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = "a".repeat(32)
    private val request = "b".repeat(32)
    private val assistant = "00000000-0000-0000-0000-000000000001"
    private val subject = "ai_fixture_subject"
    private val baseUrl = "https://consultation.example.invalid"
    private val human = OrbisConnectionCredential(baseUrl, "fixture-human-token", 9)
    private val json = Json { encodeDefaults = true }

    private fun runtime(base: String = baseUrl, token: String = human.token,
        aid: String = assistant, sub: String = subject, revision: Long = 4) =
        ConsultationRuntimeConfig(baseUrl = base, humanToken = token, assistantId = aid,
            subject = sub, revision = revision, enabled = true)

    private fun turn() = ConsultationConversationTurn(request, session, subject, assistant,
        "ACTIVE", 4, 5000L, "consultation-wake", 16384, 1)

    private fun checkpoint(state: String = "RUNNING", attempts: Int = 0): JsonObject =
        buildJsonObject {
            put("requestId", request); put("sessionId", session); put("phase", "ACTIVE")
            put("assistantId", assistant); put("state", state); put("submitAttempts", attempts)
            put("conversationTurn", Json.parseToJsonElement(json.encodeToString(turn())))
            // Fields excluded from the diagnostic schema must never escape the projection.
            put("messages", Json.parseToJsonElement("[{\"arbitrary-private-field\":\"PRIVATE_REASONING_AND_ARGUMENTS\"}]"))
            put("finalText", "PRIVATE_FINAL_TEXT")
            put("bindingDigest", "private-binding"); put("inputDigest", "private-input")
        }

    private fun JsonObject.with(name: String, value: String): JsonObject =
        JsonObject(toMutableMap().apply { put(name, JsonPrimitive(value)) })

    private fun JsonObject.withTurn(change: (JsonObject) -> JsonObject): JsonObject =
        JsonObject(toMutableMap().apply { put("conversationTurn", change(getValue("conversationTurn").jsonObject)) })

    private fun head(directory: File) = File(directory, "conversation-${consultationConversationId(session, subject)}.head")
    private fun data(directory: File) = File(directory, "$request.json")
    private fun fixture(value: JsonObject = checkpoint()): File = temporary.newFolder().apply {
        head(this).writeText(request)
        data(this).writeText(value.toString())
    }
    private fun status(directory: File, sid: String = session, config: ConsultationRuntimeConfig = runtime(),
        credential: OrbisConnectionCredential = human, now: Long = 1000) =
        readConsultationLocalStatus(directory, sid, config, credential, now)

    private fun snapshot(directory: File): Map<String, Pair<Long, List<Byte>>> =
        directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).path to (it.lastModified() to it.readBytes().toList())
        }

    @Test fun runningMetadataIsBoundedAndContentFree() {
        val result = status(fixture(checkpoint().with("toolInFlight", "PRIVATE_TOOL_NAME")))
        assertEquals(ConsultationLocalState.RUNNING, result.state)
        assertEquals(ConsultationLocalPhase.ACTIVE, result.phase)
        assertTrue(result.toolInFlight)
        assertEquals(0, result.submitAttempts)
        assertFalse(result.leaseExpired)
        assertFalse(result.toString().contains("PRIVATE"))
        assertFalse(result.toString().contains(request))
        assertFalse(result.toString().contains(human.token))
    }

    @Test fun readsNeitherChangeFilesNorCreateRecoveryArtifacts() {
        val directory = fixture()
        val before = snapshot(directory)
        repeat(3) { assertEquals(ConsultationLocalState.RUNNING, status(directory).state) }
        assertEquals(before, snapshot(directory))
    }

    @Test fun missingDirectoryDoesNotGetCreated() {
        val directory = File(temporary.root, "missing-private-store")
        val before = snapshot(temporary.root)
        assertEquals(ConsultationLocalState.NOT_FOUND, status(directory).state)
        assertFalse(directory.exists())
        assertEquals(before, snapshot(temporary.root))
    }

    @Test fun missingHeadDoesNotEnumerateAnotherSession() {
        val directory = temporary.newFolder()
        File(directory, "unrelated.json").writeText("PRIVATE_UNRELATED_CHECKPOINT")
        assertEquals(ConsultationLocalState.NOT_FOUND, status(directory).state)
    }

    @Test fun missingCheckpointBehindHeadIsUnavailable() {
        val directory = temporary.newFolder()
        head(directory).writeText(request)
        assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
    }

    @Test fun invalidSessionIdsAreUnavailableWithoutAccessingOtherPaths() {
        val directory = fixture()
        listOf("../other", "A".repeat(32), "", "a".repeat(33)).forEach {
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory, sid = it).state)
        }
    }

    @Test fun humanAddressAndTokenMustBothMatchCurrentRuntime() {
        val directory = fixture()
        listOf(runtime(base = "https://other.example.invalid"), runtime(token = "different-human"),
            runtime(token = ""), runtime(base = "")).forEach {
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory, config = it).state)
        }
    }

    @Test fun assistantAndRuntimeRevisionMustMatchCheckpointTurn() {
        val directory = fixture()
        listOf(runtime(aid = "different-assistant"), runtime(revision = 5), runtime(revision = -1)).forEach {
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory, config = it).state)
        }
    }

    @Test fun anotherSubjectCannotSeeOldSubjectsHead() {
        assertEquals(ConsultationLocalState.NOT_FOUND,
            status(fixture(), config = runtime(sub = "ai_other_subject")).state)
    }

    @Test fun crossedHeadRequestSessionAndAssistantAreRejected() {
        listOf("requestId" to "c".repeat(32), "sessionId" to "d".repeat(32),
            "assistantId" to "another-assistant").forEach { (key, value) ->
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(fixture(checkpoint().with(key, value))).state)
        }
    }

    @Test fun crossedTurnIdentityOrPhaseIsRejected() {
        listOf("requestId" to "c".repeat(32), "sessionId" to "d".repeat(32),
            "subject" to "ai_other_subject", "assistantId" to "other-assistant",
            "phase" to "ARCHIVING").forEach { (key, value) ->
            assertEquals(ConsultationLocalState.UNAVAILABLE,
                status(fixture(checkpoint().withTurn { it.with(key, value) })).state)
        }
    }

    @Test fun legacyCheckpointWithoutConversationTurnIsUnavailable() {
        assertEquals(ConsultationLocalState.UNAVAILABLE,
            status(fixture(JsonObject(checkpoint().filterKeys { it != "conversationTurn" }))).state)
    }

    @Test fun headBackupsAndPendingWritesAreNeverRecoveredOrRemoved() {
        listOf(".bak", ".new").forEach { suffix ->
            val directory = fixture()
            File(head(directory).path + suffix).writeText("c".repeat(32))
            val before = snapshot(directory)
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
            assertEquals(before, snapshot(directory))
        }
    }

    @Test fun backupOnlyHeadIsUnavailableWithoutRestoringBase() {
        val directory = temporary.newFolder()
        File(head(directory).path + ".bak").writeText(request)
        val before = snapshot(directory)
        assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
        assertFalse(head(directory).exists())
        assertEquals(before, snapshot(directory))
    }

    @Test fun checkpointSidecarsAreNotRecoveredOrRemoved() {
        listOf(".bak", ".new").forEach { suffix ->
            val directory = fixture()
            File(data(directory).path + suffix).writeText("PRIVATE_PENDING_CHECKPOINT")
            val before = snapshot(directory)
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
            assertEquals(before, snapshot(directory))
        }
    }

    @Test fun unknownStateAndPhaseNeverEscapeIntoUi() {
        assertEquals(ConsultationLocalState.UNAVAILABLE,
            status(fixture(checkpoint(state = "PRIVATE_EXCEPTION"))).state)
        assertEquals(ConsultationLocalState.UNAVAILABLE,
            status(fixture(checkpoint().with("phase", "PRIVATE_PHASE"))).state)
    }

    @Test fun unknownFailureIsReplacedWithOneClosedSafeCode() {
        val result = status(fixture(checkpoint(state = "UNKNOWN").with("failure", "Bearer PRIVATE_TOKEN exception body")))
        assertEquals("unclassified_local_failure", result.failureCode)
        assertFalse(result.toString().contains("PRIVATE_TOKEN"))
    }

    @Test fun allTerminalSafeCodesArePreservedExactly() {
        listOf("generation_not_completed_no_automatic_retry", "unexecuted_tool_no_automatic_retry",
            "missing_terminal_assistant_no_automatic_retry", "tool_call_tail_no_automatic_retry",
            "empty_final_text_no_automatic_retry", "oversized_final_text_no_automatic_retry").forEach { code ->
            assertEquals(code, status(fixture(checkpoint(state = "UNKNOWN").with("failure", code))).failureCode)
        }
    }

    @Test fun oldSafeCodeAndNullFailureRemainUsable() {
        listOf("network_interrupted_no_automatic_retry", "provider_request_failed_no_automatic_retry",
            "generation_deadline_expired_no_automatic_retry", "generation_cancelled_no_automatic_retry",
            "binding_changed_no_automatic_retry", "process_interrupted_evidence_recovered_no_automatic_retry",
            "session_ended_archive_without_active_replay").forEach { code ->
            assertEquals(code, status(fixture(checkpoint(state = "UNKNOWN").with("failure", code))).failureCode)
        }
        assertNull(status(fixture()).failureCode)
    }

    @Test fun completedSubmissionExhaustionIsDerivedWithoutWritingCheckpoint() {
        listOf(3, 4, Int.MAX_VALUE).forEach { attempts ->
            val directory = fixture(checkpoint(state = "COMPLETE", attempts = attempts))
            val before = snapshot(directory)
            val result = status(directory)
            assertEquals(ConsultationLocalState.SUBMISSION_EXHAUSTED, result.state)
            assertEquals(attempts, result.submitAttempts)
            assertEquals(before, snapshot(directory))
        }
        assertEquals(ConsultationLocalState.COMPLETE, status(fixture(checkpoint("COMPLETE", 2))).state)
        assertEquals(ConsultationLocalState.SUBMITTED, status(fixture(checkpoint("SUBMITTED", 3))).state)
    }

    @Test fun negativeAttemptsAndFabricatedDerivedStateAreRejected() {
        assertEquals(ConsultationLocalState.UNAVAILABLE, status(fixture(checkpoint(attempts = -1))).state)
        assertEquals(ConsultationLocalState.UNAVAILABLE, status(fixture(checkpoint("SUBMISSION_EXHAUSTED"))).state)
    }

    @Test fun httpStatusIsReturnedOnlyWithinProtocolRange() {
        listOf(99, 100, 200, 502, 599, 600).forEach { code ->
            val value = JsonObject(checkpoint().toMutableMap().apply { put("httpStatus", JsonPrimitive(code)) })
            assertEquals(code.takeIf { it in 100..599 }, status(fixture(value)).httpStatus)
        }
    }

    @Test fun expiryBoundaryIsInclusiveAndDoesNotChangeState() {
        val directory = fixture()
        assertFalse(status(directory, now = 4999).leaseExpired)
        assertTrue(status(directory, now = 5000).leaseExpired)
        assertEquals(ConsultationLocalState.RUNNING, status(directory, now = 6000).state)
    }

    @Test fun malformedOversizedOrInvalidUtf8MetadataIsUnavailable() {
        listOf("not-json".toByteArray(), ByteArray(2 * 1024 * 1024 + 1) { 'x'.code.toByte() },
            byteArrayOf(0xC3.toByte(), 0x28)).forEach { bytes ->
            val directory = fixture()
            data(directory).writeBytes(bytes)
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
        }
    }

    @Test fun malformedHeadAndOversizedHeadDoNotBecomePaths() {
        listOf("../private.json", "b".repeat(129), "b".repeat(32) + "\n").forEach { value ->
            val directory = fixture()
            head(directory).writeText(value)
            assertEquals(ConsultationLocalState.UNAVAILABLE, status(directory).state)
        }
    }

    @Test fun unavailableProjectionNeverContainsPartiallyReadState() {
        val result = status(fixture(checkpoint("COMPLETE", 3).withTurn { it.with("subject", "wrong") }))
        assertEquals(ConsultationLocalStatus(ConsultationLocalState.UNAVAILABLE), result)
    }
}
