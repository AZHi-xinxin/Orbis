package me.rerere.rikkahub.data.orbis.privacy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitive, NOT an authorization boundary by itself.
 *
 * Callers must keep records, plaintext, recovery codes and session keys out of ordinary chat,
 * request logs, tool receipts, searchable indexes and public file providers. Human approval,
 * Android key custody, private model transport and atomic encrypted storage are caller responsibilities.
 * This class neither writes files nor opens a room automatically.
 *
 * Recovery codes are randomly generated 256-bit secrets, not human passwords. A person holding
 * a code AND its matching recovery envelope can recover the data key independently of the UI.
 * Replacing the recovery envelope does not revoke older exported envelopes or their codes.
 */
object PrivateVaultCrypto {
    const val MAX_RECORD_BYTES = 16 * 1024 * 1024
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_IDENTIFIER_BYTES = 256
    private const val WRAPPED_KEY_KIND: Byte = 1
    private const val RECORD_KIND: Byte = 2
    private const val CODE_PREFIX = "ORPV1-"
    private val PREFIX = byteArrayOf(0x4f, 0x52, 0x50, 0x56, 1) // ORPV, format version 1
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val headerBytes = PREFIX.size + 1 + NONCE_BYTES
    private val wrappedKeyBytes = headerBytes + KEY_BYTES + TAG_BYTES

    class Creation internal constructor(val session: Session, val recovery: RecoveryMaterial) {
        override fun toString() = "PrivateVaultCreation(redacted)"
    }

    class RecoveryMaterial internal constructor(val recoveryCode: String, wrappedKey: ByteArray) {
        private val envelope = wrappedKey.copyOf()
        val wrappedKey: ByteArray get() = envelope.copyOf()
        override fun toString() = "PrivateVaultRecoveryMaterial(redacted)"
    }

    class AccessException internal constructor() : IllegalArgumentException("private_vault_access_failed")

    /** Mutable key storage is copied on entry; close releases the held bytes on a best-effort basis. */
    class Session internal constructor(private val vaultId: String, key: ByteArray) : Closeable {
        private val lock = Any()
        private val secret = key.copyOf()
        private var closed = false

        init {
            requireIdentifier(vaultId)
            if (secret.size != KEY_BYTES) {
                secret.fill(0)
                throw AccessException()
            }
        }

        fun encryptRecord(recordId: String, plaintext: ByteArray): ByteArray = synchronized(lock) {
            requireOpen()
            requireIdentifier(recordId)
            if (plaintext.size > MAX_RECORD_BYTES) throw AccessException()
            encrypt(secret, RECORD_KIND, vaultId, recordId, plaintext)
        }

        fun decryptRecord(recordId: String, envelope: ByteArray): ByteArray = synchronized(lock) {
            requireOpen()
            requireIdentifier(recordId)
            decrypt(secret, RECORD_KIND, vaultId, recordId, envelope, MAX_RECORD_BYTES)
        }

        /** Produces a new recovery wrapper, but does not invalidate any previous exported wrapper. */
        fun createRecoveryMaterial(): RecoveryMaterial = synchronized(lock) {
            requireOpen()
            recovery(secret, vaultId)
        }

        internal fun wrapDeviceKey(protector: PrivateVaultKeyProtector): ByteArray = synchronized(lock) {
            requireOpen()
            val copy = secret.copyOf()
            try { protector.wrap(vaultId, copy) } finally { copy.fill(0) }
        }

        private fun requireOpen() {
            if (closed) throw AccessException()
        }

        override fun close() = synchronized(lock) {
            secret.fill(0)
            closed = true
        }

        override fun toString() = "PrivateVaultSession(redacted)"
    }

    fun create(vaultId: String): Creation {
        requireIdentifier(vaultId)
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        var session: Session? = null
        try {
            session = Session(vaultId, key)
            return Creation(session, recovery(key, vaultId))
        } catch (failure: Throwable) {
            session?.close()
            throw failure
        } finally {
            key.fill(0)
        }
    }

    /** Explicit offline recovery only. This method does not implement an AI approval policy. */
    fun recover(vaultId: String, recoveryCode: String, wrappedKey: ByteArray): Session {
        requireIdentifier(vaultId)
        if (wrappedKey.size != wrappedKeyBytes) throw AccessException()
        val recoveryKey = decodeRecoveryCode(recoveryCode)
        try {
            val key = decrypt(recoveryKey, WRAPPED_KEY_KIND, vaultId, "", wrappedKey, KEY_BYTES)
            try {
                if (key.size != KEY_BYTES) throw AccessException()
                return Session(vaultId, key)
            } finally {
                key.fill(0)
            }
        } finally {
            recoveryKey.fill(0)
        }
    }

    internal fun openDevice(vaultId: String, wrappedKey: ByteArray, protector: PrivateVaultKeyProtector): Session {
        requireIdentifier(vaultId)
        val key = protector.unwrap(vaultId, wrappedKey)
        try { return Session(vaultId, key) } finally { key.fill(0) }
    }

    private fun recovery(key: ByteArray, vaultId: String): RecoveryMaterial {
        val recoveryKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            return RecoveryMaterial(
                CODE_PREFIX + encoder.encodeToString(recoveryKey),
                encrypt(recoveryKey, WRAPPED_KEY_KIND, vaultId, "", key),
            )
        } finally {
            recoveryKey.fill(0)
        }
    }

    private fun decodeRecoveryCode(code: String): ByteArray {
        // Bound work before trimming or decoding attacker-controlled input.
        if (code.length > 128) throw AccessException()
        val clean = code.trim()
        if (!clean.startsWith(CODE_PREFIX) || clean.length != CODE_PREFIX.length + 43) throw AccessException()
        val encoded = clean.substring(CODE_PREFIX.length)
        val bytes = try {
            decoder.decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw AccessException()
        }
        if (bytes.size != KEY_BYTES || encoder.encodeToString(bytes) != encoded) {
            bytes.fill(0)
            throw AccessException()
        }
        return bytes
    }

    private fun requireIdentifier(value: String) {
        if (value.isBlank() || value.length > MAX_IDENTIFIER_BYTES ||
            !Charsets.UTF_8.newEncoder().canEncode(value) ||
            value.toByteArray(Charsets.UTF_8).size > MAX_IDENTIFIER_BYTES || value.any { it.isISOControl() }) {
            throw AccessException()
        }
    }

    private fun associatedData(header: ByteArray, vaultId: String, recordId: String): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(header)
                // Length framing prevents ambiguous identity concatenation.
                for (identity in listOf(vaultId, recordId)) {
                    val bytes = identity.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            buffer.toByteArray()
        }

    private fun encrypt(key: ByteArray, kind: Byte, vaultId: String, recordId: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = PREFIX + byteArrayOf(kind) + nonce
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(associatedData(header, vaultId, recordId))
            return header + cipher.doFinal(plaintext)
        } catch (_: GeneralSecurityException) {
            throw AccessException()
        }
    }

    private fun decrypt(key: ByteArray, kind: Byte, vaultId: String, recordId: String,
                        envelope: ByteArray, maxPlaintextBytes: Int): ByteArray {
        if (envelope.size < headerBytes + TAG_BYTES ||
            envelope.size.toLong() > headerBytes.toLong() + TAG_BYTES + maxPlaintextBytes ||
            PREFIX.indices.any { envelope[it] != PREFIX[it] } || envelope[PREFIX.size] != kind) {
            throw AccessException()
        }
        val header = envelope.copyOfRange(0, headerBytes)
        val nonce = header.copyOfRange(PREFIX.size + 1, headerBytes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(associatedData(header, vaultId, recordId))
            // No unauthenticated plaintext is returned before the authentication tag is checked.
            return cipher.doFinal(envelope, headerBytes, envelope.size - headerBytes)
        } catch (_: GeneralSecurityException) {
            throw AccessException()
        }
    }
}
