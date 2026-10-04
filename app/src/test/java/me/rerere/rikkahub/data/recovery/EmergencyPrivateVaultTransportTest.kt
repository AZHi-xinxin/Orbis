package me.rerere.rikkahub.data.recovery

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultCrypto
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

/** Actual AEAD ciphertext, but synthetic database/settings; Android validation has separate tests. */
class EmergencyPrivateVaultTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rawAndRestoredVaultRemainCiphertextAndRequireExplicitIndependentRecoveryCode() {
        val app = temporary.newFolder("synthetic-app")
        val roots = EmergencyRestore.rootNames.associateWith { File(app, it).apply { mkdirs() } }
        fun write(relative: String, bytes: ByteArray) = File(app, relative).apply {
            parentFile!!.mkdirs(); writeBytes(bytes)
        }
        write("databases/rikka_hub", "synthetic database".toByteArray())
        write("files/datastore/settings.preferences_pb", "synthetic settings".toByteArray())
        val owner = MessageDigest.getInstance("SHA-256").digest("synthetic assistant".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val generation = "33be8625-cb24-418e-852e-7189d7daebee"
        val state = "276ad6b8-2fb3-4ec4-9874-354e2b9028be"
        val record = "944040e2-0c8a-4c0e-ae52-78d6cbd5fbd0"
        val vaultId = "748ae15c-d321-4e2a-9024-82481b01c19f"
        val base = "no_backup/orbis-private-vaults/$owner"
        val plaintext = "synthetic private body that must never occur in backup plaintext".toByteArray()
        val creation = PrivateVaultCrypto.create(vaultId)
        val (ciphertext, envelope) = creation.session.use { session ->
            val encryptedBody = session.encryptRecord(record, plaintext)
            val wrapped = buildJsonObject {
                put("version", 1); put("vaultId", vaultId); put("ownerDigest", owner)
                // Inert synthetic device wrapper; the transport never attempts device unwrapping.
                put("deviceKey", Base64.getEncoder().encodeToString(ByteArray(61) { 7 }))
                put("recoveryKey", Base64.getEncoder().encodeToString(creation.recovery.wrappedKey))
                put("encryptedState", Base64.getEncoder().encodeToString(session.encryptRecord("index", "synthetic encrypted index".toByteArray())))
            }.toString().toByteArray()
            encryptedBody to wrapped
        }
        write("$base/CURRENT", generation.toByteArray())
        write("$base/generations/$generation/HEAD", state.toByteArray())
        write("$base/generations/$generation/states/$state.vault", envelope)
        write("$base/generations/$generation/records/$record.bin", ciphertext)
        val archive = File(temporary.root, "rescue.zip")
        EmergencyArchive.create(roots.map { EmergencyArchiveRoot(it.key, it.value) }, archive,
            EmergencyArchiveMetadata("synthetic.orbis", "test", 1))
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val text = zip.getInputStream(entries.nextElement()).use { it.readBytes().toString(Charsets.UTF_8) }
                assertFalse(text.contains(plaintext.toString(Charsets.UTF_8)))
                assertFalse(text.contains(creation.recovery.recoveryCode))
            }
        }
        val transaction = File(app, "orbis-emergency/transaction")
        val plan = EmergencyRestore.prepare(archive, transaction, "synthetic.orbis", 1, roots, {}, {})
        assertEquals(1, plan.restoredPrivateVaults)
        assertEquals(0, plan.retainedPrivateVaults)
        assertArrayEquals(envelope, File(transaction, "raw/$base/generations/$generation/states/$state.vault").readBytes())
        assertArrayEquals(envelope, File(transaction, "prepared/$base/generations/$generation/states/$state.vault").readBytes())
        assertEquals("1", File(transaction, "prepared/$base/recovery-required.marker").readText())
        EmergencyRestore.commit(transaction, roots, {})
        assertEquals("1", File(app, "$base/recovery-required.marker").readText())
        assertArrayEquals(ciphertext, File(app, "$base/generations/$generation/records/$record.bin").readBytes())
        PrivateVaultCrypto.recover(vaultId, creation.recovery.recoveryCode, creation.recovery.wrappedKey).use { recovered ->
            assertArrayEquals(plaintext, recovered.decryptRecord(record,
                File(app, "$base/generations/$generation/records/$record.bin").readBytes()))
        }
        assertArrayEquals(ciphertext, File(transaction, "originals/$base/generations/$generation/records/$record.bin").readBytes())
    }
}
