package me.rerere.rikkahub.data.orbis.privacy

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.KeyStore
import java.util.UUID

/**
 * Production factory, raw OS noBackupFilesDir and actual Android Keystore. Unlike the cache
 * fixtures this exercises the exact creation path used by the page, without canonicalising
 * that input first. Emulator-only, random synthetic owner; no model, chat or existing vault.
 * Failure messages contain only fixed stages/reasons and existence booleans, never a recovery
 * code, envelope, pathname, private content or a platform exception message.
 */
@RunWith(AndroidJUnit4::class)
class PrivateVaultProductionFactoryDeviceTest {
    private lateinit var context: Context
    private lateinit var owner: String
    private lateinit var ownDirectory: File
    private val ownAliases = linkedSetOf<String>()

    @Before fun isolatedProductionPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        context = instrumentation.targetContext.applicationContext
        check(context.javaClass == Application::class.java)
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "isolated_emulator_required" }
        owner = "synthetic-factory-${UUID.randomUUID()}"
        ownDirectory = File(File(context.noBackupFilesDir, "orbis-private-vaults"), vaultDigest(owner))
        check(!Files.exists(ownDirectory.toPath(), NOFOLLOW_LINKS)) { "synthetic_owner_collision" }
    }

    @Test fun rawNoBackupProductionFactoryCreatesAndReopensWithoutSendingARequest() = runBlocking {
        var stage = "factory_open"
        try {
            val repository = AndroidPrivateVaults.open(context, owner)
            stage = "before_create_status"
            assertTrue(repository.status().availability == PrivateVaultAvailability.ABSENT)
            assertFalse(ownDirectory.exists())
            stage = "create_io"
            val material = withContext(Dispatchers.IO) { repository.create() }
            stage = "committed_envelope"
            rememberOwnAlias()
            // Assert without rendering the secret in JUnit's expected/actual messages.
            assertTrue(material.recoveryCode.startsWith("ORPV1-") && material.recoveryCode.length == 49)
            stage = "reopen_status"
            val reopened = AndroidPrivateVaults.open(context, owner)
            val status = withContext(Dispatchers.IO) { reopened.status() }
            assertTrue(status.availability == PrivateVaultAvailability.READY)
            assertFalse(status.enabled)
            assertFalse(status.recoveryConfirmed)
            assertTrue(status.recordCount == 0)
            stage = "canonical_reopen_same_inode"
            val current = File(ownDirectory, "CURRENT")
            val originalPointer = current.readBytes()
            val originalInode = Os.stat(current.path).st_ino
            val canonicalContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getDataDir(): File = context.dataDir.canonicalFile
                override fun getNoBackupFilesDir(): File = File(dataDir, "no_backup")
            }
            val canonicalRepository = AndroidPrivateVaults.open(canonicalContext, owner)
            assertTrue(canonicalRepository.status() == status)
            try {
                canonicalRepository.create()
                throw AssertionError("existing_room_recreated")
            } catch (error: PrivateVaultException) {
                assertTrue(error.reasonCode == "already_exists")
            }
            assertTrue(current.readBytes().contentEquals(originalPointer))
            assertTrue(Os.stat(current.path).st_ino == originalInode)
            stage = "confirm_then_reopen"
            withContext(Dispatchers.IO) { reopened.confirmRecoverySaved() }
            val confirmed = withContext(Dispatchers.IO) { AndroidPrivateVaults.open(context, owner).status() }
            assertTrue(confirmed.recoveryConfirmed)
            assertFalse(confirmed.enabled)
            stage = "existing_record_same_room"
            withContext(Dispatchers.IO) {
                reopened.setEnabled(true)
                val record = reopened.openAiSession("synthetic-binding").use {
                    it.writeRecord("synthetic-title", "synthetic-body")
                }
                val existing = AndroidPrivateVaults.open(canonicalContext, owner)
                assertTrue(existing.status() == reopened.status())
                assertTrue(existing.status().recoveryConfirmed && existing.status().recordCount == 1)
                existing.openAiSession("synthetic-binding").use {
                    assertTrue(it.readRecord(record.id).body == "synthetic-body")
                }
            }
        } catch (error: Throwable) {
            val reason = (error as? PrivateVaultException)?.reasonCode?.takeIf { it in SAFE_REASONS }
                ?: if (error is AssertionError) "assertion_failed" else "unclassified"
            throw AssertionError("private_factory_stage=$stage reason=$reason " +
                "owner_directory_exists=${ownDirectory.exists()} " +
                "current_exists=${File(ownDirectory, "CURRENT").exists()} " + pathDiagnostics())
        }
    }

    /** Fixed relative scopes only: compare Java NIO's result with Android's native lstat. */
    private fun pathDiagnostics(): String {
        val observations = mutableListOf<String>()
        var path: File? = ownDirectory
        var level = 0
        while (path != null && level < 16) {
            val current = path
            val scope = when (level) { 0 -> "owner"; 1 -> "vaults"; 2 -> "no_backup"; 3 -> "app"; else -> "ancestor_${level - 3}" }
            val nio = try { Files.isSymbolicLink(current.toPath()).toString() } catch (_: Exception) { "unknown" }
            val native = try { OsConstants.S_ISLNK(Os.lstat(current.path).st_mode).toString() }
                catch (error: ErrnoException) { "errno_${error.errno}" }
                catch (_: Exception) { "unknown" }
            val canonicalChanges = try { (current.canonicalFile != current.absoluteFile).toString() }
                catch (_: Exception) { "unknown" }
            observations += "$scope:nio=$nio,native=$native,canonical_changes=$canonicalChanges"
            path = current.parentFile
            level++
        }
        return observations.joinToString(";")
    }

    private fun rememberOwnAlias() {
        // Inspection must also address the same file through the trusted application root,
        // otherwise this test would itself reject the deliberately exercised OS ancestor alias.
        val physicalOwner = File(File(context.dataDir.canonicalFile, "no_backup/orbis-private-vaults"), vaultDigest(owner))
        val generation = readVaultFile(File(physicalOwner, "CURRENT"), 36).toString(Charsets.US_ASCII)
        check(validVaultId(generation))
        val head = readVaultFile(File(physicalOwner, "generations/$generation/HEAD"), 36).toString(Charsets.US_ASCII)
        check(validVaultId(head))
        val envelope = decodeVaultJson<VaultEnvelope>(readVaultFile(
            File(physicalOwner, "generations/$generation/states/$head.vault"), MAX_ENVELOPE_BYTES))
        check(envelope.ownerDigest == vaultDigest(owner) && validVaultId(envelope.vaultId))
        ownAliases += "orbis-private-vault-v1-${vaultDigest(envelope.vaultId)}"
    }

    @After fun removeOnlyThisSyntheticOwnerAndItsKnownKey() {
        if (!::ownDirectory.isInitialized) return
        // Never enumerate Keystore aliases or other owners. If creation failed before writing an
        // envelope, no unknown key is guessed or removed; the fixed failure above remains evidence.
        if (File(ownDirectory, "CURRENT").isFile && ownAliases.isEmpty()) {
            try { rememberOwnAlias() } catch (_: Exception) { /* Leave unknown keys untouched. */ }
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also { store ->
            ownAliases.forEach { alias ->
                check(alias.matches(Regex("orbis-private-vault-v1-[0-9a-f]{64}")))
                store.deleteEntry(alias)
            }
        }
        if (!Files.exists(ownDirectory.toPath(), NOFOLLOW_LINKS)) return
        val expectedParent = File(context.noBackupFilesDir, "orbis-private-vaults").canonicalFile
        check(ownDirectory.name == vaultDigest(owner) && ownDirectory.canonicalFile.parentFile == expectedParent)
        // Files.walk does not follow links. Refuse even a synthetic linked entry rather than
        // allowing recursive cleanup to traverse outside this exact random owner directory.
        val entries = Files.walk(ownDirectory.toPath()).use { stream -> stream.toArray().map { it as java.nio.file.Path } }
        check(entries.all { !Files.isSymbolicLink(it) }) { "synthetic_cleanup_link_refused" }
        entries.sortedByDescending { it.nameCount }.forEach(Files::delete)
    }

    companion object {
        private val SAFE_REASONS = setOf("unsafe_path", "storage_failed", "device_key_unavailable",
            "already_exists", "authentication_failed", "invalid_format", "size_limit")
    }
}
