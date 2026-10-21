package me.rerere.rikkahub.data.orbis.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivateVaultPasswordCryptoTest {
    private val password = " 合成中文密码!é123 "
    private val owner = "synthetic-password-vault"

    @Test fun passwordRoundTripPreservesUnicodeAndSpacesExactly() {
        PrivateVaultCrypto.createSession(owner).use { original ->
            val wrapped = original.createPasswordRecovery(password.toCharArray())
            val ciphertext = original.encryptRecord("record", "synthetic-body".toByteArray())
            PrivateVaultCrypto.recoverWithPassword(owner, password.toCharArray(), wrapped).use { restored ->
                assertEquals("synthetic-body", restored.decryptRecord("record", ciphertext).toString(Charsets.UTF_8))
            }
            denied { PrivateVaultCrypto.recoverWithPassword(owner, password.trim().toCharArray(), wrapped) }
            denied { PrivateVaultCrypto.recoverWithPassword(owner, password.replace("é", "e\u0301").toCharArray(), wrapped) }
        }
    }

    @Test fun independentRandomSaltAndNoncePreventDeterministicWrapping() {
        PrivateVaultCrypto.createSession(owner).use { session ->
            val first = session.createPasswordRecovery(password.toCharArray())
            val second = session.createPasswordRecovery(password.toCharArray())
            assertNotEquals(first.salt, second.salt)
            assertNotEquals(first.wrappedKey, second.wrappedKey)
            assertEquals(16, vaultUnbase64(first.salt, 32).size)
            assertEquals(600_000, first.iterations)
            assertFalse(first.toString().contains(first.wrappedKey))
            assertFalse(first.toString().contains(password))
        }
    }

    @Test fun wrongPasswordOwnerAndTamperedTagFailClosed() {
        PrivateVaultCrypto.createSession(owner).use { session ->
            val recovery = session.createPasswordRecovery(password.toCharArray())
            denied { PrivateVaultCrypto.recoverWithPassword(owner, "wrong-password".toCharArray(), recovery) }
            denied { PrivateVaultCrypto.recoverWithPassword("other-owner", password.toCharArray(), recovery) }
            val modified = vaultUnbase64(recovery.wrappedKey, 66).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            denied { PrivateVaultCrypto.recoverWithPassword(owner, password.toCharArray(), recovery.copy(wrappedKey = vaultBase64(modified))) }
        }
    }

    @Test fun maliciousKdfParametersAreRejectedBeforePasswordWork() {
        val header = byteArrayOf(0x4f, 0x52, 0x50, 0x56, 1, 3) + ByteArray(60)
        val valid = VaultPasswordRecovery(salt = vaultBase64(ByteArray(16)), wrappedKey = vaultBase64(header))
        val bad = listOf(valid.copy(version = 2), valid.copy(kdf = "PBKDF2-HMAC-SHA1"),
            valid.copy(iterations = 1), valid.copy(iterations = Int.MAX_VALUE),
            valid.copy(salt = vaultBase64(ByteArray(15))), valid.copy(salt = vaultBase64(ByteArray(33))),
            valid.copy(wrappedKey = vaultBase64(header.copyOf(65))),
            valid.copy(wrappedKey = vaultBase64(header.copyOf().also { it[5] = 1 })))
        bad.forEach { descriptor ->
            try {
                // Invalid password would produce AccessException if the KDF path were reached.
                PrivateVaultCrypto.recoverWithPassword(owner, charArrayOf(), descriptor)
                fail("Unbounded or ambiguous KDF accepted")
            } catch (error: PrivateVaultException) { assertEquals("invalid_format", error.reasonCode) }
        }
    }

    @Test fun passwordAndLegacyWrappersCannotBeConfused() {
        val legacy = PrivateVaultCrypto.create(owner)
        legacy.session.use { session ->
            val passwordWrapper = session.createPasswordRecovery(password.toCharArray())
            denied { PrivateVaultCrypto.recover(owner, legacy.recovery.recoveryCode, vaultUnbase64(passwordWrapper.wrappedKey, 66)) }
            try {
                PrivateVaultCrypto.recoverWithPassword(owner, password.toCharArray(),
                    passwordWrapper.copy(wrappedKey = vaultBase64(legacy.recovery.wrappedKey)))
                fail("Legacy wrapper accepted as password wrapper")
            } catch (error: PrivateVaultException) { assertEquals("invalid_format", error.reasonCode) }
        }
    }

    private fun denied(action: () -> Unit) {
        try { action(); fail("Expected authentication failure") }
        catch (_: PrivateVaultCrypto.AccessException) { /* no key returned */ }
    }
}
