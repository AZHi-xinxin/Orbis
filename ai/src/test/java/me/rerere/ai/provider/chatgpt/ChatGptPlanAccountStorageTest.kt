package me.rerere.ai.provider.chatgpt

import org.junit.Assert.*
import org.junit.Test

class ChatGptPlanAccountStorageTest {
    @Test fun quarantinedRotationIsSerializableWithoutMakingCredentialsDisplayable() {
        val pending = PlanCredential(clientId = "oaiapp_synthetic", subject = "synthetic-subject",
            accessToken = "synthetic-new-access", refreshToken = "synthetic-new-refresh", identityPending = true,
            pendingNonce = "synthetic-nonce")
        val encoded = kotlinx.serialization.json.Json.encodeToString(PlanCredential.serializer(), pending)
        assertEquals(pending, kotlinx.serialization.json.Json.decodeFromString(PlanCredential.serializer(), encoded))
        assertFalse(pending.toString().contains("synthetic-new"))
        assertFalse(pending.toString().contains("synthetic-nonce"))
    }
    @Test fun accountLabelsRemainStableWhenReauthorizationReordersRegistrations() {
        val first = PlanCredential(id = "12345678-0000-4000-8000-000000000000", clientId = "oaiapp_first", subject = "first", email = "same@example.test")
        val second = PlanCredential(id = "87654321-0000-4000-8000-000000000000", clientId = "oaiapp_second", subject = "second", email = "same@example.test")
        val before = listOf(first, second).associate { it.id to it.accountLabel }
        val after = listOf(second, first.copy(accessToken = "test-access-token", welcomeSeen = true))
            .associate { it.id to it.accountLabel }
        assertEquals(before, after)
        assertNotEquals(first.accountLabel, second.accountLabel)
        assertEquals("same@example.test · 12345678", first.accountLabel)
        assertFalse(after.values.any { it.contains("test-access-token") })
        assertEquals("ChatGPT 账号 · 12345678", first.copy(email = "").accountLabel)
    }

    @Test fun encryptedVaultWriteLimitMatchesReadableBounds() {
        requireWritablePlanVaultSize(29)
        requireWritablePlanVaultSize(1_048_576)
        listOf(-1, 0, 28, 1_048_577, Int.MAX_VALUE).forEach { size ->
            val error = assertThrows(IllegalArgumentException::class.java) { requireWritablePlanVaultSize(size) }
            assertEquals("siwc_vault_invalid", error.message)
        }
    }
}
