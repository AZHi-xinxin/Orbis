package me.rerere.rikkahub.data.orbis.privacy

import android.app.Application
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

/**
 * Real Android Keystore, synthetic isolated-runner cache files only. Does not open the app's
 * no_backup directory, start the production Application, contact a model, or touch real vaults.
 * The small protector wrapper records only random key aliases for precise test cleanup; all
 * wrapping/unwrapping is delegated to the production AndroidPrivateVaultKeyProtector.
 */
@RunWith(AndroidJUnit4::class)
class PrivateVaultKeystoreDeviceTest {
    private lateinit var directory: File
    private lateinit var cacheRoot: File
    private val testAliases = linkedSetOf<String>()

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        cacheRoot = instrumentation.targetContext.cacheDir.canonicalFile
        check(cacheRoot.isDirectory || cacheRoot.mkdirs())
        directory = Files.createTempDirectory(cacheRoot.toPath(), "private-vault-keystore-").toFile().canonicalFile
        check(directory.parentFile == cacheRoot)
    }

    @After fun tearDown() {
        try {
            val store = keyStore()
            // Never enumerate or delete other app keys: only aliases captured by this fixture.
            testAliases.forEach { alias ->
                check(alias.matches(Regex("orbis-private-vault-v1-[0-9a-f]{64}")))
                store.deleteEntry(alias)
            }
        } finally {
            if (::directory.isInitialized) {
                check(directory.canonicalFile.parentFile == cacheRoot)
                check(directory.name.startsWith("private-vault-keystore-"))
                check(directory.deleteRecursively())
            }
        }
    }

    @Test fun realKeystoreRoundTripClearsOldGrantsAndRetainsOnlyCiphertextAtRest() {
        val fixture = createGrantedFixture("source")
        val sourceBeforeExport = hashes(fixture.root)
        val backup = export(fixture.repo)
        assertEquals(sourceBeforeExport, hashes(fixture.root))
        assertCipherOnly(fixture.root, backup, fixture)
        assertEquals("", exportedEnvelope(backup).deviceKey)

        val otherProtector = TrackedAndroidProtector()
        assertNotSame(fixture.protector.actual, otherProtector.actual)
        val restoredRoot = File(directory, "fresh-restored")
        assertFalse(restoredRoot.exists())
        val restored = repository(restoredRoot, fixture.assistantId, otherProtector)
        assertEquals(PrivateVaultAvailability.ABSENT, restored.status().availability)
        restored.importEncrypted(ByteArrayInputStream(backup), fixture.recoveryCode)

        assertRecoveredButPaused(restored, fixture)
        restored.setEnabled(true)
        assertFalse(restored.isAccessGranted(fixture.requestId))
        assertDenied("access_not_approved") { restored.readGranted(fixture.requestId, fixture.recordId) }
        restored.openAiSession("synthetic-restored-ai").use { ai ->
            assertEquals(fixture.body, ai.readRecord(fixture.recordId).body)
            assertEquals(fixture.title, ai.listRecords().single().title)
        }
        assertCipherOnly(restoredRoot, export(restored), fixture)
        assertEquals(sourceBeforeExport, hashes(fixture.root))
    }

    @Test fun missingActualKeystoreKeyDoesNotCreateAnEmptyReplacementAndCanRecoverOffline() {
        val fixture = createGrantedFixture("lost-device-key")
        val backup = export(fixture.repo)
        val before = hashes(fixture.root)
        val envelope = exportedEnvelope(backup)
        val alias = aliasFor(envelope.vaultId)
        check(alias in testAliases)
        val store = keyStore()
        assertTrue(store.containsAlias(alias))
        store.deleteEntry(alias) // Exactly this synthetic vault's random key, never a production key.
        assertFalse(store.containsAlias(alias))

        val reopened = repository(fixture.root, fixture.assistantId, TrackedAndroidProtector())
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, reopened.status().availability)
        assertDenied("device_key_unavailable") { reopened.openAiSession("synthetic-no-key") }
        assertDenied("device_key_unavailable") { reopened.readGranted(fixture.requestId, fixture.recordId) }
        assertDenied("already_exists") { reopened.create() }
        assertDenied { reopened.recoverLocal("not-a-valid-recovery-code") }
        assertFalse(store.containsAlias(alias)) // Read/error paths must never mint replacement keys.
        assertEquals(before, hashes(fixture.root))
        assertArrayEquals(backup, export(reopened)) // Original ciphertext is still exportable.

        reopened.recoverLocal(fixture.recoveryCode)
        assertTrue(keyStore().containsAlias(alias))
        assertRecoveredButPaused(reopened, fixture)
        reopened.setEnabled(true)
        reopened.openAiSession("synthetic-rebound-ai").use { ai ->
            assertEquals(fixture.body, ai.readRecord(fixture.recordId).body)
        }
        assertCipherOnly(fixture.root, export(reopened), fixture)
        assertPreservedFiles(before, fixture.root)
    }

    @Test fun emergencyMarkerRequiresRecoveryEvenWithTheOriginalDeviceKeyStillPresent() {
        val fixture = createGrantedFixture("emergency-marker")
        val ownerRoot = File(fixture.root, vaultDigest(fixture.assistantId))
        val marker = File(ownerRoot, "recovery-required.marker").apply { writeText("1") }
        val before = hashes(fixture.root)
        val backup = export(fixture.repo)
        assertTrue(keyStore().containsAlias(aliasFor(exportedEnvelope(backup).vaultId)))
        val reopened = repository(fixture.root, fixture.assistantId, TrackedAndroidProtector())

        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, reopened.status().availability)
        assertFalse(reopened.isAccessGranted(fixture.requestId))
        assertDenied("recovery_required") { reopened.openAiSession("synthetic-marked-ai") }
        assertDenied("recovery_required") { reopened.setEnabled(true) }
        assertDenied("recovery_required") { reopened.readGranted(fixture.requestId, fixture.recordId) }
        assertDenied("already_exists") { reopened.create() }
        assertDenied { reopened.recoverLocal("not-a-valid-recovery-code") }
        assertTrue(marker.exists())
        assertEquals(before, hashes(fixture.root))
        assertCipherOnly(fixture.root, backup, fixture)

        reopened.recoverLocal(fixture.recoveryCode)
        assertFalse(marker.exists())
        assertRecoveredButPaused(reopened, fixture)
        reopened.setEnabled(true)
        assertFalse(reopened.isAccessGranted(fixture.requestId))
        reopened.openAiSession("synthetic-after-rescue-ai").use { ai ->
            assertEquals(fixture.body, ai.readRecord(fixture.recordId).body)
        }
        // Recovery advances CURRENT but never removes earlier immutable ciphertext generations.
        assertPreservedFiles(before.filterKeys { it != "${ownerRoot.name}/recovery-required.marker" }, fixture.root)
        assertCipherOnly(fixture.root, export(reopened), fixture)
    }

    private data class Fixture(
        val root: File,
        val assistantId: String,
        val protector: TrackedAndroidProtector,
        val repo: PrivateVaultRepository,
        val recoveryCode: String,
        val title: String,
        val body: String,
        val recordId: String,
        val requestId: String,
    ) {
        override fun toString() = "SyntheticPrivateVaultFixture(redacted)"
    }

    private fun createGrantedFixture(name: String): Fixture {
        val root = File(directory, name)
        val assistantId = "synthetic-assistant-${UUID.randomUUID()}"
        val protector = TrackedAndroidProtector()
        val repo = repository(root, assistantId, protector)
        val recovery = repo.create()
        assertFalse(repo.status().enabled)
        assertFalse(repo.status().recoveryConfirmed)
        assertDenied("recovery_not_confirmed") { repo.setEnabled(true) }
        repo.confirmRecoverySaved()
        repo.setEnabled(true)
        val title = "Synthetic private title ${UUID.randomUUID()}"
        val body = "SYNTHETIC-PRIVATE-BODY-${UUID.randomUUID()} 这是隔离测试，不是真实聊天。"
        return repo.openAiSession("synthetic-private-ai").use { ai ->
            val record = ai.writeRecord(title, body)
            val request = repo.requestAccess("Synthetic acceptance request", listOf(record.id), 60_000)
            assertFalse(repo.isAccessGranted(request.id))
            assertDenied("access_not_approved") { repo.readGranted(request.id, record.id) }
            assertEquals(PrivateVaultRequestStatus.APPROVED, ai.decideAccess(request.id, true).status)
            assertTrue(repo.isAccessGranted(request.id))
            assertEquals(title, repo.listGranted(request.id).single().title)
            assertEquals(body, repo.readGranted(request.id, record.id).body)
            Fixture(root, assistantId, protector, repo, recovery.recoveryCode, title, body, record.id, request.id)
        }
    }

    private fun assertRecoveredButPaused(repo: PrivateVaultRepository, fixture: Fixture) {
        val status = repo.status()
        assertEquals(PrivateVaultAvailability.READY, status.availability)
        assertEquals(1, status.recordCount)
        assertFalse(status.enabled)
        assertEquals(0, status.pendingRequestCount)
        assertTrue(repo.requestsForHuman().all { it.status == PrivateVaultRequestStatus.REVOKED })
        assertFalse(repo.isAccessGranted(fixture.requestId))
        assertDenied("room_paused") { repo.openAiSession("synthetic-paused-ai") }
    }

    private fun repository(root: File, assistantId: String, protector: PrivateVaultKeyProtector) =
        PrivateVaultRepository(root, assistantId, protector, elapsedNow = android.os.SystemClock::elapsedRealtime)

    private fun export(repo: PrivateVaultRepository) = ByteArrayOutputStream().also(repo::exportEncrypted).toByteArray()

    private fun exportedEnvelope(bytes: ByteArray): VaultEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magic = ByteArray(8).also(input::readFully)
        assertArrayEquals("ORPVBAK1".toByteArray(Charsets.US_ASCII), magic)
        input.readUTF()
        val length = input.readInt()
        check(length in 1..MAX_ENVELOPE_BYTES)
        vaultJson.decodeFromString<VaultEnvelope>(ByteArray(length).also(input::readFully).toString(Charsets.UTF_8))
    }

    private fun assertCipherOnly(root: File, backup: ByteArray, fixture: Fixture) {
        val forbidden = listOf(fixture.title, fixture.body, fixture.recoveryCode).map { it.toByteArray(Charsets.UTF_8) }
        val payloads = root.walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList() + listOf(backup)
        payloads.forEach { payload -> forbidden.forEach { plaintext ->
            assertFalse("Synthetic sensitive text must not occur in stored/exported bytes", contains(payload, plaintext))
        } }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > bytes.size) return false
        return (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
    }

    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    private fun assertPreservedFiles(before: Map<String, String>, root: File) {
        val after = hashes(root)
        before.filterKeys { !it.endsWith("/CURRENT") }.forEach { (path, digest) ->
            assertEquals("Existing ciphertext must survive explicit recovery: $path", digest, after[path])
        }
    }

    private fun assertDenied(reason: String? = null, block: () -> Unit) {
        try { block(); fail("Expected private-vault refusal") }
        catch (error: PrivateVaultException) { if (reason != null) assertEquals(reason, error.reasonCode) }
    }

    private inner class TrackedAndroidProtector : PrivateVaultKeyProtector {
        val actual = AndroidPrivateVaultKeyProtector()
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            check(validVaultId(vaultId))
            val alias = aliasFor(vaultId)
            check(alias in testAliases || !keyStore().containsAlias(alias))
            testAliases += alias
            return actual.wrap(vaultId, dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray) = actual.unwrap(vaultId, wrappedKey)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun aliasFor(vaultId: String) = "orbis-private-vault-v1-${vaultDigest(vaultId)}"
}
