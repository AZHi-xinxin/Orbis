package me.rerere.rikkahub.data.orbis.privacy

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous, encrypted local storage. Run on an IO dispatcher. This is an application policy
 * boundary, not a claim that a device owner or model provider cannot observe plaintext.
 * Never pass records or recovery material to ordinary chat, logs, Web or file-provider paths.
 */
class PrivateVaultRepository(
    baseDirectory: File,
    assistantId: String,
    private val protector: PrivateVaultKeyProtector,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = { System.nanoTime() / 1_000_000 },
    private val processNonce: String = PROCESS_NONCE,
    beforeCommit: (String) -> Unit = {},
    baseDirectoryResolver: (() -> File)? = null,
) {
    // Android's trusted application-root mapping can involve filesystem I/O. Defer it until
    // status()/guarded(), not the Compose remember block that creates a repository instance.
    private val storage by lazy {
        val directory = try { baseDirectoryResolver?.invoke() ?: baseDirectory }
        catch (error: PrivateVaultException) { throw error }
        catch (_: Exception) { throw PrivateVaultException("storage_failed") }
        PrivateVaultStorage(directory, vaultDigest(assistantId), beforeCommit)
    }

    init {
        require(assistantId.isNotBlank() && assistantId.length <= 4096) { "private_vault_invalid_owner" }
        require(processNonce.isNotBlank()) { "private_vault_invalid_process" }
    }

    fun status(): PrivateVaultStatus = try {
        // A first read does not create an empty owner directory/lock in an emergency backup.
        // A concurrent creation can make this one response stale, but cannot disclose content.
        if (!storage.exists()) PrivateVaultStatus(PrivateVaultAvailability.ABSENT) else guarded {
            when {
                !storage.exists() -> PrivateVaultStatus(PrivateVaultAvailability.ABSENT)
                storage.recoveryRequired() -> PrivateVaultStatus(PrivateVaultAvailability.RECOVERY_REQUIRED)
                else -> opened { current -> PrivateVaultStatus(PrivateVaultAvailability.READY,
                    current.state.enabled, current.state.recoveryConfirmed, current.state.records.size,
                    current.state.requests.count { effective(it) == PrivateVaultRequestStatus.PENDING }) }
            }
        }
    } catch (error: PrivateVaultException) {
        PrivateVaultStatus(if (error.reasonCode == "device_key_unavailable") PrivateVaultAvailability.RECOVERY_REQUIRED
            else PrivateVaultAvailability.UNREADABLE)
    }

    /** The returned recovery code is not retained. The room remains disabled until acknowledged. */
    fun create(): PrivateVaultCrypto.RecoveryMaterial = guarded {
        if (storage.exists()) fail("already_exists")
        val roomId = vaultId()
        val creation = PrivateVaultCrypto.create(roomId)
        creation.session.use { session ->
            val envelope = VaultEnvelope(vaultId = roomId, ownerDigest = storage.owner,
                deviceKey = "", recoveryKey = "", encryptedState = "")
            createWithId(session, envelope, creation.recovery)
        }
    }

    private fun createWithId(session: PrivateVaultCrypto.Session, envelope: VaultEnvelope,
                             recovery: PrivateVaultCrypto.RecoveryMaterial): PrivateVaultCrypto.RecoveryMaterial {
        val state = audited(VaultState(epoch = vaultId()), "created")
        val complete = envelope.copy(deviceKey = vaultBase64(session.wrapDeviceKey(protector)),
            recoveryKey = vaultBase64(recovery.wrappedKey))
        commitNewGeneration(session, complete, state, emptyMap())
        return recovery
    }

    fun confirmRecoverySaved() = guarded { opened { current ->
        commit(current, audited(current.state.copy(recoveryConfirmed = true), "recovery_saved"))
    } }

    fun setEnabled(enabled: Boolean) = guarded { opened { current ->
        if (enabled && !current.state.recoveryConfirmed) fail("recovery_not_confirmed")
        val state = current.state.copy(enabled = enabled, epoch = vaultId(),
            requests = if (!enabled) revokeAll(current.state.requests) else current.state.requests)
        commit(current, audited(state, if (enabled) "enabled" else "paused"))
    } }

    /** Null scope means a frozen set of the records and versions that exist at this instant. */
    fun requestAccess(purpose: String, recordIds: List<String>? = null,
                      accessDurationMs: Long = DEFAULT_ACCESS_MS): PrivateVaultAccessRequest = guarded {
        opened { current ->
            requireEnabled(current.state)
            requireText(purpose, 4096, allowBlank = false)
            if (accessDurationMs !in 1..MAX_ACCESS_MS) fail("invalid_duration")
            if (recordIds != null && (recordIds.isEmpty() || recordIds.size > MAX_RECORDS ||
                    recordIds.distinct().size != recordIds.size || recordIds.any { !validVaultId(it) })) fail("invalid_scope")
            val scope = recordIds?.toSet()
            val refs = current.state.records.filter { scope == null || it.id in scope }
            if (refs.isEmpty() || (scope != null && refs.size != scope.size)) fail("invalid_scope")
            val kept = current.state.requests.filter { effective(it) in setOf(PrivateVaultRequestStatus.PENDING, PrivateVaultRequestStatus.APPROVED) }
            if (kept.size >= MAX_REQUESTS) fail("request_limit")
            val request = PrivateVaultAccessRequest(vaultId(), purpose, refs.map { it.id }, now(), accessDurationMs,
                PrivateVaultRequestStatus.PENDING)
            commit(current, audited(current.state.copy(requests = kept + VaultRequest(request, refs)), "requested", request.id))
            request
        }
    }

    fun requestsForHuman(): List<PrivateVaultAccessRequest> = guarded { opened { current ->
        current.state.requests.map { it.request.copy(status = effective(it)) }
    } }

    fun auditEvents(): List<PrivateVaultAuditEvent> = guarded { opened { it.state.audit.toList() } }

    /** Poll this to clear an open viewer on expiry/revocation without appending read-audit snapshots. */
    fun isAccessGranted(requestId: String): Boolean = try { guarded { opened { current ->
        requireGrant(current, requestId)
        true
    } } } catch (_: PrivateVaultException) { false }

    fun listGranted(requestId: String): List<PrivateVaultRecordInfo> = guarded { opened { current ->
        val request = requireGrant(current, requestId)
        commit(current, audited(current.state, "human_list", requestId))
        request.refs.map { it.info() }
    } }

    fun readGranted(requestId: String, recordId: String): PrivateVaultRecord = guarded { opened { current ->
        val request = requireGrant(current, requestId)
        val ref = request.refs.singleOrNull { it.id == recordId } ?: fail("outside_scope")
        val record = readRecord(current, ref)
        // An audit failure fails closed rather than returning unrecorded plaintext.
        commit(current, audited(current.state, "human_read", requestId, recordId))
        record
    } }

    fun revokeAccess(requestId: String) = guarded { opened { current -> revoke(current, requestId, null) } }

    /** An app-internal capability. The caller must be the isolated private-model channel. */
    fun openAiSession(bindingId: String): PrivateVaultAiSession = guarded { opened { current ->
        requireEnabled(current.state)
        if (bindingId.isBlank() || bindingId.length > 4096) fail("invalid_binding")
        val digest = vaultDigest(bindingId)
        commit(current, audited(current.state, "ai_session", bindingDigest = digest))
        PrivateVaultAiSession(this, current.state.epoch, digest)
    } }

    /** New random data key and recovery code, staged together. Old backups remain decryptable. */
    fun issueRecoveryCode(): PrivateVaultCrypto.RecoveryMaterial = guarded { opened { current ->
        val newVaultId = vaultId()
        val created = PrivateVaultCrypto.create(newVaultId)
        created.session.use { newSession ->
            val generation = vaultId()
            val stateId = vaultId()
            // Re-encrypt one record at a time; never stage plaintext or the whole vault in memory.
            val refs = current.state.records.map { oldRef ->
                val record = readRecord(current, oldRef)
                val ref = oldRef.copy(version = vaultId())
                val bytes = vaultJson.encodeToString(record).toByteArray(Charsets.UTF_8)
                val ciphertext = try { newSession.encryptRecord(recordBinding(ref), bytes) } finally { bytes.fill(0) }
                storage.putRecord(generation, ref.version, ciphertext)
                ref
            }
            val state = audited(current.state.copy(enabled = false, recoveryConfirmed = false, epoch = vaultId(),
                records = refs, requests = revokeAll(current.state.requests)), "recovery_rotated")
            val envelope = VaultEnvelope(vaultId = newVaultId, ownerDigest = storage.owner,
                deviceKey = vaultBase64(newSession.wrapDeviceKey(protector)),
                recoveryKey = vaultBase64(created.recovery.wrappedKey), encryptedState = "")
            storage.commit(generation, stateId, encodeEnvelope(newSession, stateId, envelope, state), newGeneration = true)
            created.recovery
        }
    } }

    /** Portable ciphertext only. The stream is owned by the caller. Works when the device key is lost. */
    fun exportEncrypted(output: OutputStream) = guarded {
        val snapshot = storage.load()
        // If the device key is unavailable, the current generation's immutable ciphertext is still
        // exportable. Include bounded generation files; their names reveal no record titles.
        val recordDirectory = File(storage.root, "generations/${snapshot.generation}/records")
        rejectVaultLinks(recordDirectory.toPath())
        val records = if (recordDirectory.exists()) recordDirectory.listFiles()?.toList() ?: fail("storage_failed") else emptyList()
        if (records.size > MAX_BACKUP_RECORDS) fail("size_limit")
        val manifest = vaultJson.encodeToString(snapshot.envelope.copy(deviceKey = "")).toByteArray(Charsets.UTF_8)
        var total = manifest.size.toLong() + 128
        records.forEach { file ->
            if (!file.name.endsWith(".bin") || !validVaultId(file.name.removeSuffix(".bin"))) fail("invalid_format")
            rejectVaultLinks(file.toPath())
            if (!file.isFile || file.length() > MAX_CIPHER_BYTES) fail("invalid_format")
            total += file.length() + 64
        }
        if (total > MAX_BACKUP_BYTES) fail("size_limit")
        val out = DataOutputStream(output)
        out.write(BACKUP_MAGIC)
        out.writeUTF(snapshot.stateId)
        out.writeInt(manifest.size)
        out.write(manifest)
        out.writeInt(records.size)
        records.sortedBy { it.name }.forEach { file ->
            val bytes = readVaultFile(file, MAX_CIPHER_BYTES)
            if (!vaultCipherHeader(bytes, 2)) fail("invalid_format")
            out.writeUTF(file.name.removeSuffix(".bin"))
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.flush()
    }

    /** Explicit offline recovery; never runs automatically during normal app startup. */
    fun importEncrypted(input: InputStream, recoveryCode: String) = guarded {
        // First release has no destructive replace option. Existing rooms use recoverLocal instead.
        if (storage.exists()) fail("already_exists")
        val bytes = boundedVaultRead(input, MAX_BACKUP_BYTES)
        val imported = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(BACKUP_MAGIC.size).also(imported::readFully)
        if (!magic.contentEquals(BACKUP_MAGIC)) fail("invalid_format")
        val stateId = imported.readUTF()
        if (!validVaultId(stateId)) fail("invalid_format")
        val envelope = decodeVaultJson<VaultEnvelope>(readSized(imported, MAX_ENVELOPE_BYTES))
        validateVaultEnvelope(envelope, storage.owner)
        val count = imported.readInt()
        if (count !in 0..MAX_BACKUP_RECORDS) fail("invalid_format")
        val records = LinkedHashMap<String, ByteArray>()
        repeat(count) {
            val version = imported.readUTF()
            if (!validVaultId(version) || records.containsKey(version)) fail("invalid_format")
            records[version] = readSized(imported, MAX_CIPHER_BYTES)
        }
        if (imported.read() != -1) fail("invalid_format")
        recover(envelope, stateId, recoveryCode) { version -> records[version] ?: fail("missing_record") }
    }

    fun recoverLocal(recoveryCode: String) = guarded {
        val snapshot = storage.load()
        recover(snapshot.envelope, snapshot.stateId, recoveryCode) { storage.record(snapshot.generation, it) }
    }

    private fun recover(envelope: VaultEnvelope, stateId: String, code: String, record: (String) -> ByteArray) {
        PrivateVaultCrypto.recover(envelope.vaultId, code, vaultUnbase64(envelope.recoveryKey, 66)).use { session ->
            val state = decodeState(session, stateId, envelope)
            val records = LinkedHashMap<String, ByteArray>()
            // Validate every still-current record before changing CURRENT. Historical grants are
            // revoked, so their obsolete versions need not be imported into the new generation.
            state.records.forEach { ref ->
                val ciphertext = record(ref.version)
                decodeRecord(session, ref, ciphertext)
                records[ref.version] = ciphertext
            }
            val reset = audited(state.copy(enabled = false, epoch = vaultId(), recoveryConfirmed = true,
                requests = revokeAll(state.requests)), "offline_recovered")
            val rebound = envelope.copy(deviceKey = vaultBase64(session.wrapDeviceKey(protector)))
            commitNewGeneration(session, rebound, reset, records)
            storage.clearRecoveryMarker()
        }
    }

    internal fun aiList(token: PrivateVaultAiSession): List<PrivateVaultRecordInfo> = withAi(token) { current ->
        commit(current, audited(current.state, "ai_list", bindingDigest = token.bindingDigest))
        current.state.records.map { it.info() }
    }
    internal fun aiRead(token: PrivateVaultAiSession, id: String): PrivateVaultRecord = withAi(token) { current ->
        val ref = current.state.records.singleOrNull { it.id == id } ?: fail("record_missing")
        val record = readRecord(current, ref)
        commit(current, audited(current.state, "ai_read", recordId = id, bindingDigest = token.bindingDigest))
        record
    }
    internal fun aiWrite(token: PrivateVaultAiSession, title: String, body: String, id: String?): PrivateVaultRecordInfo = withAi(token) { current ->
        requireText(title, 1024, allowBlank = false)
        requireText(body, MAX_BODY_BYTES, allowBlank = true)
        val previous = if (id != null) current.state.records.singleOrNull { it.id == id } ?: fail("record_missing") else null
        if (previous == null && current.state.records.size >= MAX_RECORDS) fail("record_limit")
        val ref = VaultRecordRef(previous?.id ?: vaultId(), vaultId(), title, previous?.createdAtMs ?: now(), now())
        val record = PrivateVaultRecord(ref.id, title, body, ref.createdAtMs, ref.updatedAtMs)
        val plaintext = vaultJson.encodeToString(record).toByteArray(Charsets.UTF_8)
        val ciphertext = try { current.session.encryptRecord(recordBinding(ref), plaintext) } finally { plaintext.fill(0) }
        storage.putRecord(current.snapshot.generation, ref.version, ciphertext)
        val state = current.state.copy(records = current.state.records.filterNot { it.id == ref.id } + ref)
        commit(current, audited(state, "ai_write", recordId = ref.id, bindingDigest = token.bindingDigest))
        ref.info()
    }
    internal fun aiDelete(token: PrivateVaultAiSession, id: String) = withAi(token) { current ->
        if (current.state.records.none { it.id == id }) fail("record_missing")
        val state = current.state.copy(records = current.state.records.filterNot { it.id == id },
            requests = current.state.requests.map { if (it.refs.any { ref -> ref.id == id }) revoked(it) else it })
        commit(current, audited(state, "ai_delete", recordId = id, bindingDigest = token.bindingDigest))
    }
    internal fun aiRequests(token: PrivateVaultAiSession): List<PrivateVaultAccessRequest> = withAi(token) { current ->
        current.state.requests.filter { effective(it) == PrivateVaultRequestStatus.PENDING }.map { it.request }
    }
    internal fun aiDecide(token: PrivateVaultAiSession, requestId: String, approved: Boolean,
                          recordIds: List<String>? = null): PrivateVaultAccessRequest = withAi(token) { current ->
        val original = current.state.requests.singleOrNull { it.request.id == requestId } ?: fail("request_missing")
        if (effective(original) != PrivateVaultRequestStatus.PENDING) fail("request_not_pending")
        // The old internal two-argument API retains its frozen full scope. The AI tool requires
        // explicit IDs; never infer consent from omitted/empty/unrecognised model arguments.
        if (!approved && recordIds != null) fail("invalid_scope")
        if (recordIds != null && (recordIds.isEmpty() || recordIds.size > MAX_RECORDS ||
                recordIds.distinct().size != recordIds.size || recordIds.any { !validVaultId(it) })) fail("invalid_scope")
        val scope = recordIds?.toSet()
        val selected = if (approved && scope != null) original.refs.filter { it.id in scope } else original.refs
        if (scope != null && selected.size != scope.size) fail("outside_scope")
        if (approved && selected.any { ref -> current.state.records.none { it.id == ref.id && it.version == ref.version } }) fail("request_scope_changed")
        val wall = now()
        if (wall < 0 || wall > Long.MAX_VALUE - original.request.requestedDurationMs) fail("invalid_clock")
        val decision = original.copy(refs = selected, request = original.request.copy(recordIds = selected.map { it.id },
            status = if (approved) PrivateVaultRequestStatus.APPROVED else PrivateVaultRequestStatus.DENIED,
            expiresAtMs = if (approved) wall + original.request.requestedDurationMs else null),
            decisionProcess = processNonce, approvedAtMs = if (approved) wall else null,
            approvedElapsedMs = if (approved) elapsedNow() else null)
        commit(current, audited(current.state.copy(requests = current.state.requests.map { if (it.request.id == requestId) decision else it }),
            if (approved) "approved" else "denied", requestId, bindingDigest = token.bindingDigest))
        decision.request
    }
    internal fun aiRevoke(token: PrivateVaultAiSession, requestId: String) = withAi(token) { revoke(it, requestId, token.bindingDigest) }

    private fun revoke(current: Opened, requestId: String, binding: String?) {
        if (current.state.requests.none { it.request.id == requestId }) fail("request_missing")
        commit(current, audited(current.state.copy(requests = current.state.requests.map {
            if (it.request.id == requestId) revoked(it) else it }), "revoked", requestId, bindingDigest = binding))
    }
    private fun revoked(value: VaultRequest) = if (value.request.status in setOf(PrivateVaultRequestStatus.PENDING, PrivateVaultRequestStatus.APPROVED))
        value.copy(request = value.request.copy(status = PrivateVaultRequestStatus.REVOKED)) else value
    private fun revokeAll(requests: List<VaultRequest>) = requests.map(::revoked)

    private fun effective(value: VaultRequest): PrivateVaultRequestStatus {
        val request = value.request
        if (request.status == PrivateVaultRequestStatus.PENDING) {
            val wall = now()
            return if (wall < request.createdAtMs || wall - request.createdAtMs >= MAX_PENDING_MS) PrivateVaultRequestStatus.EXPIRED else request.status
        }
        if (request.status != PrivateVaultRequestStatus.APPROVED) return request.status
        val wall = now()
        val elapsed = elapsedNow()
        val began = value.approvedAtMs ?: return PrivateVaultRequestStatus.EXPIRED
        val startElapsed = value.approvedElapsedMs ?: return PrivateVaultRequestStatus.EXPIRED
        val until = request.expiresAtMs ?: return PrivateVaultRequestStatus.EXPIRED
        return if (value.decisionProcess != processNonce || wall < began || wall >= until || elapsed < startElapsed ||
            elapsed - startElapsed >= request.requestedDurationMs) PrivateVaultRequestStatus.EXPIRED else PrivateVaultRequestStatus.APPROVED
    }

    private fun requireGrant(current: Opened, requestId: String): VaultRequest {
        requireEnabled(current.state)
        val request = current.state.requests.singleOrNull { it.request.id == requestId } ?: fail("request_missing")
        if (effective(request) != PrivateVaultRequestStatus.APPROVED) fail("access_not_approved")
        return request
    }
    private fun requireEnabled(state: VaultState) { if (!state.enabled || !state.recoveryConfirmed) fail("room_paused") }
    private fun <T> withAi(token: PrivateVaultAiSession, action: (Opened) -> T): T = guarded { opened { current ->
        requireEnabled(current.state)
        if (token.isClosed || token.epoch != current.state.epoch) fail("session_expired")
        action(current)
    } }
    private fun readRecord(current: Opened, ref: VaultRecordRef) = decodeRecord(current.session, ref,
        storage.record(current.snapshot.generation, ref.version))
    private fun decodeRecord(session: PrivateVaultCrypto.Session, ref: VaultRecordRef, ciphertext: ByteArray): PrivateVaultRecord {
        val bytes = session.decryptRecord(recordBinding(ref), ciphertext)
        try {
            val record = decodeVaultJson<PrivateVaultRecord>(bytes)
            if (record.id != ref.id || record.title != ref.title || record.createdAtMs != ref.createdAtMs || record.updatedAtMs != ref.updatedAtMs) fail("invalid_format")
            requireText(record.title, 1024, false)
            requireText(record.body, MAX_BODY_BYTES, true)
            return record
        } finally { bytes.fill(0) }
    }
    private fun recordBinding(ref: VaultRecordRef) = "record:${ref.id}:${ref.version}"
    private fun stateBinding(id: String) = "state:$id:${storage.owner}"
    private fun decodeState(session: PrivateVaultCrypto.Session, stateId: String, envelope: VaultEnvelope): VaultState {
        val bytes = session.decryptRecord(stateBinding(stateId), vaultUnbase64(envelope.encryptedState, MAX_STATE_BYTES + 34))
        try {
            if (bytes.size > MAX_STATE_BYTES) fail("size_limit")
            val state = decodeVaultJson<VaultState>(bytes)
            validateState(state)
            return state
        } finally { bytes.fill(0) }
    }
    private fun validateState(state: VaultState) {
        if (state.version != 1 || !validVaultId(state.epoch) || state.revision < 0 || state.records.size > MAX_RECORDS ||
            state.requests.size > MAX_REQUESTS || state.audit.size > MAX_AUDIT || state.records.map { it.id }.distinct().size != state.records.size ||
            state.requests.map { it.request.id }.distinct().size != state.requests.size) fail("invalid_format")
        fun validateRef(ref: VaultRecordRef) {
            if (!validVaultId(ref.id) || !validVaultId(ref.version)) fail("invalid_format")
            requireText(ref.title, 1024, false)
        }
        state.records.forEach(::validateRef)
        state.requests.forEach { item ->
            val request = item.request
            if (!validVaultId(request.id) || item.refs.size > MAX_RECORDS || item.refs.map { it.id } != request.recordIds ||
                request.recordIds.distinct().size != request.recordIds.size || request.requestedDurationMs !in 1..MAX_ACCESS_MS) fail("invalid_format")
            requireText(request.purpose, 4096, false)
            item.refs.forEach(::validateRef)
        }
    }
    private fun commit(current: Opened, state: VaultState, envelope: VaultEnvelope = current.snapshot.envelope) {
        val id = vaultId()
        storage.commit(current.snapshot.generation, id, encodeEnvelope(current.session, id, envelope, state))
    }
    private fun commitNewGeneration(session: PrivateVaultCrypto.Session, envelope: VaultEnvelope, state: VaultState,
                                    records: Map<String, ByteArray>) {
        val generation = vaultId()
        val id = vaultId()
        records.forEach { (version, ciphertext) -> storage.putRecord(generation, version, ciphertext) }
        storage.commit(generation, id, encodeEnvelope(session, id, envelope, state), newGeneration = true)
    }
    private fun encodeEnvelope(session: PrivateVaultCrypto.Session, id: String, envelope: VaultEnvelope, state: VaultState): VaultEnvelope {
        if (state.revision == Long.MAX_VALUE) fail("size_limit")
        validateState(state)
        val bytes = vaultJson.encodeToString(state.copy(revision = state.revision + 1)).toByteArray(Charsets.UTF_8)
        try {
            if (bytes.size > MAX_STATE_BYTES) fail("size_limit")
            return envelope.copy(encryptedState = vaultBase64(session.encryptRecord(stateBinding(id), bytes)))
        } finally { bytes.fill(0) }
    }
    private fun audited(state: VaultState, action: String, requestId: String? = null, recordId: String? = null,
                        bindingDigest: String? = null) = state.copy(audit = (state.audit +
        PrivateVaultAuditEvent(vaultId(), now(), action, requestId, recordId, bindingDigest)).takeLast(MAX_AUDIT))
    private class Opened(val snapshot: VaultSnapshot, val session: PrivateVaultCrypto.Session, val state: VaultState)
    private fun <T> opened(block: (Opened) -> T): T {
        if (storage.recoveryRequired()) fail("recovery_required")
        val snapshot = storage.load()
        PrivateVaultCrypto.openDevice(snapshot.envelope.vaultId, vaultUnbase64(snapshot.envelope.deviceKey, 4096), protector).use { session ->
            return block(Opened(snapshot, session, decodeState(session, snapshot.stateId, snapshot.envelope)))
        }
    }
    private fun <T> guarded(block: () -> T): T = try { storage.locked(block) }
        catch (error: PrivateVaultException) { throw error }
        catch (_: PrivateVaultCrypto.AccessException) { fail("authentication_failed") }
        catch (_: Exception) { fail("storage_failed") }
    private fun requireText(value: String, bytes: Int, allowBlank: Boolean) {
        if (value.length > bytes || (!allowBlank && value.isBlank()) || !Charsets.UTF_8.newEncoder().canEncode(value) ||
            value.toByteArray(Charsets.UTF_8).size > bytes) fail("invalid_text")
    }
    private fun readSized(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        if (length !in 0..maximum || length > input.available()) fail("invalid_format")
        return ByteArray(length).also(input::readFully)
    }
    private fun fail(code: String): Nothing = throw PrivateVaultException(code)
    companion object {
        const val DEFAULT_ACCESS_MS = 15 * 60 * 1000L
        const val MAX_ACCESS_MS = 24 * 60 * 60 * 1000L
        private const val MAX_PENDING_MS = 24 * 60 * 60 * 1000L
        private const val MAX_REQUESTS = 128
        private const val MAX_AUDIT = 1000
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_BACKUP_RECORDS = 4096
        private val PROCESS_NONCE = vaultId()
        private val BACKUP_MAGIC = "ORPVBAK1".toByteArray(Charsets.US_ASCII)
    }
}

class PrivateVaultAiSession internal constructor(
    private val repository: PrivateVaultRepository, internal val epoch: String, internal val bindingDigest: String,
) : Closeable {
    private val closed = AtomicBoolean(false)
    internal val isClosed get() = closed.get()
    fun listRecords() = repository.aiList(this)
    fun readRecord(id: String) = repository.aiRead(this, id)
    fun writeRecord(title: String, body: String, recordId: String? = null) = repository.aiWrite(this, title, body, recordId)
    fun deleteRecord(id: String) = repository.aiDelete(this, id)
    fun pendingRequests() = repository.aiRequests(this)
    /** Explicit scope must be a nonempty subset of the original frozen request. Null is legacy full-scope consent. */
    fun decideAccess(requestId: String, approved: Boolean, recordIds: List<String>? = null) =
        repository.aiDecide(this, requestId, approved, recordIds)
    fun revokeAccess(requestId: String) = repository.aiRevoke(this, requestId)
    override fun close() { closed.set(true) }
    override fun toString() = "PrivateVaultAiSession(redacted)"
}
