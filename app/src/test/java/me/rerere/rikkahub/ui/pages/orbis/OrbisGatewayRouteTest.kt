package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionPersistence
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayRouteTest {
    private val base = "https://st.example/v1"
    private val admin = "SYNTHETIC_ADMIN_" + "x".repeat(32)
    private val upstreamKey = "SYNTHETIC_UPSTREAM_ONLY"
    private fun code(expected: String, action: () -> Unit) {
        val error = assertThrows(GatewayRouteException::class.java) { action() }
        assertEquals(expected, error.code)
        assertFalse(error.message.orEmpty().contains(admin))
        assertFalse(error.message.orEmpty().contains(upstreamKey))
    }
    private class MemoryPersistence : OrbisConnectionPersistence {
        var saved: String? = null
        var failWrite = false
        override suspend fun read() = saved
        override suspend fun write(value: String?) { if (failWrite) error("synthetic disk failure"); saved = value }
    }
    private class Transport : GatewayRouteTransport {
        var posts = 0; var reads = 0; var receiptReads = 0
        var tokenSeen: String? = null
        var writeSeen: GatewayRouteWrite? = null
        var error: GatewayRouteException? = null
        var capabilityEnabled = true
        var receiptFound = true
        var beforePost: () -> Unit = {}
        var directory = GatewayRouteDirectory(4, listOf(GatewayRouteRow("main", "https://up.example/v1", "flash", false, true)))
        override suspend fun capability(base: String) = capabilityEnabled
        override suspend fun directory(base: String, token: String): GatewayRouteDirectory { tokenSeen = token; reads++; return directory }
        override suspend fun upsert(base: String, token: String, value: GatewayRouteWrite): GatewayRouteReceipt {
            beforePost(); posts++; tokenSeen = token; writeSeen = value
            error?.let { throw it }
            return GatewayRouteReceipt(value.requestId, 5, value.publicModel)
        }
        override suspend fun receipt(base: String, token: String, value: GatewayRouteWrite): GatewayRouteReceipt? {
            receiptReads++; tokenSeen = token
            return if (receiptFound) GatewayRouteReceipt(value.requestId, 5, value.publicModel) else null
        }
    }
    private suspend fun paired(persistence: MemoryPersistence, transport: Transport): GatewayRouteStore =
        GatewayRouteStore(persistence, transport).also { it.load(); it.pair(base, admin) }
    private suspend fun create(store: GatewayRouteStore) = store.create(base, 4, "st-pro", "https://up.example/v1", "pro", upstreamKey)

    @Test fun managementSameOriginAndTailnetOnly() {
        val url = gatewayManagementUrl("https://st.example:8443/prefix/v1/", "upsert")
        assertEquals("https://st.example:8443/prefix/v1/st/routes/upsert", url.toString())
        assertEquals("http://100.71.245.14:18010/v1", gatewayManagementBase("http://100.71.245.14:18010/v1").toString())
        listOf("http://192.168.31.48:18010/v1", "http://public.example/v1", "https://user:secret@st.example/v1",
            "https://st.example/v1?q=secret", "https://st.example/v1#secret", "https://st.example/v1%2F", "https://st.example/").forEach {
            code("unsafe_address") { gatewayManagementBase(it) }
        }
    }
    @Test fun endpointCannotEscapeOrCarryCredentials() {
        listOf("../chat/completions", "https://evil.example", "requests/../../x", "requests/" + "a".repeat(33)).forEach {
            code("invalid") { gatewayManagementUrl(base, it) }
        }
        assertTrue(gatewayManagementUrl(base, "requests/" + "a".repeat(32)).toString().startsWith(base + "/st/routes/requests/"))
    }
    @Test fun unsafeUpstreamsAndMultipleKeysAreRejected() {
        listOf("http://up.example/v1", "https://127.0.0.1/v1", "https://10.1.1.1/v1", "https://100.71.245.14/v1",
            "https://169.254.169.254/v1", "https://localhost/v1", "https://host.local/v1", "https://[::1]/v1",
            "https://user:secret@up.example/v1", "https://up.example/v1?api_key=secret").forEach { code("unsafe_address") { gatewayUpstreamBase(it) } }
        listOf("key-one,key-two", "key-one\nkey-two", "key one", "").forEach { code("unsupported") { gatewaySingleKey(it) } }
    }
    @Test fun sourcePreservesExactModelButRefusesAdvancedOverrides() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://up.example/v1", apiKey = upstreamKey)
        val model = Model(modelId = "vendor/pro")
        assertEquals("https://up.example/v1" to "vendor/pro", gatewaySource(provider, model))
        code("unsupported") { gatewaySource(provider.copy(useResponseApi = true), model) }
        code("unsupported") { gatewaySource(provider, model.copy(type = ModelType.IMAGE)) }
        code("unsupported") { gatewaySource(provider, model.copy(providerOverwrite = provider)) }
        code("unsupported") { gatewaySource(provider, model.copy(customHeaders = listOf(CustomHeader("X-Test", "private")))) }
    }
    @Test fun capabilityIsExplicitAndNotAnAuthGrant() {
        assertTrue(parseGatewayCapability("""{"schema":"orbis.st.routes-capabilities/1","enabled":true,"authorization":"dedicated-human-bearer","maxRoutes":16}"""))
        assertFalse(parseGatewayCapability("""{"schema":"orbis.st.routes-capabilities/1","enabled":false,"authorization":"dedicated-human-bearer","maxRoutes":16}"""))
        code("not_gateway") { parseGatewayCapability("""{"schema":"ordinary","enabled":true,"authorization":"chat-token","maxRoutes":16}""") }
    }
    @Test fun malformedAndOversizeResponseDoesNotReachUi() {
        code("response") { parseGatewayRoutes("not-json") }
        code("response") { parseGatewayRoutes(" ".repeat(GATEWAY_ROUTES_MAX_BYTES + 1)) }
        code("response") { parseGatewayRoutes("""{"schema":"orbis.st.routes/1","revision":-1,"routes":[]}""") }
    }
    @Test fun strictReceiptCannotConfirmADifferentRequestOrAlias() {
        val receipt = """{"schema":"orbis.st.routes-upsert-result/1","requestId":"abc","publicModel":"pro","status":"saved","revision":5}"""
        assertEquals("pro", parseGatewayReceipt(receipt, "abc", "pro").publicModel)
        code("response") { parseGatewayReceipt(receipt, "different", "pro") }
        code("response") { parseGatewayReceipt(receipt, "abc", "different") }
    }
    @Test fun directorySeparatesImmutableAndManagedRoutes() {
        val value = parseGatewayRoutes("""{"schema":"orbis.st.routes/1","revision":4,"routes":[
            {"publicModel":"main","upstreamBaseUrl":"https://up.example/v1","upstreamModel":"flash","managed":false,"keyConfigured":true},
            {"publicModel":"pro","upstreamBaseUrl":"https://up.example/v1","upstreamModel":"pro","managed":true,"keyConfigured":true}]}""")
        assertEquals(4L, value.revision); assertFalse(value.routes.first().managed); assertTrue(value.routes.last().managed)
    }
    @Test fun pairReadsWithoutPostingAndKeepsAdminSeparateFromUpstream() = runBlocking {
        val p = MemoryPersistence(); val t = Transport(); val store = paired(p, t)
        assertEquals(admin, t.tokenSeen); assertEquals(0, t.posts)
        assertFalse(store.state.value.toString().contains(admin))
        assertFalse(store.state.value.toString().contains(upstreamKey))
        assertFalse(p.saved!!.contains(upstreamKey))
    }
    @Test fun disabledManagementDoesNotSaveAPairing() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { capabilityEnabled = false }
        code("disabled") { runBlocking { paired(p, t) } }
        assertNull(p.saved); assertEquals(0, t.posts); assertEquals(0, t.reads)
    }
    @Test fun writesExactlyOneExplicitSourceAndPersistsIntentBeforeNetwork() = runBlocking {
        val p = MemoryPersistence(); val t = Transport(); val store = paired(p, t)
        t.beforePost = { assertTrue(p.saved!!.contains(upstreamKey)); assertTrue(store.state.value.pending) }
        create(store)
        assertEquals(1, t.posts); assertEquals(admin, t.tokenSeen); assertEquals(upstreamKey, t.writeSeen!!.apiKey)
        assertEquals("st-pro", store.state.value.savedAlias); assertFalse(store.state.value.pending)
        assertFalse(p.saved!!.contains(upstreamKey)); assertEquals("GatewayRouteWrite(private)", t.writeSeen.toString())
    }
    @Test fun encryptedIntentWriteFailureMakesNoPost() = runBlocking {
        val p = MemoryPersistence(); val t = Transport(); val store = paired(p, t); p.failWrite = true
        code("storage") { runBlocking { create(store) } }
        assertEquals(0, t.posts)
    }
    @Test fun pendingSurvivesReloadAndReconcilesWithoutResendingKey() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { error = GatewayRouteException("unknown") }
        val store = paired(p, t)
        code("unknown") { runBlocking { create(store) } }
        assertTrue(store.state.value.pending)
        val reopened = GatewayRouteStore(p, t); reopened.load()
        assertTrue(reopened.state.value.pending)
        code("pending") { runBlocking { create(reopened) } }
        assertTrue(reopened.reconcile(base)); assertEquals(1, t.posts); assertEquals(1, t.receiptReads)
        assertFalse(p.saved!!.contains(upstreamKey))
    }
    @Test fun missingReceiptKeepsIntentRatherThanAllowingDuplicateCreation() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { error = GatewayRouteException("unknown"); receiptFound = false }
        val store = paired(p, t)
        code("unknown") { runBlocking { create(store) } }
        assertFalse(store.reconcile(base)); assertTrue(store.state.value.pending)
        code("pending") { runBlocking { store.pair(base, admin) } }
        assertEquals(1, t.posts)
    }
    @Test fun onlyExplicitRetryAfterNotFoundUsesTheExactOldIdAndPayload() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { error = GatewayRouteException("unknown"); receiptFound = false }
        val store = paired(p, t)
        code("unknown") { runBlocking { create(store) } }
        val original = t.writeSeen!!.body()
        code("pending") { runBlocking { store.retrySameRequest(base) } }
        assertFalse(store.reconcile(base)); assertTrue(store.state.value.canRetrySameRequest)
        t.error = null
        store.retrySameRequest(base)
        assertEquals(2, t.posts); assertEquals(original, t.writeSeen!!.body())
        assertFalse(store.state.value.pending); assertFalse(store.state.value.canRetrySameRequest)
    }
    @Test fun definiteConflictClearsIntentButRequiresFreshDirectory() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { error = GatewayRouteException("revision_conflict", true) }
        val store = paired(p, t)
        code("revision_conflict") { runBlocking { create(store) } }
        assertFalse(store.state.value.pending); assertNull(store.state.value.directory)
        assertFalse(p.saved!!.contains(upstreamKey)); assertEquals(1, t.posts)
    }
    @Test fun rejectedRetryDoesNotDiscardEarlierUncertainIntent() = runBlocking {
        val p = MemoryPersistence(); val t = Transport().apply { error = GatewayRouteException("unknown"); receiptFound = false }
        val store = paired(p, t)
        code("unknown") { runBlocking { create(store) } }
        assertFalse(store.reconcile(base))
        t.error = GatewayRouteException("unauthorized", true)
        code("unauthorized") { runBlocking { store.retrySameRequest(base) } }
        assertTrue(store.state.value.pending); assertTrue(p.saved!!.contains(upstreamKey)); assertEquals(2, t.posts)
    }
    @Test fun cannotOverwriteServerDefaultOrUseDifferentOrigin() = runBlocking {
        val p = MemoryPersistence(); val t = Transport(); val store = paired(p, t)
        code("immutable_route") { runBlocking { store.create(base, 4, "main", "https://up.example/v1", "pro", upstreamKey) } }
        code("changed") { runBlocking { store.create("https://other.example/v1", 4, "st-pro", "https://up.example/v1", "pro", upstreamKey) } }
        code("revision_conflict") { runBlocking { store.create(base, 3, "st-pro", "https://up.example/v1", "pro", upstreamKey) } }
        assertEquals(0, t.posts)
    }
    @Test fun changedSourceAfterConfirmationNeverPosts() = runBlocking {
        val p = MemoryPersistence(); val t = Transport(); val store = paired(p, t)
        code("changed") { runBlocking { store.create(base, 4, "st-pro", "https://up.example/v1", "pro", upstreamKey) { throw GatewayRouteException("changed") } } }
        assertEquals(0, t.posts); assertFalse(store.state.value.pending)
    }
    @Test fun unreadableRecordIsNotOverwrittenOrSubmitted() = runBlocking {
        val p = MemoryPersistence().apply { saved = "broken original record" }; val t = Transport(); val store = GatewayRouteStore(p, t)
        code("storage") { runBlocking { store.load() } }
        code("storage") { runBlocking { store.pair(base, admin) } }
        assertEquals("broken original record", p.saved); assertEquals(0, t.posts)
    }
}
