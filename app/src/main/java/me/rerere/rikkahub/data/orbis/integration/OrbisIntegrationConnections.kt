package me.rerere.rikkahub.data.orbis.integration

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class OrbisIntegration(val key: String, val title: String) {
    ST_ATLAS("st-atlas", "ST 星盘只读连接"), TECH_HUB("tech-hub", "TechHub 连接"),
    CONSULTATION("consultation-human", "咨询室 · 人类专用连接")
}

data class OrbisConnectionState(val loaded: Boolean = false, val enabled: Boolean = false,
    val configured: Boolean = false, val baseUrl: String = "", val revision: Long = 0,
    val canEdit: Boolean = false, val error: String? = null) {
    val available: Boolean get() = loaded && enabled && configured && error == null
}

/** Not a data class: the default toString must never expose the bearer. */
internal class OrbisConnectionCredential(val baseUrl: String, val token: String, val revision: Long)
internal interface OrbisConnectionPersistence {
    suspend fun read(): String?
    suspend fun write(value: String?)
}

internal fun normalizeOrbisIntegrationUrl(value: String): String {
    require(value.length <= 2048 && value.none { it.isISOControl() }) { "invalid_address" }
    val url = value.trim().toHttpUrlOrNull() ?: error("invalid_address")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "invalid_address" }
    val octets = url.host.split('.').map { it.toIntOrNull() }
    val privateIp = octets.size == 4 && octets.all { it != null && it in 0..255 } &&
        (octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1]!! in 16..31) || (octets[0] == 100 && octets[1]!! in 64..127))
    require(url.isHttps || privateIp || url.host == "localhost" || url.host == "::1") { "https_required" }
    // No user-controlled path traversal/encoded separators at the prefix boundary.
    require(url.encodedPath.none { it == '%' || it == '\\' }) { "invalid_address" }
    return url.toString().trimEnd('/')
}

internal fun validateOrbisIntegrationToken(token: String): String = token.trim().also {
    require(it.length in 32..4096 && it.all { character -> character.code in 33..126 }) { "invalid_token" }
}

class OrbisIntegrationConnections(context: Context, scope: CoroutineScope) {
    private val stores = OrbisIntegration.entries.associateWith {
        OrbisConnectionStore(AndroidOrbisConnectionPersistence(context.applicationContext, it.key), scope)
    }
    operator fun get(kind: OrbisIntegration): OrbisConnectionStore = stores.getValue(kind)
    val atlasBootstrap = OrbisAtlasBootstrapStore(
        AndroidOrbisConnectionPersistence(context.applicationContext, "st-atlas-bootstrap"),
        stores.getValue(OrbisIntegration.ST_ATLAS), OrbisAtlasBootstrapClient(), scope,
    )
    init {
        stores.getValue(OrbisIntegration.ST_ATLAS).accessAllowed = {
            atlasBootstrap.state.value.readAllowed
        }
    }
}

/** Separate from exported Settings and provider/MCP credentials; only the user's explicit save enables it. */
class OrbisConnectionStore internal constructor(private val persistence: OrbisConnectionPersistence, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OrbisConnectionState())
    val state = mutableState.asStateFlow()
    private var credential: OrbisConnectionCredential? = null
    internal var accessAllowed: () -> Boolean = { true }
    init { scope.launch { reload() } }

    suspend fun reload() = mutex.withLock { loadLocked() }

    suspend fun save(baseUrl: String, newToken: String, enabled: Boolean) = withContext(NonCancellable) {
        mutex.withLock {
            if (!state.value.loaded) loadLocked()
            check(state.value.canEdit) { "配置尚未安全读取，请先重试或明确清除。" }
            val url = normalizeOrbisIntegrationUrl(baseUrl)
            val token = if (newToken.isNotBlank()) validateOrbisIntegrationToken(newToken) else {
                check(credential?.baseUrl == url) { "更换地址时必须重新填写专用 Token。" }
                credential?.token ?: error("请填写专用 Token。")
            }
            val revision = state.value.revision + 1
            val json = buildJsonObject { put("baseUrl", url); put("token", token); put("enabled", enabled) }.toString()
            try { persistence.write(json) } catch (_: Exception) { error("配置保存失败，旧设置仍保留。") }
            credential = OrbisConnectionCredential(url, token, revision)
            mutableState.value = OrbisConnectionState(true, enabled, true, url, revision, true)
        }
    }

    suspend fun clear() = withContext(NonCancellable) {
        mutex.withLock {
            try { persistence.write(null) } catch (_: Exception) { error("本机配置未能清除。") }
            credential = null
            mutableState.value = OrbisConnectionState(loaded = true, canEdit = true, revision = state.value.revision + 1)
        }
    }

    internal suspend fun readCredential(): OrbisConnectionCredential? = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        credential.takeIf { state.value.available && accessAllowed() }
    }

    /** Stable across process restarts; never exposed by the public/UI state. */
    internal suspend fun configurationFingerprint(): String = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        check(state.value.canEdit) { "本机星图配置尚未安全读取。" }
        fingerprintLocked()
    }

    private fun fingerprintLocked(): String = atlasBootstrapDigest(buildJsonArray {
        add(credential?.baseUrl.orEmpty()); add(credential?.token.orEmpty()); add(state.value.enabled)
    }.toString())

    /** CAS covers old hand-entered settings and retries after an interrupted local commit. */
    internal suspend fun installBootstrap(expected: String, root: String, token: String,
        sourceStillValid: () -> Unit) = withContext(NonCancellable) {
        mutex.withLock {
            if (!state.value.loaded) loadLocked()
            check(state.value.canEdit) { "本机星图配置尚未安全读取。" }
            val sameIntent = credential?.baseUrl == root && credential?.token == token && state.value.enabled
            if (!sameIntent && fingerprintLocked() != expected) throw AtlasBootstrapException("atlas_changed")
            sourceStillValid()
            if (!sameIntent) writeBootstrapLocked(root, token, true)
        }
    }

    internal suspend fun setBootstrapEnabled(root: String, token: String, enabled: Boolean) = withContext(NonCancellable) {
        mutex.withLock {
            if (!state.value.loaded) loadLocked()
            if (!state.value.canEdit || credential?.baseUrl != root || credential?.token != token)
                throw AtlasBootstrapException("atlas_changed")
            writeBootstrapLocked(root, token, enabled)
        }
    }

    /** A revoked/forgotten bootstrap must never erase an unrelated newer manual connection. */
    internal suspend fun clearBootstrapIfOwned(root: String, token: String) = withContext(NonCancellable) {
        mutex.withLock {
            if (!state.value.loaded) loadLocked()
            check(state.value.canEdit) { "本机星图配置尚未安全读取。" }
            if (credential?.baseUrl == root && credential?.token == token) {
                persistence.write(null)
                credential = null
                mutableState.value = OrbisConnectionState(loaded = true, canEdit = true, revision = state.value.revision + 1)
            }
        }
    }

    private suspend fun writeBootstrapLocked(root: String, token: String, enabled: Boolean) {
        val url = normalizeOrbisIntegrationUrl(root)
        val checked = validateOrbisIntegrationToken(token)
        val json = buildJsonObject { put("baseUrl", url); put("token", checked); put("enabled", enabled) }.toString()
        persistence.write(json)
        val revision = state.value.revision + 1
        credential = OrbisConnectionCredential(url, checked, revision)
        mutableState.value = OrbisConnectionState(true, enabled, true, url, revision, true)
    }

    private suspend fun loadLocked() {
        val revision = state.value.revision + 1
        credential = null
        try {
            val saved = persistence.read()
            if (saved == null) {
                mutableState.value = OrbisConnectionState(loaded = true, canEdit = true, revision = revision)
                return
            }
            require(saved.toByteArray().size <= 16384)
            val root = Json.parseToJsonElement(saved).jsonObject
            require(root.keys == setOf("baseUrl", "token", "enabled"))
            fun text(key: String) = root.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
            val url = normalizeOrbisIntegrationUrl(text("baseUrl"))
            val token = validateOrbisIntegrationToken(text("token"))
            val enabled = root.getValue("enabled").jsonPrimitive.let { require(!it.isString); it.boolean }
            credential = OrbisConnectionCredential(url, token, revision)
            mutableState.value = OrbisConnectionState(true, enabled, true, url, revision, true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableState.value = OrbisConnectionState(loaded = true, revision = revision,
                error = "本机授权读取失败，已保持断开；原文件未覆盖。请重试读取或清除后重新配置。")
        }
    }
}

internal class AndroidOrbisConnectionPersistence(context: Context, key: String) : OrbisConnectionPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "orbis-integration-$key.bin"))
    private val alias = "orbis_integration_${key}_v1"
    private val aad = alias.toByteArray(Charsets.UTF_8)
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
        val bytes = file.openRead().use { it.cloudReadBounded(20000) }
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString() }
        finally { plain.fill(0); bytes.fill(0) }
    }
    /** Diagnostic snapshot only. Unlike AtomicFile.openRead, this never restores a backup,
     * removes a pending write, creates a directory or creates a key. The owning store must
     * hold its normal writer mutex. Interrupted writes are unavailable, not repaired. */
    internal suspend fun readExistingSnapshot(): String? = withContext(Dispatchers.IO) {
        // Android may expose /data/user/0 through a trusted /data/data ancestor
        // alias. Canonicalize that trusted root, not the untrusted file itself.
        val base = File(checkNotNull(file.baseFile.parentFile).canonicalFile, file.baseFile.name)
        fun checkStablePath() {
            check(base.canonicalFile == base.absoluteFile)
            check(!File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
        }
        checkStablePath()
        if (!base.exists()) return@withContext null
        check(base.isFile)
        val bytes = base.inputStream().use { it.cloudReadBounded(20000) }
        try {
            checkStablePath()
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
            try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString() }
            finally { plain.fill(0) }
        } finally { bytes.fill(0) }
    }
    override suspend fun write(value: String?) = withContext(Dispatchers.IO) {
        if (value == null) {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
        } else {
            val plain = value.toByteArray(Charsets.UTF_8)
            require(plain.size <= 16384)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
            val sealed = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
            val output = file.startWrite()
            try {
                output.write(sealed)
                output.fd.sync()
                file.finishWrite(output)
                // AtomicFile may only log a failed rename: do not claim that a
                // new authorization was saved until the encrypted bytes match.
                val stored = file.openRead().use { it.cloudReadBounded(20000) }
                try { check(stored.contentEquals(sealed)) { "encrypted_config_write_failed" } }
                finally { stored.fill(0) }
            }
            catch (error: Throwable) { file.failWrite(output); throw error }
            finally { sealed.fill(0) }
        }
    }
}
