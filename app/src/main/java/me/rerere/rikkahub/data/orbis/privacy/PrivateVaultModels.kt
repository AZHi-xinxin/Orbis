package me.rerere.rikkahub.data.orbis.privacy

import kotlinx.serialization.Serializable

/** Fixed reason codes only; never propagate paths, model output, plaintext or key material. */
class PrivateVaultException(val reasonCode: String) : IllegalStateException("private_vault:$reasonCode")

enum class PrivateVaultAvailability { ABSENT, READY, RECOVERY_REQUIRED, UNREADABLE }
data class PrivateVaultStatus(
    val availability: PrivateVaultAvailability,
    val enabled: Boolean = false,
    val recoveryConfirmed: Boolean = false,
    val recordCount: Int = 0,
    val pendingRequestCount: Int = 0,
)

@Serializable
data class PrivateVaultRecordInfo(val id: String, val title: String, val createdAtMs: Long, val updatedAtMs: Long) {
    override fun toString() = "PrivateVaultRecordInfo(redacted)"
}

@Serializable
data class PrivateVaultRecord(val id: String, val title: String, val body: String,
                              val createdAtMs: Long, val updatedAtMs: Long) {
    override fun toString() = "PrivateVaultRecord(redacted)"
}

@Serializable
enum class PrivateVaultRequestStatus { PENDING, APPROVED, DENIED, REVOKED, EXPIRED }

@Serializable
data class PrivateVaultAccessRequest(
    val id: String, val purpose: String, val recordIds: List<String>, val createdAtMs: Long,
    val requestedDurationMs: Long, val status: PrivateVaultRequestStatus,
    val expiresAtMs: Long? = null,
) {
    override fun toString() = "PrivateVaultAccessRequest(redacted)"
}

@Serializable
data class PrivateVaultAuditEvent(val id: String, val atMs: Long, val action: String,
                                 val requestId: String? = null, val recordId: String? = null,
                                 val bindingDigest: String? = null) {
    override fun toString() = "PrivateVaultAuditEvent(redacted)"
}

@Serializable
internal data class VaultRecordRef(val id: String, val version: String, val title: String,
                                   val createdAtMs: Long, val updatedAtMs: Long) {
    fun info() = PrivateVaultRecordInfo(id, title, createdAtMs, updatedAtMs)
    override fun toString() = "VaultRecordRef(redacted)"
}

@Serializable
internal data class VaultRequest(
    val request: PrivateVaultAccessRequest, val refs: List<VaultRecordRef>,
    val decisionProcess: String? = null, val approvedAtMs: Long? = null,
    val approvedElapsedMs: Long? = null,
) {
    override fun toString() = "VaultRequest(redacted)"
}

@Serializable
internal data class VaultState(
    val version: Int = 1, val enabled: Boolean = false, val recoveryConfirmed: Boolean = false,
    val epoch: String, val revision: Long = 0, val records: List<VaultRecordRef> = emptyList(),
    val requests: List<VaultRequest> = emptyList(), val audit: List<PrivateVaultAuditEvent> = emptyList(),
) {
    override fun toString() = "VaultState(redacted)"
}

/** Everything sensitive, including titles, scopes, requests and audit, lives in encryptedState. */
@Serializable
internal data class VaultEnvelope(val version: Int = 1, val vaultId: String, val ownerDigest: String,
                                  val deviceKey: String, val recoveryKey: String, val encryptedState: String) {
    override fun toString() = "VaultEnvelope(redacted)"
}
