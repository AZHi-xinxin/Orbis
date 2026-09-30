package me.rerere.rikkahub.data.orbis.integration

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting

enum class OrbisAtlasBootstrapPhase { NONE, PENDING, ACTIVE, REVOKING, REVOKED, UNREADABLE }
data class OrbisAtlasBootstrapState(val loaded: Boolean = false, val busy: Boolean = false,
    val phase: OrbisAtlasBootstrapPhase = OrbisAtlasBootstrapPhase.NONE, val providerId: String? = null,
    val root: String = "", val error: String? = null) {
    val managed: Boolean get() = !loaded || phase != OrbisAtlasBootstrapPhase.NONE
    val readAllowed: Boolean get() = loaded && phase in setOf(OrbisAtlasBootstrapPhase.NONE, OrbisAtlasBootstrapPhase.ACTIVE)
}

/** Encrypted private intent, separate from backups/Settings; contains NO gateway key. */
@Serializable
private data class AtlasBootstrapIntent(val version: Int = 1, val phase: OrbisAtlasBootstrapPhase,
    val providerId: String, val root: String, val sourceFingerprint: String, val targetFingerprint: String,
    val requestId: String, val deviceId: String, val token: String, val grantId: String? = null,
    val revokeRequestId: String? = null) {
    override fun toString(): String = "AtlasBootstrapIntent(private)"
}

/** One bounded managed flow. Construction/reload is local-only; every HTTP request is explicit. */
class OrbisAtlasBootstrapStore internal constructor(private val persistence: OrbisConnectionPersistence,
    private val atlas: OrbisConnectionStore, private val transport: AtlasBootstrapTransport,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OrbisAtlasBootstrapState())
    val state = mutableState.asStateFlow()
    private var intent: AtlasBootstrapIntent? = null
    private val json = Json { encodeDefaults = true }
    private val random = SecureRandom()
    init { scope.launch { reload() } }

    suspend fun reload() = mutex.withLock { loadLocked() }

    suspend fun connect(providerId: String, providers: () -> List<ProviderSetting>) = operation {
        if (state.value.managed) throw AtlasBootstrapException("managed")
        val source = source(providerId, providers)
        val expected = atlas.configurationFingerprint()
        transport.capability(source.root)
        sameSource(source.providerId, source.fingerprint, providers)
        val next = AtlasBootstrapIntent(phase = OrbisAtlasBootstrapPhase.PENDING,
            providerId = source.providerId, root = source.root, sourceFingerprint = source.fingerprint,
            targetFingerprint = expected, requestId = randomHex(), deviceId = randomHex(),
            token = "orb_atlas_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)))
        persist(next) // MUST be durable before a POST can create a remote grant.
        confirmLocked(providers)
    }

    /** Exact original IDs/verifier after timeout or restart, never a second enrollment. */
    suspend fun retry(providers: () -> List<ProviderSetting>) = operation {
        if (intent?.phase != OrbisAtlasBootstrapPhase.PENDING) throw AtlasBootstrapException("managed")
        confirmLocked(providers)
    }

    private suspend fun confirmLocked(providers: () -> List<ProviderSetting>) {
        val pending = checkNotNull(intent)
        val current = sameSource(pending.providerId, pending.sourceFingerprint, providers)
        val result = transport.register(current, pending.requestId, pending.deviceId, atlasBootstrapDigest(pending.token))
        if (pending.grantId != null && pending.grantId != result.grantId) throw AtlasBootstrapException("invalid_response")
        if (!result.active) {
            persist(pending.copy(phase = OrbisAtlasBootstrapPhase.REVOKED, grantId = result.grantId))
            atlas.clearBootstrapIfOwned(pending.root, pending.token)
            throw AtlasBootstrapException("revoked")
        }
        // Save the receipt first. A later local failure remains an exact retriable intent.
        val confirmed = pending.copy(grantId = result.grantId)
        persist(confirmed)
        sameSource(pending.providerId, pending.sourceFingerprint, providers)
        atlas.installBootstrap(pending.targetFingerprint, pending.root, pending.token) {
            sameSource(pending.providerId, pending.sourceFingerprint, providers)
        }
        // Check again after any suspending IO before claiming the connection was installed.
        sameSource(pending.providerId, pending.sourceFingerprint, providers)
        persist(confirmed.copy(phase = OrbisAtlasBootstrapPhase.ACTIVE))
    }

    /** Revoke does not depend on the old higher-privilege provider still existing. */
    suspend fun revoke() = operation {
        val saved = intent ?: throw AtlasBootstrapException("managed")
        if (saved.phase == OrbisAtlasBootstrapPhase.REVOKED) {
            atlas.clearBootstrapIfOwned(saved.root, saved.token)
            return@operation
        }
        val revoking = saved.copy(phase = OrbisAtlasBootstrapPhase.REVOKING,
            revokeRequestId = saved.revokeRequestId ?: randomHex())
        persist(revoking)
        transport.revoke(revoking.root, revoking.token, checkNotNull(revoking.revokeRequestId))
        persist(revoking.copy(phase = OrbisAtlasBootstrapPhase.REVOKED))
        atlas.clearBootstrapIfOwned(revoking.root, revoking.token)
    }

    suspend fun setEnabled(enabled: Boolean) = operation {
        val saved = intent?.takeIf { it.phase == OrbisAtlasBootstrapPhase.ACTIVE }
            ?: throw AtlasBootstrapException("managed")
        atlas.setBootstrapEnabled(saved.root, saved.token, enabled)
    }

    /** UI must explicitly warn: forgetting is local only, NOT evidence of server revocation. */
    suspend fun forgetLocal() = mutex.withLock {
        withContext(NonCancellable) {
            try {
                if (!state.value.loaded) loadLocked()
                intent?.let { atlas.clearBootstrapIfOwned(it.root, it.token) }
                persistence.write(null)
                intent = null
                publish()
            } catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false, error = AtlasBootstrapException("storage").message)
            }
        }
    }

    suspend fun saveManual(root: String, token: String, enabled: Boolean) = operation {
        if (state.value.managed) throw AtlasBootstrapException("managed")
        atlas.save(root, token, enabled)
    }

    suspend fun clearManual() = operation {
        if (state.value.managed) throw AtlasBootstrapException("managed")
        atlas.clear()
    }

    private suspend fun operation(action: suspend () -> Unit) = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        if (state.value.phase == OrbisAtlasBootstrapPhase.UNREADABLE) return@withLock
        mutableState.value = state.value.copy(busy = true, error = null)
        try { action(); publish() }
        catch (cancelled: CancellationException) {
            publish(AtlasBootstrapException("unknown").message)
            throw cancelled
        } catch (error: AtlasBootstrapException) { publish(error.message)
        } catch (_: Exception) { publish(AtlasBootstrapException("storage").message) }
    }

    private fun source(id: String, providers: () -> List<ProviderSetting>): AtlasBootstrapSource =
        atlasBootstrapSource(providers().singleOrNull { it.id.toString() == id })

    private fun sameSource(id: String, fingerprint: String, providers: () -> List<ProviderSetting>): AtlasBootstrapSource {
        val current = try { source(id, providers) } catch (_: Exception) { throw AtlasBootstrapException("source_changed") }
        if (current.fingerprint != fingerprint) throw AtlasBootstrapException("source_changed")
        return current
    }

    private suspend fun persist(next: AtlasBootstrapIntent) = withContext(NonCancellable) {
        try { persistence.write(json.encodeToString(next)) }
        catch (_: Exception) { throw AtlasBootstrapException("storage") }
        intent = next
        publish(busy = true)
    }

    private suspend fun loadLocked() {
        try {
            val saved = persistence.read()
            intent = if (saved == null) null else json.decodeFromString<AtlasBootstrapIntent>(saved).also {
                require(saved.toByteArray().size <= 16384 && it.version == 1)
                require(it.phase in setOf(OrbisAtlasBootstrapPhase.PENDING, OrbisAtlasBootstrapPhase.ACTIVE,
                    OrbisAtlasBootstrapPhase.REVOKING, OrbisAtlasBootstrapPhase.REVOKED))
                kotlin.uuid.Uuid.parse(it.providerId)
                require(normalizeOrbisIntegrationUrl(it.root) == it.root)
                require(listOf(it.sourceFingerprint, it.targetFingerprint).all { value -> value.matches(Regex("[0-9a-f]{64}")) })
                require(listOf(it.requestId, it.deviceId).all { value -> value.matches(Regex("[0-9a-f]{32}")) })
                require(it.token.matches(Regex("orb_atlas_[A-Za-z0-9_-]{43}")))
                require(it.grantId == null || it.grantId.matches(Regex("[0-9a-f]{32}")))
                require(it.revokeRequestId == null || it.revokeRequestId.matches(Regex("[0-9a-f]{32}")))
                require(it.phase != OrbisAtlasBootstrapPhase.ACTIVE || it.grantId != null)
                require(it.phase != OrbisAtlasBootstrapPhase.REVOKING || it.revokeRequestId != null)
            }
            publish()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            intent = null
            mutableState.value = OrbisAtlasBootstrapState(loaded = true, phase = OrbisAtlasBootstrapPhase.UNREADABLE,
                error = AtlasBootstrapException("storage").message)
        }
    }

    private fun publish(error: String? = null, busy: Boolean = false) {
        mutableState.value = OrbisAtlasBootstrapState(loaded = true, busy = busy,
            phase = intent?.phase ?: OrbisAtlasBootstrapPhase.NONE, providerId = intent?.providerId,
            root = intent?.root.orEmpty(), error = error)
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
    private fun randomHex() = randomBytes(16).joinToString("") { "%02x".format(it) }
}
