package me.rerere.rikkahub.data.orbis

import java.util.UUID

enum class GatewayRecoveryScopeStatus { LEGACY, MATCH, MISMATCH, UNAVAILABLE }

/** Hashes only: no endpoint, credential, request nonce, chat content or remote-idle receipt. */
class GatewayRecoveryScopeStore(private val storage: OrbisQueuePauseStore) {
    fun status(conversationId: String, fingerprint: String): GatewayRecoveryScopeStatus = try {
        validate(fingerprint)
        if (storage.pauseReason(conflictId(conversationId)) != null) GatewayRecoveryScopeStatus.MISMATCH
        else when (storage.pauseReason(conversationId)) {
            null -> GatewayRecoveryScopeStatus.LEGACY
            PREFIX + fingerprint -> GatewayRecoveryScopeStatus.MATCH
            else -> GatewayRecoveryScopeStatus.MISMATCH
        }
    } catch (_: Exception) { GatewayRecoveryScopeStatus.UNAVAILABLE }

    /** Preserve the first unresolved destination; conflicting destinations cannot clear each other. */
    fun record(conversationId: String, fingerprint: String) {
        validate(fingerprint)
        when (status(conversationId, fingerprint)) {
            GatewayRecoveryScopeStatus.LEGACY -> storage.pause(conversationId, PREFIX + fingerprint)
            GatewayRecoveryScopeStatus.MATCH -> Unit
            GatewayRecoveryScopeStatus.MISMATCH -> storage.pause(conflictId(conversationId), "multiple_unconfirmed_scopes")
            GatewayRecoveryScopeStatus.UNAVAILABLE -> error("gateway_scope_unavailable")
        }
        check(status(conversationId, fingerprint) != GatewayRecoveryScopeStatus.UNAVAILABLE)
    }

    /** Only after trusted idle and the caller's final owner/history CAS; never clears a conflict. */
    fun clearAfterConfirmedIdle(conversationId: String, fingerprint: String) {
        when (status(conversationId, fingerprint)) {
            GatewayRecoveryScopeStatus.LEGACY -> return
            GatewayRecoveryScopeStatus.MATCH -> check(storage.resumeIfReason(conversationId, PREFIX + fingerprint))
            else -> error("gateway_scope_changed")
        }
        check(status(conversationId, fingerprint) == GatewayRecoveryScopeStatus.LEGACY)
    }

    private fun validate(value: String) = require(value.matches(Regex("[0-9a-f]{64}")))
    private fun conflictId(conversationId: String): String {
        require(UUID.fromString(conversationId).toString() == conversationId)
        return UUID.nameUUIDFromBytes(("orbis-gateway-scope-conflict:" + conversationId).toByteArray(Charsets.UTF_8)).toString()
    }
    companion object {
        const val FILE_NAME = "orbis-gateway-recovery-scopes-v1.json"
        private const val PREFIX = "scope_"
    }
}
