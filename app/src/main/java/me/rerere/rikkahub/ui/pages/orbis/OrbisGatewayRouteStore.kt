package me.rerere.rikkahub.ui.pages.orbis

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionPersistence
import me.rerere.rikkahub.data.orbis.integration.AndroidOrbisConnectionPersistence
import kotlin.uuid.Uuid

/** One mutex/AtomicFile owner per provider across configuration changes and overlapping dialogs. */
internal object GatewayRouteStores {
    private val values = mutableMapOf<Uuid, GatewayRouteStore>()
    @Synchronized fun get(context: Context, providerId: Uuid): GatewayRouteStore = values.getOrPut(providerId) {
        GatewayRouteStore(AndroidOrbisConnectionPersistence(context.applicationContext, "st-route-management-$providerId"), GatewayRouteClient())
    }
}

internal data class GatewayRouteState(val loaded: Boolean = false, val readable: Boolean = false,
    val pairedBase: String? = null, val pending: Boolean = false, val directory: GatewayRouteDirectory? = null,
    val savedAlias: String? = null, val canRetrySameRequest: Boolean = false)

/** Lives only in Keystore-encrypted noBackupFilesDir, never exported Settings or chat history. */
@Serializable
private class GatewayRoutePrivate(val version: Int = 1, val base: String, val token: String,
    val pending: GatewayRouteWrite? = null) {
    override fun toString() = "GatewayRoutePrivate(private)"
}

internal class GatewayRouteStore(private val persistence: OrbisConnectionPersistence, private val transport: GatewayRouteTransport) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(GatewayRouteState())
    val state = mutable.asStateFlow()
    private var secretState: GatewayRoutePrivate? = null
    private val json = Json { encodeDefaults = true }

    suspend fun load() = mutex.withLock { loadLocked() }
    private suspend fun loadLocked() {
        try {
            val raw = persistence.read()
            secretState = raw?.let { text ->
                routeRequire(text.toByteArray().size <= 16384, "storage")
                json.decodeFromString<GatewayRoutePrivate>(text).also {
                    routeRequire(it.version == 1 && gatewayManagementBase(it.base).toString() == it.base, "storage")
                    gatewayAdminToken(it.token); it.pending?.validate()
                }
            }
            mutable.value = GatewayRouteState(true, true, secretState?.base, secretState?.pending != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { secretState = null; mutable.value = GatewayRouteState(loaded = true); throw GatewayRouteException("storage") }
    }
    private suspend fun ready(): GatewayRoutePrivate {
        if (!state.value.loaded) loadLocked()
        routeRequire(state.value.readable, "storage")
        return secretState ?: throw GatewayRouteException("unauthorized")
    }
    private suspend fun persist(next: GatewayRoutePrivate) = withContext(NonCancellable) {
        try { persistence.write(json.encodeToString(next)) } catch (_: Exception) { throw GatewayRouteException("storage") }
        secretState = next
        mutable.value = state.value.copy(loaded = true, readable = true, pairedBase = next.base, pending = next.pending != null)
    }
    suspend fun pair(base: String, token: String) = mutex.withLock {
        if (!state.value.loaded) loadLocked()
        routeRequire(state.value.readable, "storage")
        routeRequire(secretState?.pending == null, "pending")
        val url = gatewayManagementBase(base).toString()
        val admin = gatewayAdminToken(token)
        routeRequire(transport.capability(url), "disabled")
        val directory = transport.directory(url, admin) // Only independent management token, never chat key.
        persist(GatewayRoutePrivate(base = url, token = admin))
        mutable.value = state.value.copy(directory = directory, savedAlias = null)
    }
    suspend fun read(base: String) = mutex.withLock {
        val current = ready()
        routeRequire(gatewayManagementBase(base).toString() == current.base, "changed")
        val directory = transport.directory(current.base, current.token)
        mutable.value = state.value.copy(directory = directory)
    }
    suspend fun create(base: String, expectedRevision: Long, alias: String, upstream: String, model: String,
        apiKey: String, stillValid: () -> Unit = {}) = mutex.withLock {
        val current = ready()
        routeRequire(current.pending == null, "pending")
        routeRequire(gatewayManagementBase(base).toString() == current.base, "changed")
        val directory = state.value.directory ?: throw GatewayRouteException("changed")
        routeRequire(directory.revision == expectedRevision, "revision_conflict")
        val routeName = gatewayRouteName(alias)
        routeRequire(directory.routes.none { it.publicModel == routeName }, "immutable_route")
        val value = GatewayRouteWrite(UUID.randomUUID().toString().replace("-", ""), expectedRevision, routeName,
            gatewayUpstreamBase(upstream), gatewayUpstreamModel(model), gatewaySingleKey(apiKey))
        value.validate(); stillValid()
        persist(GatewayRoutePrivate(base = current.base, token = current.token, pending = value))
        // The old state remains a durable uncertain intent from this point, including cancellation/process death.
        submit(current, value, stillValid, firstAttempt = true)
    }
    private suspend fun submit(current: GatewayRoutePrivate, value: GatewayRouteWrite, stillValid: () -> Unit, firstAttempt: Boolean) {
        mutable.value = state.value.copy(canRetrySameRequest = false)
        try {
            stillValid()
            val receipt = transport.upsert(current.base, current.token, value)
            routeRequire(receipt.requestId == value.requestId && receipt.publicModel == value.publicModel, "response")
            complete(current, receipt)
        } catch (error: GatewayRouteException) {
            // A later rejected retry cannot prove an earlier uncertain request never committed.
            if (firstAttempt && (error.definiteRejection || error.code == "changed")) {
                persist(GatewayRoutePrivate(base = current.base, token = current.token))
                mutable.value = state.value.copy(directory = null, savedAlias = null)
            }
            throw error
        }
    }
    suspend fun reconcile(base: String): Boolean = mutex.withLock {
        val current = ready()
        routeRequire(gatewayManagementBase(base).toString() == current.base, "changed")
        val pending = current.pending ?: throw GatewayRouteException("pending")
        val receipt = transport.receipt(current.base, current.token, pending)
        if (receipt == null) {
            mutable.value = state.value.copy(canRetrySameRequest = true)
            return@withLock false
        }
        routeRequire(receipt.requestId == pending.requestId && receipt.publicModel == pending.publicModel, "response")
        complete(current, receipt)
        true
    }
    /** Only after a fresh read returned not-found, and only on a second explicit human action. */
    suspend fun retrySameRequest(base: String, stillValid: () -> Unit = {}) = mutex.withLock {
        val current = ready()
        routeRequire(gatewayManagementBase(base).toString() == current.base, "changed")
        routeRequire(state.value.canRetrySameRequest, "pending")
        val pending = current.pending ?: throw GatewayRouteException("pending")
        stillValid()
        submit(current, pending, stillValid, firstAttempt = false) // Same ID + CAS + original exact payload.
    }
    private suspend fun complete(current: GatewayRoutePrivate, receipt: GatewayRouteReceipt) {
        persist(GatewayRoutePrivate(base = current.base, token = current.token))
        mutable.value = state.value.copy(directory = null, savedAlias = receipt.publicModel, canRetrySameRequest = false)
    }
}
