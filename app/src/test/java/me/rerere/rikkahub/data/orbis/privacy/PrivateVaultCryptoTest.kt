package me.rerere.rikkahub.data.orbis.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivateVaultCryptoTest {
    private val vault = "assistant-a-vault-1"
    private val text = "synthetic-private-title\nsynthetic-private-body".toByteArray()

    @Test fun recordRoundTripDoesNotMutatePlaintext() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use { session ->
            val original = text.copyOf()
            val envelope = session.encryptRecord("entry-1", text)
            assertArrayEquals(original, text)
            assertArrayEquals(text, session.decryptRecord("entry-1", envelope))
            assertFalse(String(envelope, Charsets.ISO_8859_1).contains("synthetic-private"))
        }
    }

    @Test fun independentRecoveryRestoresWithoutOriginalSession() {
        val created = PrivateVaultCrypto.create(vault)
        val envelope = created.session.encryptRecord("entry-1", text)
        created.session.close()
        PrivateVaultCrypto.recover(vault, created.recovery.recoveryCode, created.recovery.wrappedKey).use { restored ->
            assertArrayEquals(text, restored.decryptRecord("entry-1", envelope))
        }
    }

    @Test fun samePlaintextGetsIndependentRandomNonces() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            assertFalse(session.encryptRecord("entry-1", text).contentEquals(session.encryptRecord("entry-1", text)))
        }
    }

    @Test fun recoveryMaterialIsRandomAcrossCreations() {
        val first = PrivateVaultCrypto.create(vault)
        val second = PrivateVaultCrypto.create(vault)
        try {
            assertNotEquals(first.recovery.recoveryCode, second.recovery.recoveryCode)
            assertFalse(first.recovery.wrappedKey.contentEquals(second.recovery.wrappedKey))
        } finally {
            first.session.close()
            second.session.close()
        }
    }

    @Test fun wrongRecoveryCodeFailsWithoutReturningAKey() {
        val first = PrivateVaultCrypto.create(vault)
        val second = PrivateVaultCrypto.create(vault)
        try {
            denied { PrivateVaultCrypto.recover(vault, second.recovery.recoveryCode, first.recovery.wrappedKey) }
        } finally {
            first.session.close()
            second.session.close()
        }
    }

    @Test fun wrongVaultCannotOpenRecoveryEnvelope() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use {
            denied { PrivateVaultCrypto.recover("other-vault", created.recovery.recoveryCode, created.recovery.wrappedKey) }
        }
    }

    @Test fun wrongRecordCannotOpenRecordEnvelope() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            val envelope = session.encryptRecord("entry-1", text)
            denied { session.decryptRecord("entry-2", envelope) }
        }
    }

    @Test fun anotherVaultSessionCannotReadRecord() {
        val first = PrivateVaultCrypto.create(vault)
        val second = PrivateVaultCrypto.create("other-vault")
        try {
            val envelope = first.session.encryptRecord("entry-1", text)
            denied { second.session.decryptRecord("entry-1", envelope) }
        } finally {
            first.session.close()
            second.session.close()
        }
    }

    @Test fun everyRecordByteIsAuthenticatedOrRejectedAsInvalidFormat() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            val envelope = session.encryptRecord("entry-1", text)
            for (index in envelope.indices) {
                val tampered = envelope.copyOf()
                tampered[index] = (tampered[index].toInt() xor 1).toByte()
                denied { session.decryptRecord("entry-1", tampered) }
            }
        }
    }

    @Test fun everyRecoveryEnvelopeByteIsAuthenticatedOrRejected() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use {
            val envelope = created.recovery.wrappedKey
            for (index in envelope.indices) {
                val tampered = envelope.copyOf()
                tampered[index] = (tampered[index].toInt() xor 1).toByte()
                denied { PrivateVaultCrypto.recover(vault, created.recovery.recoveryCode, tampered) }
            }
        }
    }

    @Test fun truncatedAndAppendedRecordsFailClosed() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            val envelope = session.encryptRecord("entry-1", text)
            for (size in 0 until envelope.size) denied { session.decryptRecord("entry-1", envelope.copyOf(size)) }
            denied { session.decryptRecord("entry-1", envelope + byteArrayOf(0)) }
        }
    }

    @Test fun malformedRecoveryCodesFailWithSanitizedErrors() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use {
            for (code in listOf("", "secret-human-password", "ORPV1-", "ORPV2-" + "A".repeat(43),
                "ORPV1-" + "!".repeat(43), "ORPV1-" + "A".repeat(42) + "=", "x".repeat(129))) {
                denied { PrivateVaultCrypto.recover(vault, code, created.recovery.wrappedKey) }
            }
        }
    }

    @Test fun copyingARecoveryEnvelopeDoesNotExposeMutableInternalState() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use {
            val returned = created.recovery.wrappedKey
            returned.fill(0)
            PrivateVaultCrypto.recover(vault, created.recovery.recoveryCode, created.recovery.wrappedKey).close()
        }
    }

    @Test fun sessionCopiesCallerKeyBytes() {
        val callerBytes = ByteArray(32) { it.toByte() } // Synthetic test-only key, never production initialization.
        PrivateVaultCrypto.Session(vault, callerBytes).use { session ->
            val envelope = session.encryptRecord("entry-1", text)
            callerBytes.fill(0)
            assertArrayEquals(text, session.decryptRecord("entry-1", envelope))
        }
    }

    @Test fun closeIsIdempotentAndBlocksAllSessionOperations() {
        val session = PrivateVaultCrypto.create(vault).session
        val envelope = session.encryptRecord("entry-1", text)
        session.close()
        session.close()
        denied { session.encryptRecord("entry-1", text) }
        denied { session.decryptRecord("entry-1", envelope) }
        denied { session.createRecoveryMaterial() }
    }

    @Test fun newRecoveryCodeDoesNotPretendToRevokeOldOfflineCopies() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use { session ->
            val replacement = session.createRecoveryMaterial()
            val envelope = session.encryptRecord("entry-1", text)
            assertNotEquals(created.recovery.recoveryCode, replacement.recoveryCode)
            for (material in listOf(created.recovery, replacement)) {
                PrivateVaultCrypto.recover(vault, material.recoveryCode, material.wrappedKey).use { restored ->
                    assertArrayEquals(text, restored.decryptRecord("entry-1", envelope))
                }
            }
            denied { PrivateVaultCrypto.recover(vault, replacement.recoveryCode, created.recovery.wrappedKey) }
        }
    }

    @Test fun recordAndKeyEnvelopeCannotBeSubstituted() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use { session ->
            val envelope = session.encryptRecord("entry-1", ByteArray(32))
            denied { session.decryptRecord("entry-1", created.recovery.wrappedKey) }
            denied { PrivateVaultCrypto.recover(vault, created.recovery.recoveryCode, envelope) }
        }
    }

    @Test fun identifiersHaveBoundedUnambiguousEncoding() {
        for (id in listOf("", " ", "a\nb", "x".repeat(257), "界".repeat(86), "\uD800")) {
            denied { PrivateVaultCrypto.create(id) }
        }
        PrivateVaultCrypto.create(vault).session.use { session ->
            denied { session.encryptRecord("", text) }
            denied { session.decryptRecord("x".repeat(257), byteArrayOf()) }
        }
    }

    @Test fun plaintextAndEnvelopeLengthsAreBounded() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            denied { session.encryptRecord("entry-1", ByteArray(PrivateVaultCrypto.MAX_RECORD_BYTES + 1)) }
            denied { session.decryptRecord("entry-1", ByteArray(PrivateVaultCrypto.MAX_RECORD_BYTES + 35)) }
        }
    }

    @Test fun emptyRecordIsStillAuthenticated() {
        PrivateVaultCrypto.create(vault).session.use { session ->
            val envelope = session.encryptRecord("entry-1", byteArrayOf())
            assertArrayEquals(byteArrayOf(), session.decryptRecord("entry-1", envelope))
            denied { session.decryptRecord("entry-2", envelope) }
        }
    }

    @Test fun diagnosticStringsDoNotContainSecretsOrIdentifiers() {
        val created = PrivateVaultCrypto.create(vault)
        created.session.use {
            val descriptions = listOf(created.toString(), created.session.toString(), created.recovery.toString())
            for (description in descriptions) {
                assertFalse(description.contains(vault))
                assertFalse(description.contains(created.recovery.recoveryCode))
                assertTrue(description.contains("redacted"))
            }
        }
    }

    private fun denied(block: () -> Unit) {
        try {
            block()
            fail("Expected private vault access to fail")
        } catch (failure: PrivateVaultCrypto.AccessException) {
            assertEquals("private_vault_access_failed", failure.message)
            assertNull(failure.cause)
        }
    }
}
