package me.rerere.rikkahub.data.orbis.privacy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PrivateVaultPasswordRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val protector = TestProtector()
    private val owner = "synthetic-password-owner"
    private val password = " 合成恢复密码!é123 "
    private fun repo(base: File = temporary.root, key: PrivateVaultKeyProtector = protector,
                     assistant: String = owner, before: (String) -> Unit = {}) =
        PrivateVaultRepository(base, assistant, key, { 1_000_000L }, { 10_000L }, "synthetic-process", before)
    private fun ready(repository: PrivateVaultRepository = repo()): PrivateVaultRepository {
        repository.createWithRecoveryPassword(password.toCharArray())
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        return repository
    }
    private fun backup(repository: PrivateVaultRepository) = ByteArrayOutputStream().also(repository::exportEncrypted).toByteArray()
    private fun storage(base: File = temporary.root) = PrivateVaultStorage(base, vaultDigest(owner)) {}
    private fun snapshot() = storage().locked { storage().load() }
    private fun pointers(): Pair<String, String> = snapshot().let { it.generation to it.stateId }
    private fun denied(code: String, action: () -> Unit) {
        try { action(); fail("Expected fail-closed") } catch (error: PrivateVaultException) {
            assertEquals(code, error.reasonCode)
            assertNull(error.cause)
        }
    }
    private fun restoredBody(repository: PrivateVaultRepository): String {
        repository.setEnabled(true)
        return repository.openAiSession("restored").use { ai -> ai.readRecord(ai.listRecords().single().id).body }
    }

    @Test fun passwordOnlyCreationRequiresAcknowledgementAndWipesCallerBuffer() {
        val repository = repo()
        val input = password.toCharArray()
        repository.createWithRecoveryPassword(input)
        assertTrue(input.all { it == '\u0000' })
        val status = repository.status()
        assertEquals(PrivateVaultAvailability.READY, status.availability)
        assertTrue(status.hasRecoveryPassword)
        assertFalse(status.hasRecoveryCode)
        assertFalse(status.enabled)
        assertFalse(status.recoveryConfirmed)
        denied("recovery_not_confirmed") { repository.setEnabled(true) }
        assertEquals(2, snapshot().envelope.version)
        assertTrue(validateEmergencyVaultCopy(File(temporary.root, vaultDigest(owner))))
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        assertTrue(repository.status().enabled)
    }

    @Test fun passwordBackupRestoresWithIndependentDeviceKeyAndNoPlaintextOnDisk() {
        val repository = ready()
        repository.openAiSession("private").use { it.writeRecord("SYNTHETIC-SECRET-TITLE", "SYNTHETIC-SECRET-BODY") }
        val exported = backup(repository)
        val restored = repo(temporary.newFolder("restored"), TestProtector())
        val input = password.toCharArray()
        restored.importEncryptedWithPassword(ByteArrayInputStream(exported), input)
        assertTrue(input.all { it == '\u0000' })
        assertFalse(restored.status().enabled)
        assertTrue(restored.status().recoveryConfirmed)
        assertEquals("SYNTHETIC-SECRET-BODY", restoredBody(restored))
        val forbidden = listOf(password, "SYNTHETIC-SECRET-TITLE", "SYNTHETIC-SECRET-BODY")
        val raw = exported.toString(Charsets.UTF_8)
        forbidden.forEach { assertFalse(raw.contains(it)) }
        temporary.root.walkTopDown().filter { it.isFile }.forEach { file ->
            forbidden.forEach { assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(it)) }
        }
    }

    @Test fun legacyRoomKeepsV1WireFormatAndRejectsPasswordRecoveryWithoutMutation() {
        val repository = repo()
        val code = repository.create().recoveryCode
        val initial = snapshot()
        assertEquals(1, initial.envelope.version)
        assertFalse(vaultJson.parseToJsonElement(vaultJson.encodeToString(initial.envelope)).jsonObject.containsKey("passwordRecovery"))
        assertTrue(repository.status().hasRecoveryCode)
        assertFalse(repository.status().hasRecoveryPassword)
        denied("password_not_configured") { repository.recoverLocalWithPassword(password.toCharArray()) }
        assertEquals(initial, snapshot())
        val destination = repo(temporary.newFolder("legacy-copy"), TestProtector())
        val exported = backup(repository)
        denied("password_not_configured") { destination.importEncryptedWithPassword(ByteArrayInputStream(exported), password.toCharArray()) }
        assertEquals(PrivateVaultAvailability.ABSENT, destination.status().availability)
        destination.importEncrypted(ByteArrayInputStream(exported), code)
        assertEquals(PrivateVaultAvailability.READY, destination.status().availability)
    }

    @Test fun settingPasswordKeepsLegacyCodeContentSessionAndHumanGrant() {
        val repository = repo()
        val oldCode = repository.create().recoveryCode
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        repository.openAiSession("private").use { ai ->
            val record = ai.writeRecord("title", "unchanged-body")
            val request = repository.requestAccess("synthetic-read", listOf(record.id))
            ai.decideAccess(request.id, true)
            val before = snapshot()
            repository.setRecoveryPassword(password.toCharArray())
            assertEquals(before.envelope.vaultId, snapshot().envelope.vaultId)
            assertEquals(before.envelope.recoveryKey, snapshot().envelope.recoveryKey)
            assertTrue(repository.status().enabled)
            assertTrue(repository.status().recoveryConfirmed)
            assertTrue(repository.status().hasRecoveryCode)
            assertTrue(repository.isAccessGranted(request.id))
            assertEquals("unchanged-body", ai.readRecord(record.id).body)
            assertEquals("unchanged-body", repository.readGranted(request.id, record.id).body)
        }
        val exported = backup(repository)
        val byCode = repo(temporary.newFolder("legacy-code"), TestProtector())
        byCode.importEncrypted(ByteArrayInputStream(exported), oldCode)
        assertEquals("unchanged-body", restoredBody(byCode))
        val byPassword = repo(temporary.newFolder("new-password"), TestProtector())
        byPassword.importEncryptedWithPassword(ByteArrayInputStream(exported), password.toCharArray())
        assertEquals("unchanged-body", restoredBody(byPassword))
    }

    @Test fun passwordChangeRetainsContentAndOldExportsButNotOldPasswordOnNewExport() {
        val repository = ready()
        repository.openAiSession("private").use { it.writeRecord("title", "retained-body") }
        val oldExport = backup(repository)
        val newPassword = "new-synthetic-password"
        repository.setRecoveryPassword(newPassword.toCharArray())
        val exported = backup(repository)
        val destination = repo(temporary.newFolder("new-copy"), TestProtector())
        denied("authentication_failed") { destination.importEncryptedWithPassword(ByteArrayInputStream(exported), password.toCharArray()) }
        assertEquals(PrivateVaultAvailability.ABSENT, destination.status().availability)
        destination.importEncryptedWithPassword(ByteArrayInputStream(exported), newPassword.toCharArray())
        assertEquals("retained-body", restoredBody(destination))
        val older = repo(temporary.newFolder("old-copy"), TestProtector())
        older.importEncryptedWithPassword(ByteArrayInputStream(oldExport), password.toCharArray())
        assertEquals("retained-body", restoredBody(older))
    }

    @Test fun wrongPasswordNeverChangesCurrentOrHeadAndCannotOverwriteExistingRoom() {
        val repository = ready()
        val original = pointers()
        val input = "wrong-password".toCharArray()
        denied("authentication_failed") { repository.recoverLocalWithPassword(input) }
        assertTrue(input.all { it == '\u0000' })
        assertEquals(original, pointers())
        val input2 = password.toCharArray()
        denied("already_exists") { repository.importEncryptedWithPassword(ByteArrayInputStream(backup(repository)), input2) }
        assertTrue(input2.all { it == '\u0000' })
        assertEquals(original, pointers())
    }

    @Test fun corruptedCurrentRecordIsAuthenticatedBeforePasswordRecoveryCommit() {
        val repository = ready()
        repository.openAiSession("private").use { it.writeRecord("title", "body") }
        val before = pointers()
        val records = File(storage().root, "generations/${before.first}/records")
        val record = records.listFiles()!!.single()
        val valid = record.readBytes()
        record.writeBytes(valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        denied("authentication_failed") { repository.recoverLocalWithPassword(password.toCharArray()) }
        assertEquals(before, pointers())
        val destination = repo(temporary.newFolder("corrupt-copy"), TestProtector())
        denied("authentication_failed") { destination.importEncryptedWithPassword(ByteArrayInputStream(backup(repository)), password.toCharArray()) }
        assertEquals(PrivateVaultAvailability.ABSENT, destination.status().availability)
        record.writeBytes(valid)
        assertEquals("body", repository.openAiSession("private").use { it.readRecord(it.listRecords().single().id).body })
    }

    @Test fun lostDeviceKeyCannotSetPasswordButCanExplicitlyRecoverWithPassword() {
        val repository = ready()
        repository.openAiSession("private").use { it.writeRecord("title", "body") }
        val before = pointers()
        val lost = repo(key = TestProtector())
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, lost.status().availability)
        assertTrue(lost.status().hasRecoveryPassword)
        assertFalse(lost.status().hasRecoveryCode)
        val input = "replacement-password".toCharArray()
        denied("device_key_unavailable") { lost.setRecoveryPassword(input) }
        assertTrue(input.all { it == '\u0000' })
        assertEquals(before, pointers())
        lost.recoverLocalWithPassword(password.toCharArray())
        assertEquals("body", restoredBody(lost))
    }

    @Test fun recoveryMarkerRequiresExplicitCredentialAndReportsBothRecoveryModes() {
        val repository = repo()
        repository.create()
        repository.setRecoveryPassword(password.toCharArray())
        File(storage().root, "recovery-required.marker").writeText("1")
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, repository.status().availability)
        assertTrue(repository.status().hasRecoveryPassword)
        assertTrue(repository.status().hasRecoveryCode)
        val before = pointers()
        denied("recovery_required") { repository.setRecoveryPassword("replacement-password".toCharArray()) }
        assertEquals(before, pointers())
        repository.recoverLocalWithPassword(password.toCharArray())
        assertEquals(PrivateVaultAvailability.READY, repository.status().availability)
        assertFalse(File(storage().root, "recovery-required.marker").exists())
    }

    @Test fun ownerBindingRejectsCrossAssistantPasswordImport() {
        val repository = ready()
        val other = repo(temporary.newFolder("other-assistant"), TestProtector(), assistant = "different-owner")
        denied("invalid_format") { other.importEncryptedWithPassword(ByteArrayInputStream(backup(repository)), password.toCharArray()) }
        assertEquals(PrivateVaultAvailability.ABSENT, other.status().availability)
    }

    @Test fun newBackupCodePreservesPasswordAndRequiresReconfirmationRevokingGrants() {
        val repository = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val request = repository.requestAccess("read", listOf(record.id))
        ai.decideAccess(request.id, true)
        val before = snapshot().envelope
        val recovery = repository.issueRecoveryCode()
        val after = snapshot().envelope
        assertEquals(before.vaultId, after.vaultId)
        assertEquals(before.passwordRecovery, after.passwordRecovery)
        assertTrue(repository.status().hasRecoveryCode)
        assertTrue(repository.status().hasRecoveryPassword)
        assertFalse(repository.status().enabled)
        assertFalse(repository.status().recoveryConfirmed)
        assertFalse(repository.isAccessGranted(request.id))
        denied("room_paused") { ai.listRecords() }
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        denied("session_expired") { ai.listRecords() }
        ai.close()
        val exported = backup(repository)
        val byCode = repo(temporary.newFolder("by-code"), TestProtector())
        byCode.importEncrypted(ByteArrayInputStream(exported), recovery.recoveryCode)
        assertEquals("body", restoredBody(byCode))
        val byPassword = repo(temporary.newFolder("by-password"), TestProtector())
        byPassword.importEncryptedWithPassword(ByteArrayInputStream(exported), password.toCharArray())
        assertEquals("body", restoredBody(byPassword))
    }

    @Test fun failedPasswordChangeCommitLeavesPreviousPasswordAndStateCurrent() {
        var failing = false
        val repository = ready(repo(before = { if (failing && it == "head") error("synthetic-failure") }))
        val before = pointers()
        failing = true
        val input = "new-synthetic-password".toCharArray()
        denied("storage_failed") { repository.setRecoveryPassword(input) }
        assertTrue(input.all { it == '\u0000' })
        assertEquals(before, pointers())
        assertTrue(repository.status().enabled)
        failing = false
        repository.recoverLocalWithPassword(password.toCharArray())
        assertEquals(PrivateVaultAvailability.READY, repository.status().availability)
    }

    @Test fun invalidCreationAndSetInputsAreWipedAndNeverCreateOrChangeRoom() {
        val repository = repo()
        listOf(CharArray(0), CharArray(7) { 'a' }, CharArray(129) { 'a' }, "abcdefg\uD800".toCharArray()).forEach { input ->
            denied("password_invalid_length") { repository.createWithRecoveryPassword(input) }
            assertTrue(input.all { it == '\u0000' })
            assertEquals(PrivateVaultAvailability.ABSENT, repository.status().availability)
        }
        ready(repository)
        val before = pointers()
        val bad = "short".toCharArray()
        denied("password_invalid_length") { repository.setRecoveryPassword(bad) }
        assertTrue(bad.all { it == '\u0000' })
        val exists = password.toCharArray()
        denied("already_exists") { repository.createWithRecoveryPassword(exists) }
        assertTrue(exists.all { it == '\u0000' })
        assertEquals(before, pointers())
    }

    @Test fun unsupportedOrDowngradedEnvelopeAndExcessiveKdfFailWithoutCommit() {
        val repository = ready()
        val exported = backup(repository)
        val changes: List<(VaultEnvelope) -> VaultEnvelope> = listOf(
            { it.copy(version = 3) }, { it.copy(version = 1) }, { it.copy(passwordRecovery = null) },
            { it.copy(passwordRecovery = it.passwordRecovery!!.copy(iterations = Int.MAX_VALUE)) })
        changes.forEachIndexed { index, change ->
            val destination = repo(temporary.newFolder("malformed-$index"), TestProtector())
            val passwordInput = password.toCharArray()
            denied("invalid_format") {
                destination.importEncryptedWithPassword(ByteArrayInputStream(rewriteEnvelope(exported, change)), passwordInput)
            }
            assertTrue(passwordInput.all { it == '\u0000' })
            assertEquals(PrivateVaultAvailability.ABSENT, destination.status().availability)
        }
    }

    @Test fun exactMinimumAndMaximumPasswordLengthsAreAcceptedWithoutTruncation() {
        for (length in listOf(8, 128)) {
            val repository = repo(temporary.newFolder("length-$length"))
            val input = CharArray(length) { if (it == length - 1) '中' else '!' }
            val recovery = input.copyOf()
            repository.createWithRecoveryPassword(input)
            assertTrue(input.all { it == '\u0000' })
            repository.recoverLocalWithPassword(recovery)
            assertTrue(recovery.all { it == '\u0000' })
            assertEquals(PrivateVaultAvailability.READY, repository.status().availability)
        }
    }

    private fun rewriteEnvelope(backup: ByteArray, transform: (VaultEnvelope) -> VaultEnvelope): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(backup))
        val magic = ByteArray(8).also(input::readFully)
        val stateId = input.readUTF()
        val envelope = decodeVaultJson<VaultEnvelope>(ByteArray(input.readInt()).also(input::readFully))
        val replacement = vaultJson.encodeToString(transform(envelope)).toByteArray(Charsets.UTF_8)
        val rest = input.readBytes()
        return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.write(magic); output.writeUTF(stateId); output.writeInt(replacement.size); output.write(replacement); output.write(rest)
        } }.toByteArray()
    }

    private class TestProtector : PrivateVaultKeyProtector {
        private val random = SecureRandom()
        private val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(vaultId.toByteArray())
            return nonce + cipher.doFinal(dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrappedKey.copyOfRange(0, 12)))
            cipher.updateAAD(vaultId.toByteArray())
            cipher.doFinal(wrappedKey, 12, wrappedKey.size - 12)
        } catch (_: Exception) { throw PrivateVaultException("device_key_unavailable") }
    }
}
