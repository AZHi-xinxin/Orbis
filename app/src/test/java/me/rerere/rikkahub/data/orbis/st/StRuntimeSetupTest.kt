package me.rerere.rikkahub.data.orbis.st

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StRuntimeSetupTest {
    @Test fun enablingDoesNotPretendCurrentRunContainsNewToolOrRegisterAnInstance() {
        val result = stRuntimeReadiness(true, true, true, false, false, "stopped")
        assertEquals("new_turn_required", result["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), result["registered_for_this_run"])
        assertEquals(JsonPrimitive(false), result["instance_registered"])
        assertEquals(JsonPrimitive(false), result["execution_performed"])
        assertTrue(result["next_steps"]!!.jsonPrimitive.content.contains("另发一条新消息"))
        val refreshed = stRuntimeReadiness(true, true, true, true, false, "stopped")
        assertEquals("first_start_required", refreshed["reason"]!!.jsonPrimitive.content)
        assertTrue(refreshed["next_steps"]!!.jsonPrimitive.content.contains("不能让人类"))
    }

    @Test fun revokedPermissionMissingWorkspaceAndMissingShellTakePriority() {
        for ((flags, expected) in listOf(
            listOf(false, false, false) to "workspace_unbound",
            listOf(true, false, true) to "hosting_disabled",
            listOf(true, true, false) to "shell_not_ready",
        )) {
            assertEquals(expected, stRuntimeReadiness(flags[0], flags[1], flags[2], true, true, "stopped")
                ["reason"]!!.jsonPrimitive.content)
        }
    }

    @Test fun registrationAndHealthNeverClaimModelOrMemoryAcceptance() {
        val result = stRuntimeReadiness(true, true, true, true, true, "ready")
        assertEquals("instance_registered", result["reason"]!!.jsonPrimitive.content)
        assertTrue(result["next_steps"]!!.jsonPrimitive.content.contains("仍需验证"))
        assertFalse(result.containsKey("model_reply_verified"))
        assertFalse(result.containsKey("memory_roundtrip_verified"))
    }
}
