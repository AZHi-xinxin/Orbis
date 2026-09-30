package me.rerere.rikkahub.data.orbis.soup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/** Public UI state deliberately has neither the API key nor a serializable ProviderSetting. */
internal data class SoupDmSettingsState(
    val loaded: Boolean = false,
    val canEdit: Boolean = false,
    val selectionId: String? = null,
    val baseUrl: String = "",
    val modelId: String = "",
    val error: String? = null,
)

internal interface SoupDmPersistence {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

/** An explicitly supplied DM API is independent of every assistant/provider/chat configuration.
 * HTTPS syntax is checked here, but only the endpoint owner can guarantee its server has no memory.
 */
internal fun soupDmEndpoint(input: String): String {
    val base = input.trim().trimEnd('/')
    require(base.length in 1..240 && base.none { it.isISOControl() || it in "\\%" }) { "soup_dm_invalid" }
    val uri = runCatching { URI(base) }.getOrNull() ?: error("soup_dm_invalid")
    val hostname = uri.host?.lowercase() ?: error("soup_dm_invalid")
    require(uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "soup_dm_invalid" }
    require(hostname.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && '.' in hostname) { "soup_dm_invalid" }
    val path = uri.rawPath.orEmpty()
    require(path.split('/').none { it == "." || it == ".." } && !path.contains("//") && !path.endsWith("/chat/completions")) { "soup_dm_invalid" }
    return "https://$hostname$path"
}

/** Not a data class: accidental diagnostics must never print credentials. */
private class SoupDmCredential(val id: String, val endpoint: String, val modelId: String, val key: String) {
    val selection = SoupModelSelection(
        Model(id = Uuid.parse(id), modelId = modelId, displayName = modelId),
        ProviderSetting.OpenAI(id = Uuid.parse(id), name = "独立 DM API", apiKey = key, baseUrl = endpoint,
            includeHistoryReasoning = false, useResponseApi = false), endpoint, modelId,
    )
    override fun toString() = "SoupDmCredential(redacted)"
}

internal class SoupDmSettingsStore(private val persistence: SoupDmPersistence, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SoupDmSettingsState())
    val state = mutableState.asStateFlow()
    @Volatile private var credential: SoupDmCredential? = null
    init { scope.launch { reload() } }

    fun selectionOrNull(id: String): SoupModelSelection? {
        check(state.value.loaded && state.value.canEdit) { "soup_dm_unavailable" }
        return credential?.takeIf { it.id == id }?.selection
    }

    suspend fun reload() = mutex.withLock { loadLocked() }

    /** Every save creates a fresh identity, invalidating any previously prepared paid call. */
    suspend fun save(baseUrl: String, modelId: String, apiKey: String, independentEndpointConfirmed: Boolean): String {
        require(independentEndpointConfirmed) { "soup_dm_confirmation_required" }
        val checked = checked(Uuid.random().toString(), baseUrl, modelId.trim(), apiKey.trim())
        val encoded = buildJsonObject {
            put("version", 1); put("id", checked.id); put("baseUrl", checked.endpoint)
            put("modelId", checked.modelId); put("apiKey", checked.key)
        }.toString()
        withContext(NonCancellable) {
            mutex.withLock {
                if (!state.value.loaded) loadLocked()
                check(state.value.canEdit) { "soup_dm_unavailable" }
                try {
                    persistence.write(encoded)
                    check(persistence.read() == encoded)
                } catch (_: Exception) {
                    credential = null
                    mutableState.value = SoupDmSettingsState(loaded = true, error = "DM 设置保存结果未知，请重新读取；不会继续使用旧配置或自动调用。")
                    error("soup_dm_unavailable")
                }
                publish(checked)
            }
        }
        return checked.id
    }

    suspend fun clear() = withContext(NonCancellable) {
        mutex.withLock {
            try { persistence.write(null); check(persistence.read() == null) }
            catch (_: Exception) {
                credential = null
                mutableState.value = SoupDmSettingsState(loaded = true, error = "DM 设置移除结果未知，请重新读取；不会调用。")
                error("soup_dm_unavailable")
            }
            publish(null)
        }
    }

    private suspend fun loadLocked() {
        credential = null
        try {
            val encoded = persistence.read()
            val saved = encoded?.let {
                require(it.toByteArray().size <= 16384)
                val obj = Json.parseToJsonElement(it) as? JsonObject ?: error("invalid")
                require(obj.keys == setOf("version", "id", "baseUrl", "modelId", "apiKey") && obj["version"] == JsonPrimitive(1))
                fun string(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("invalid")
                checked(string("id"), string("baseUrl"), string("modelId"), string("apiKey"))
            }
            publish(saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = SoupDmSettingsState(loaded = true, error = "DM 设置读取失败，已停用；原文件未覆盖，可重新读取或明确移除。")
        }
    }

    private fun checked(id: String, baseUrl: String, modelId: String, key: String): SoupDmCredential {
        require(Uuid.parse(id).toString() == id) { "soup_dm_invalid" }
        require(modelId.isNotBlank() && modelId.length <= 200 && modelId.none(Char::isISOControl)) { "soup_dm_invalid" }
        require(key.isNotBlank() && key.length <= 4096 && key.none(Char::isISOControl)) { "soup_dm_invalid" }
        return SoupDmCredential(id, soupDmEndpoint(baseUrl), modelId, key)
    }

    private fun publish(value: SoupDmCredential?) {
        credential = value
        mutableState.value = SoupDmSettingsState(true, true, value?.id, value?.endpoint.orEmpty(), value?.modelId.orEmpty())
    }
}

internal object LocalSoupDmSettings {
    @Volatile private var instance: SoupDmSettingsStore? = null
    fun open(context: Context): SoupDmSettingsStore = instance ?: synchronized(this) {
        instance ?: SoupDmSettingsStore(AndroidSoupDmPersistence(context.applicationContext),
            CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
    }
}

/** Separate no-backup, app-private, authenticated encrypted storage; no credentials in game backup. */
private class AndroidSoupDmPersistence(context: Context) : SoupDmPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "orbis-soup-dm-api-v1.bin"))
    private val alias = "orbis_soup_dm_api_v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= 32768)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
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
            require(plain.size <= 16384)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val sealed = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
            val output = file.startWrite()
            try { output.write(sealed); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
            finally { sealed.fill(0) }
        }
    }
}
