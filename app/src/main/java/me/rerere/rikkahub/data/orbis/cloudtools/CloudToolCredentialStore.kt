package me.rerere.rikkahub.data.orbis.cloudtools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CloudToolCredentialState(
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val baseUrl: String = "",
    val canEdit: Boolean = false,
    val error: String? = null,
)

internal interface CloudCredentialPersistence {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

/** Keystore-only secret, independent no-backup file, never returned in UI state or diagnostics. */
class CloudToolCredentialStore internal constructor(
    private val persistence: CloudCredentialPersistence,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(AndroidCloudCredentialPersistence(context.applicationContext), scope)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CloudToolCredentialState())
    val state = mutableState.asStateFlow()
    private var credential: CloudGatewayCredential? = null

    init { scope.launch { reload() } }

    suspend fun reload() = mutex.withLock { loadLocked() }

    suspend fun save(baseUrl: String, token: String) {
        val checked = try { validateCloudGatewayCredential(baseUrl, token) } catch (_: Exception) {
            throw CloudToolsException("invalid_connection")
        }
        withContext(NonCancellable) {
            mutex.withLock {
                if (!state.value.loaded) loadLocked()
                if (!state.value.canEdit) throw CloudToolsException("credential_load_failed")
                val serialized = buildJsonObject { put("baseUrl", checked.baseUrl); put("token", checked.token) }.toString()
                try { persistence.write(serialized) } catch (_: Exception) { throw CloudToolsException("credential_save_failed") }
                credential = checked
                mutableState.value = CloudToolCredentialState(true, true, checked.baseUrl, true)
            }
        }
    }

    /** Local disconnect only; does not claim to revoke the corresponding server grant. */
    suspend fun clear() = withContext(NonCancellable) {
        mutex.withLock {
            try { persistence.write(null) } catch (_: Exception) { throw CloudToolsException("credential_clear_failed") }
            credential = null
            mutableState.value = CloudToolCredentialState(loaded = true, canEdit = true)
        }
    }

    internal suspend fun readCredential(): CloudGatewayCredential? = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        credential
    }

    private suspend fun loadLocked() {
        credential = null
        try {
            val saved = persistence.read()
            val checked = if (saved == null) null else {
                require(saved.toByteArray().size <= 8192)
                val obj = Json.parseToJsonElement(saved) as? JsonObject ?: error("invalid_store")
                require(obj.keys == setOf("baseUrl", "token"))
                fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_store")
                validateCloudGatewayCredential(text("baseUrl"), text("token"))
            }
            credential = checked
            mutableState.value = CloudToolCredentialState(true, checked != null, checked?.baseUrl.orEmpty(), true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableState.value = CloudToolCredentialState(loaded = true, error = "授权读取失败，已保持断开。原文件未覆盖；请重试读取或明确清除本机授权。")
        }
    }
}

private class AndroidCloudCredentialPersistence(context: Context) : CloudCredentialPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "orbis-native-cloud-credential.bin"))
    private val alias = "orbis_native_cloud_device_v1"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "credential_key_missing" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        val bytes = file.openRead().use { stream ->
            stream.cloudReadBounded(16384)
        }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("orbis-cloud-tools-v1".toByteArray(Charsets.UTF_8))
        val decoded = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(decoded)).toString() }
        finally { decoded.fill(0); bytes.fill(0) }
    }

    override suspend fun write(value: String?) = withContext(Dispatchers.IO) {
        if (value == null) {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
        } else {
            val plain = value.toByteArray(Charsets.UTF_8)
            require(plain.size <= 8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD("orbis-cloud-tools-v1".toByteArray(Charsets.UTF_8))
            val sealed = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
            val output = file.startWrite()
            try { output.write(sealed); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
            finally { sealed.fill(0) }
        }
    }
}
