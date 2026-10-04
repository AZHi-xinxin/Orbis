package me.rerere.rikkahub.data.orbis.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidPrivateVaults {
    fun open(context: Context, assistantId: String): PrivateVaultRepository {
        val application = context.applicationContext
        return PrivateVaultRepository(
            // This lexical fallback is never used when the resolver is supplied. In particular,
            // opening the UI does not call getNoBackupFilesDir(), which may create directories.
            File(application.dataDir, "no_backup/orbis-private-vaults"),
            assistantId,
            AndroidPrivateVaultKeyProtector(),
            elapsedNow = android.os.SystemClock::elapsedRealtime,
            baseDirectoryResolver = {
                resolveAndroidPrivateVaultBase(application.dataDir, application.noBackupFilesDir)
            },
        )
    }
}

/**
 * Zygote app-data isolation may expose /data/user/0 as a system-owned alias only inside the
 * application's mount namespace. Resolve the OS-provided application root, never its writable
 * no_backup/vault/owner descendants. The same existing files are addressed; nothing is moved.
 */
internal fun resolveAndroidPrivateVaultBase(dataDirectory: File, noBackupDirectory: File): File {
    val suppliedRoot = dataDirectory.toPath().toAbsolutePath().normalize()
    val suppliedNoBackup = noBackupDirectory.toPath().toAbsolutePath().normalize()
    if (suppliedNoBackup == suppliedRoot || !suppliedNoBackup.startsWith(suppliedRoot)) {
        throw PrivateVaultException("unsafe_path")
    }
    val trustedRoot = dataDirectory.canonicalFile.toPath()
    val mappedNoBackup = trustedRoot.resolve(suppliedRoot.relativize(suppliedNoBackup))
    val base = mappedNoBackup.resolve("orbis-private-vaults")
    // Keep the global no-link policy intact, including every writable application descendant.
    rejectVaultLinks(base)
    return base.toFile()
}

/**
 * Keystore protects the wrapping key from export; it does NOT authenticate an AI identity or
 * protect plaintext from a compromised app/OS. No user-presence requirement is claimed here.
 */
class AndroidPrivateVaultKeyProtector : PrivateVaultKeyProtector {
    override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray = synchronized(lock) {
        require(dataKey.size == 32) { "private_vault_key_invalid" }
        try {
            val key = getKey(vaultId) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias(vaultId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == 12)
            cipher.updateAAD(aad(vaultId))
            byteArrayOf(1) + cipher.iv + cipher.doFinal(dataKey)
        } catch (_: Exception) { throw PrivateVaultException("device_key_unavailable") }
    }

    override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray = synchronized(lock) {
        if (wrappedKey.size != 61 || wrappedKey[0] != 1.toByte()) throw PrivateVaultException("device_key_unavailable")
        try {
            // Reading never creates a replacement key or silently opens a new empty room.
            val key = getKey(vaultId) ?: throw PrivateVaultException("device_key_unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrappedKey.copyOfRange(1, 13)))
            cipher.updateAAD(aad(vaultId))
            cipher.doFinal(wrappedKey, 13, wrappedKey.size - 13)
        } catch (_: Exception) { throw PrivateVaultException("device_key_unavailable") }
    }

    private fun getKey(vaultId: String): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias(vaultId), null) as? SecretKey
    }
    private fun alias(vaultId: String) = "orbis-private-vault-v1-" + MessageDigest.getInstance("SHA-256")
        .digest(vaultId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun aad(vaultId: String) = "Orbis private vault device envelope v1\n$vaultId".toByteArray(Charsets.UTF_8)
    companion object { private val lock = Any() }
}
