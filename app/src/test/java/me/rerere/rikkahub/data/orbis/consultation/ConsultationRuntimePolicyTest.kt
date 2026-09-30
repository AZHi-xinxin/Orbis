package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.json.*
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class ConsultationRuntimePolicyTest {
    private val sid = "a".repeat(32)
    private fun allowed(name: String, phase: String = "ACTIVE", args: JsonObject = buildJsonObject {},
        reads: Set<String> = emptySet(), writes: Set<String> = emptySet()) =
        consultationToolAllowed(name, args, phase, reads, writes, sid)
    @Test fun unknownToolCannotBecomeReadByUserSavedName() {
        assertFalse(allowed("delete_everything", reads = setOf("delete_everything")))
        assertFalse(allowed("mcp__brain__remember_memory", reads = setOf("mcp__brain__remember_memory")))
    }
    @Test fun canonicalReadAndArchiveAreSeparated() {
        assertTrue(allowed("mcp__ST__recall_work_memory"))
        assertFalse(allowed("mcp__ST__remember_work_memory"))
        assertTrue(allowed("mcp__ST__remember_work_memory", "ARCHIVING"))
        assertFalse(allowed("mcp__ST__remember_emotional_memory", "ARCHIVING"))
        assertFalse(allowed("mcp__ST__recall_work_memory", "CLOSED"))
    }
    @Test fun compositeRouterChecksActualActionAndNestedArguments() {
        assertTrue(allowed("mcp__ST__stbrain_manage", args = buildJsonObject { put("action", "recall_work_memory") }))
        assertFalse(allowed("mcp__ST__stbrain_manage", args = buildJsonObject { put("action", "remember_work_memory") }))
        assertTrue(allowed("mcp__ST__stbrain_manage", "ARCHIVING", buildJsonObject { put("action", "remember_work_memory") }))
        assertFalse(allowed("mcp__ST__stbrain_manage", args = buildJsonObject {
            put("action", "recall_work_memory"); put("arguments", buildJsonObject { put("execution_ref", "fake") })
        }))
    }
    @Test fun openMustExplicitlyUseRecallView() {
        assertFalse(allowed("mcp__ST__stbrain_open"))
        assertTrue(allowed("mcp__ST__stbrain_open", args = buildJsonObject { put("view", "recall") }))
        assertFalse(allowed("mcp__ST__stbrain_manage", args = buildJsonObject {
            put("action", "stbrain_open"); put("arguments", buildJsonObject { put("view", "direct") })
        }))
    }
    @Test fun workspaceWriteConfinedToOwnArchiveDirectory() {
        val own = buildJsonObject { put("path", "/workspace/consultation/$sid/report.md") }
        assertFalse(allowed("workspace_write_file", args = own))
        assertTrue(allowed("workspace_write_file", "ARCHIVING", own))
        for (path in listOf("/workspace/report.md", "/workspace/consultation/$sid/../report.md", "/workspace/consultation/$sid/%2froot")) {
            assertFalse(allowed("workspace_write_file", "ARCHIVING", buildJsonObject { put("path", path) }))
        }
    }
    @Test fun workspaceReadIsLimitedToReferenceLibraryInBothPhases() {
        for (phase in listOf("ACTIVE", "ARCHIVING")) {
            assertTrue(allowed("workspace_read_file", phase, buildJsonObject { put("path", "/workspace/orbis-reference-v02/00-index.md") }))
            for (path in listOf("/workspace/private.md", "/workspace/orbis-reference-v02/../private.md", "/workspace/orbis-reference-v02/sub/chapter.md")) {
                assertFalse(allowed("workspace_read_file", phase, buildJsonObject { put("path", path) }))
            }
        }
    }
    @Test fun shellAndGenericMemoryNeverGainArchiveAuthority() {
        for (name in listOf("workspace_shell", "memory_tool", "compact", "mcp__ST__compact"))
            assertFalse(allowed(name, "ARCHIVING", writes = setOf(name), reads = setOf(name)))
    }
    @Test fun unknownGenerationAndInFlightToolCannotRestartOrSubmit() {
        val base = ConsultationCheckpoint(sid, sid, "ACTIVE", "assistant", "binding", "input")
        assertTrue(canStartConsultationGeneration(null))
        assertTrue(canStartConsultationGeneration(base))
        for (state in listOf("RUNNING", "BUSY", "UNKNOWN", "COMPLETE", "SUBMITTED"))
            assertFalse(canStartConsultationGeneration(base.copy(state = state)))
        assertFalse(canSubmitConsultationCheckpoint(base.copy(state = "COMPLETE", finalText = "body", toolInFlight = "tool")))
    }
    @Test fun CompletedDeliveryRetryIsBoundedAndNeverRegenerates() {
        val complete = ConsultationCheckpoint(sid, sid, "ACTIVE", "assistant", "binding", "input", state = "COMPLETE", finalText = "body")
        assertTrue(canSubmitConsultationCheckpoint(complete))
        assertTrue(canSubmitConsultationCheckpoint(complete.copy(submitAttempts = 2)))
        assertFalse(canSubmitConsultationCheckpoint(complete.copy(submitAttempts = 3)))
        assertFalse(canStartConsultationGeneration(complete))
    }
    @Test fun gatewayBusyRequiresActualStatusAndStructuredCodeBeforeAnyOutput() {
        val busy = HttpException("safe", code = "human_turn_in_progress", httpStatus = 409)
        assertTrue(consultationGatewayRejectedBeforeGeneration(busy, false))
        assertFalse(consultationGatewayRejectedBeforeGeneration(busy, true))
        assertFalse(consultationGatewayRejectedBeforeGeneration(HttpException("human_turn_in_progress", httpStatus = 409), false))
        assertFalse(consultationGatewayRejectedBeforeGeneration(HttpException("safe", code = "human_turn_in_progress", httpStatus = 400), false))
        assertFalse(consultationGatewayRejectedBeforeGeneration(HttpException("safe", code = "other", httpStatus = 409), false))
        assertFalse(consultationGatewayRejectedBeforeGeneration(IllegalStateException("409 human_turn_in_progress"), false))
    }
    @Test fun ownPrivateSetupPersistsAcrossLaterTurnsWithoutInferringOtherPartySetup() {
        val setup = buildJsonObject { put("question", "synthetic-private-question"); put("selected_context", buildJsonArray { add("synthetic-own-context") }) }
        for (turn in listOf(0, 1, 20)) {
            val claim = buildJsonObject {
                put("private_setup", setup)
                put("history", buildJsonArray { repeat(turn) { add("synthetic-formal-reply") } })
            }
            val prompt = consultationPrivateSetupPrompt(claim)
            assertTrue(prompt.contains("synthetic-private-question"))
            assertTrue(prompt.contains("synthetic-own-context"))
        }
        assertEquals("", consultationPrivateSetupPrompt(buildJsonObject { put("private_setup", buildJsonObject {}) }))
        assertEquals("", consultationPrivateSetupPrompt(buildJsonObject {}))
        assertTrue(runCatching { consultationPrivateSetupPrompt(buildJsonObject {
            put("private_setup", buildJsonObject { put("question", "a".repeat(49153)) })
        }) }.isFailure)
    }
}
