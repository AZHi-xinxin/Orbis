package me.rerere.rikkahub.data.orbis

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.model.normalizeOrbisCloudHomeUrl
import me.rerere.rikkahub.data.model.validOrbisGardenName

data class OrbisCloudSettingsState(
    val loaded: Boolean = false,
    val config: OrbisCloudHomeConfig = OrbisCloudHomeConfig(),
    val canEdit: Boolean = false,
    /** Fixed presentation text only: never include an address, credential or underlying exception. */
    val error: String? = null,
)

internal interface OrbisCloudSettingsPersistence {
    suspend fun read(): String?
    suspend fun write(value: String)
    suspend fun readBootstrap(): String?
}

/** Separate no-backup storage: never reads/writes Settings, chat databases or provider credentials. */
class OrbisCloudSettingsStore internal constructor(
    private val persistence: OrbisCloudSettingsPersistence,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        OrbisCloudPreferencesPersistence(context.applicationContext, scope), scope,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OrbisCloudSettingsState())
    val state = mutableState.asStateFlow()
    private var writable = false
    private val json = Json { encodeDefaults = true }

    init { scope.launch { mutex.withLock { if (!state.value.loaded) loadLocked() } } }

    /** A failed load never fabricates an enabled entry and never permits overwriting unread data. */
    suspend fun reload() = mutex.withLock { loadLocked() }

    /** A local tool invocation cannot race a route switch or write through an unread configuration. */
    internal suspend fun <T> withLocalGarden(block: suspend (OrbisCloudHomeConfig) -> T): T = mutex.withLock {
        // Koin creates this store lazily. The first tool can arrive before the scheduled init
        // coroutine runs; join/perform the initial read under the same lock rather than treating
        // an unread local route as an explicitly selected remote route.
        if (!state.value.loaded) loadLocked()
        check(writable) { "garden_configuration_unavailable" }
        check(!state.value.config.enabled) { "garden_local_mode_required" }
        block(state.value.config)
    }

    suspend fun save(config: OrbisCloudHomeConfig) = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        check(writable) { "云端配置尚未安全读取，请重试读取后再保存。" }
        val normalized = validate(config)
        try {
            persistence.write(json.encodeToString(normalized))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IOException("云端配置未能保存，原有设置已保留。")
        }
        mutableState.value = OrbisCloudSettingsState(loaded = true, config = normalized, canEdit = true)
    }

    private suspend fun loadLocked() {
        writable = false
        try {
            val stored = persistence.read()
            if (stored != null) {
                require(stored.length <= 4096)
                val config = validate(json.decodeFromString<OrbisCloudHomeConfig>(stored))
                writable = true
                mutableState.value = OrbisCloudSettingsState(loaded = true, config = config, canEdit = true)
                return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = OrbisCloudSettingsState(loaded = true,
                error = "云端配置读取失败，已保持离线。请重试读取，原文件会保留。")
            return
        }
        // Only an absent persisted entry permits reading the optional local suggestion file.
        writable = true
        try {
            val bootstrap = persistence.readBootstrap()
            val config = if (bootstrap == null) OrbisCloudHomeConfig() else parseBootstrap(bootstrap)
            if (bootstrap != null) persistence.write(json.encodeToString(config))
            mutableState.value = OrbisCloudSettingsState(loaded = true, config = config, canEdit = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = OrbisCloudSettingsState(loaded = true, canEdit = true,
                error = "本机建议地址未能导入，已保持离线。可以手动填写并保存主页地址。")
        }
    }

    private fun validate(config: OrbisCloudHomeConfig): OrbisCloudHomeConfig {
        require(listOf(config.gardenName, config.humanName, config.companionName).all(::validOrbisGardenName)) {
            "名称须为 1–32 字，不能含换行或控制字符。"
        }
        if (!config.enabled && config.homeUrl.isEmpty()) return config
        val url = normalizeOrbisCloudHomeUrl(config.homeUrl)
        require(url != null) { "请填写有效的 HTTPS 云端主页地址。" }
        return config.copy(homeUrl = url)
    }

    private fun parseBootstrap(text: String): OrbisCloudHomeConfig {
        require(text.toByteArray(Charsets.UTF_8).size <= 4096)
        // A single exact key rejects credentials, unknown fields and duplicate JSON keys.
        require(Regex("""\s*\{\s*"homeUrl"\s*:\s*"(?:[^"\\]|\\.)*"\s*\}\s*""").matches(text))
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("invalid bootstrap")
        val value = root["homeUrl"] as? JsonPrimitive ?: error("invalid bootstrap")
        require(value.isString)
        return validate(OrbisCloudHomeConfig(enabled = false, homeUrl = value.content))
    }
}

private class OrbisCloudPreferencesPersistence(context: Context, scope: CoroutineScope) : OrbisCloudSettingsPersistence {
    private val directory = context.noBackupFilesDir
    private val key = stringPreferencesKey("orbis_cloud_home")
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(directory, "orbis-cloud-home.preferences_pb") },
    )

    override suspend fun read(): String? = dataStore.data.first()[key]

    override suspend fun write(value: String) {
        dataStore.edit { it[key] = value }
    }

    override suspend fun readBootstrap(): String? = withContext(Dispatchers.IO) {
        val file = File(directory, "orbis-cloud-home-bootstrap.json")
        if (!file.exists()) return@withContext null
        require(file.isFile && file.length() <= 4096)
        file.inputStream().use { stream ->
            val bytes = ByteArray(4097)
            var size = 0
            while (size < bytes.size) {
                val count = stream.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= 4096)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString()
        }
    }
}
