package me.rerere.rikkahub.data.orbis.privacy

import android.app.Application
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Real Android PBKDF2/Keystore; random synthetic cache roots and aliases only. */
@RunWith(AndroidJUnit4::class)
class PrivateRoomRecoveryPasswordDeviceTest {
    private lateinit var root: File
    private lateinit var cache: File
    private val aliases = linkedSetOf<String>()
    private val password = " ORPV1-合成恢复口令  " // A password is not guessed from its prefix or trimmed.

    @Before fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
        cache = inst.targetContext.cacheDir.canonicalFile
        root = Files.createTempDirectory(cache.toPath(), "private-password-device-").toFile().canonicalFile
        check(root.parentFile == cache)
    }

    @After fun tearDown() {
        try {
            val store = keyStore()
            aliases.forEach { alias ->
                check(alias.matches(Regex("orbis-private-vault-v1-[0-9a-f]{64}")))
                store.deleteEntry(alias)
            }
        } finally {
            if (::root.isInitialized) {
                check(root.canonicalFile.parentFile == cache && root.name.startsWith("private-password-device-"))
                check(root.deleteRecursively())
            }
        }
    }

    @Test fun passwordExportRestoresUsingNewProtectorAndDirectoryWithActualAndroidCrypto() {
        val source = fixture("source", withPassword = true)
        val before = hashes(source.directory)
        val backup = export(source.repo)
        assertEquals(2, envelope(backup).version)
        assertEquals("", envelope(backup).deviceKey)
        assertTrue(source.repo.status().hasRecoveryPassword)
        assertFalse(source.repo.status().hasRecoveryCode)
        val oldAlias = alias(envelope(backup).vaultId)
        check(oldAlias in aliases)
        keyStore().deleteEntry(oldAlias)
        assertFalse(keyStore().containsAlias(oldAlias))
        val replacementProtector = TrackedProtector()
        assertNotSame(source.protector.actual, replacementProtector.actual)
        val restoredDirectory = File(root, "fresh-device")
        assertFalse(restoredDirectory.exists())
        val restored = repository(restoredDirectory, source.owner, replacementProtector)
        val input = password.toCharArray()
        restored.importEncryptedWithPassword(ByteArrayInputStream(backup), input)
        assertCleared(input)
        assertRecovered(restored, source)
        assertEquals(before, hashes(source.directory))
        assertCipherOnly(source.directory, backup, source)
        assertCipherOnly(restoredDirectory, export(restored), source)
    }

    @Test fun missingRealDeviceKeyRequiresExplicitPasswordRecoveryAndWrongPasswordPreservesFiles() {
        val source = fixture("lost-key", withPassword = true)
        val alias = alias(envelope(export(source.repo)).vaultId)
        check(alias in aliases)
        keyStore().deleteEntry(alias)
        val before = hashes(source.directory)
        val reopened = repository(source.directory, source.owner, TrackedProtector())
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, reopened.status().availability)
        assertTrue(reopened.status().hasRecoveryPassword)
        val wrong = password.trim().toCharArray()
        denied("authentication_failed") { reopened.recoverLocalWithPassword(wrong) }
        assertCleared(wrong)
        assertFalse(keyStore().containsAlias(alias))
        assertEquals(before, hashes(source.directory))
        val right = password.toCharArray()
        reopened.recoverLocalWithPassword(right)
        assertCleared(right)
        assertTrue(keyStore().containsAlias(alias))
        assertRecovered(reopened, source)
    }

    @Test fun oldV1CodeBackupStillRestoresAndPasswordApiDoesNotSilentlyUseThatCode() {
        val source = fixture("old-code", withPassword = false)
        val backup = export(source.repo)
        assertEquals(1, envelope(backup).version)
        val restored = repository(File(root, "old-restored"), source.owner, TrackedProtector())
        val codeAsPassword = source.code!!.toCharArray()
        denied("password_not_configured") {
            restored.importEncryptedWithPassword(ByteArrayInputStream(backup), codeAsPassword)
        }
        assertCleared(codeAsPassword)
        assertEquals(PrivateVaultAvailability.ABSENT, restored.status().availability)
        restored.importEncrypted(ByteArrayInputStream(backup), source.code)
        assertRecovered(restored, source)
        assertTrue(restored.status().hasRecoveryCode)
        assertFalse(restored.status().hasRecoveryPassword)
    }

    @Test fun passwordOnlyBackupRejectsRandomCodeAndIncorrectPasswordWithoutInstallingAnEmptyRoom() {
        val source = fixture("password-only", withPassword = true)
        val oldCode = fixture("unrelated-code", withPassword = false).code!!
        val backup = export(source.repo)
        val before = hashes(source.directory)
        val restored = repository(File(root, "rejected-import"), source.owner, TrackedProtector())
        denied { restored.importEncrypted(ByteArrayInputStream(backup), oldCode) }
        assertEquals(PrivateVaultAvailability.ABSENT, restored.status().availability)
        val wrong = "synthetic-wrong-password".toCharArray()
        denied("authentication_failed") { restored.importEncryptedWithPassword(ByteArrayInputStream(backup), wrong) }
        assertCleared(wrong)
        assertEquals(PrivateVaultAvailability.ABSENT, restored.status().availability)
        assertEquals(before, hashes(source.directory))
    }

    @Test fun oldReadableRoomCanAddPasswordWithoutOldCodeAndKeepsExistingGrantAndEnabledState() {
        val source = fixture("add-password", withPassword = false)
        val oldBackup = export(source.repo)
        val input = password.toCharArray()
        source.repo.setRecoveryPassword(input)
        assertCleared(input)
        assertTrue(source.repo.status().enabled)
        assertTrue(source.repo.status().recoveryConfirmed)
        assertTrue(source.repo.status().hasRecoveryPassword)
        assertTrue(source.repo.status().hasRecoveryCode)
        assertTrue(source.repo.isAccessGranted(source.requestId))
        assertEquals(source.body, source.repo.readGranted(source.requestId, source.recordId).body)
        val newBackup = export(source.repo)
        assertEquals(2, envelope(newBackup).version)
        val passwordCopy = repository(File(root, "added-password-copy"), source.owner, TrackedProtector())
        passwordCopy.importEncryptedWithPassword(ByteArrayInputStream(newBackup), password.toCharArray())
        assertRecovered(passwordCopy, source)
        val oldCopy = repository(File(root, "old-backup-copy"), source.owner, TrackedProtector())
        oldCopy.importEncrypted(ByteArrayInputStream(oldBackup), source.code!!)
        assertRecovered(oldCopy, source)
        val codeCopy = repository(File(root, "new-backup-code-copy"), source.owner, TrackedProtector())
        codeCopy.importEncrypted(ByteArrayInputStream(newBackup), source.code)
        assertRecovered(codeCopy, source)
    }

    @Test fun passwordRoomCanIssueRandomBackupCodeWithoutLosingPasswordRecovery() {
        val source = fixture("new-backup-code", withPassword = true)
        val code = source.repo.issueRecoveryCode().recoveryCode
        val status = source.repo.status()
        assertFalse(status.enabled)
        assertFalse(status.recoveryConfirmed)
        assertTrue(status.hasRecoveryPassword)
        assertTrue(status.hasRecoveryCode)
        assertFalse(source.repo.isAccessGranted(source.requestId))
        val backup = export(source.repo)
        val byPassword = repository(File(root, "dual-password"), source.owner, TrackedProtector())
        byPassword.importEncryptedWithPassword(ByteArrayInputStream(backup), password.toCharArray())
        assertRecovered(byPassword, source)
        val byCode = repository(File(root, "dual-code"), source.owner, TrackedProtector())
        byCode.importEncrypted(ByteArrayInputStream(backup), code)
        assertRecovered(byCode, source)
    }

    @Test fun everyRejectedPasswordOperationClearsCallerBufferAndLeavesOriginalsIntact() {
        val source = fixture("reject-cleanup", withPassword = false)
        val before = hashes(source.directory)
        val destination = repository(File(root, "invalid-new"), source.owner, TrackedProtector())
        for (invalid in listOf("short", "x".repeat(129))) {
            val create = invalid.toCharArray()
            denied("password_invalid_length") { destination.createWithRecoveryPassword(create) }
            assertCleared(create)
            val set = invalid.toCharArray()
            denied("password_invalid_length") { source.repo.setRecoveryPassword(set) }
            assertCleared(set)
        }
        val exists = password.toCharArray()
        denied("already_exists") { source.repo.createWithRecoveryPassword(exists) }
        assertCleared(exists)
        val corrupt = password.toCharArray()
        denied { destination.importEncryptedWithPassword(ByteArrayInputStream(byteArrayOf(1, 2, 3)), corrupt) }
        assertCleared(corrupt)
        val unconfigured = password.toCharArray()
        denied("password_not_configured") { source.repo.recoverLocalWithPassword(unconfigured) }
        assertCleared(unconfigured)
        assertEquals(before, hashes(source.directory))
        assertEquals(PrivateVaultAvailability.ABSENT, destination.status().availability)
    }

    private data class Fixture(val directory: File, val owner: String, val protector: TrackedProtector,
        val repo: PrivateVaultRepository, val code: String?, val title: String, val body: String,
        val recordId: String, val requestId: String)

    private fun fixture(name: String, withPassword: Boolean): Fixture {
        val directory = File(root, name)
        val owner = UUID.randomUUID().toString()
        val protector = TrackedProtector()
        val repo = repository(directory, owner, protector)
        val code = if (withPassword) {
            val input = password.toCharArray()
            repo.createWithRecoveryPassword(input)
            assertCleared(input)
            null
        } else repo.create().recoveryCode
        assertFalse(repo.status().enabled)
        assertFalse(repo.status().recoveryConfirmed)
        repo.confirmRecoverySaved(); repo.setEnabled(true)
        val title = "SYNTHETIC-PASSWORD-TITLE-$name"
        val body = "SYNTHETIC-PASSWORD-BODY-$name 非真实聊天内容"
        return repo.openAiSession("synthetic-password-device").use { ai ->
            val record = ai.writeRecord(title, body)
            val request = repo.requestAccess("Synthetic password acceptance", listOf(record.id), 600_000)
            ai.decideAccess(request.id, true)
            Fixture(directory, owner, protector, repo, code, title, body, record.id, request.id)
        }
    }

    private fun assertRecovered(repo: PrivateVaultRepository, source: Fixture) {
        assertEquals(PrivateVaultAvailability.READY, repo.status().availability)
        assertFalse(repo.status().enabled)
        assertFalse(repo.isAccessGranted(source.requestId))
        assertEquals(1, repo.status().recordCount)
        assertTrue(repo.requestsForHuman().all { it.status == PrivateVaultRequestStatus.REVOKED })
        repo.setEnabled(true)
        repo.openAiSession("synthetic-restored-password").use { ai ->
            assertEquals(source.title, ai.listRecords().single().title)
            assertEquals(source.body, ai.readRecord(source.recordId).body)
        }
    }

    private fun repository(directory: File, owner: String, protector: PrivateVaultKeyProtector) =
        PrivateVaultRepository(directory, owner, protector, elapsedNow = android.os.SystemClock::elapsedRealtime)
    private fun export(repo: PrivateVaultRepository) = ByteArrayOutputStream().also(repo::exportEncrypted).toByteArray()
    private fun envelope(bytes: ByteArray): VaultEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        assertArrayEquals("ORPVBAK1".toByteArray(Charsets.US_ASCII), ByteArray(8).also(input::readFully))
        input.readUTF()
        val count = input.readInt()
        check(count in 1..MAX_ENVELOPE_BYTES)
        vaultJson.decodeFromString<VaultEnvelope>(ByteArray(count).also(input::readFully).toString(Charsets.UTF_8))
    }
    private fun assertCleared(chars: CharArray) = assertTrue("Caller secret buffer must be erased", chars.all { it == '\u0000' })
    private fun denied(reason: String? = null, block: () -> Unit) {
        try { block(); fail("Expected refusal, not a successful empty replacement") }
        catch (error: PrivateVaultException) { if (reason != null) assertEquals(reason, error.reasonCode) }
    }
    private fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
    private fun assertCipherOnly(directory: File, backup: ByteArray, source: Fixture) {
        val forbidden = listOf(source.title, source.body, password)
        val files = directory.walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList() + listOf(backup)
        files.forEach { bytes -> forbidden.forEach { assertFalse(bytes.toString(Charsets.UTF_8).contains(it)) } }
    }
    private inner class TrackedProtector : PrivateVaultKeyProtector {
        val actual = AndroidPrivateVaultKeyProtector()
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            check(validVaultId(vaultId))
            val name = alias(vaultId)
            check(name in aliases || !keyStore().containsAlias(name))
            aliases += name
            return actual.wrap(vaultId, dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray) = actual.unwrap(vaultId, wrappedKey)
    }
    private fun alias(vaultId: String) = "orbis-private-vault-v1-${vaultDigest(vaultId)}"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
