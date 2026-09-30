package me.rerere.rikkahub.data.orbis.integration

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

/** Synthetic credentials and in-memory encrypted-persistence boundary; no Android or HTTP. */
class OrbisAtlasBootstrapStoreTest {
    private class Memory : OrbisConnectionPersistence {
        var saved: String? = null
        var writes = 0
        var failAt = -1
        var failRead = false
        override suspend fun read(): String? { if (failRead) error("private read path"); return saved }
        override suspend fun write(value: String?) {
            writes++
            if (writes == failAt) error("private write path")
            saved = value
        }
    }
    private class Transport : AtlasBootstrapTransport {
        var capabilities = 0
        var registers = 0
        var revokes = 0
        var failRegister = false
        var failRevoke = false
        var active = true
        val requests = mutableListOf<List<String>>()
        val revocations = mutableListOf<List<String>>()
        var afterCapability: suspend () -> Unit = {}
        var afterRegister: suspend () -> Unit = {}
        override suspend fun capability(root: String) { capabilities++; afterCapability() }
        override suspend fun register(source: AtlasBootstrapSource, requestId: String, deviceId: String, verifier: String): AtlasBootstrapRegistration {
            registers++; requests += listOf(source.root, requestId, deviceId, verifier)
            afterRegister()
            if (failRegister) throw AtlasBootstrapException("unknown")
            return AtlasBootstrapRegistration("a".repeat(32), active)
        }
        override suspend fun revoke(root: String, token: String, requestId: String) {
            revokes++; revocations += listOf(root, token, requestId)
            if (failRevoke) throw AtlasBootstrapException("unknown")
        }
    }
    private val gatewayKey = "synthetic-gateway-secret-" + "g".repeat(40)
    private val oldToken = "synthetic-old-atlas-secret-" + "o".repeat(40)
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = Memory()
        val atlasStorage = Memory()
        val atlas = OrbisConnectionStore(atlasStorage, scope)
        val transport = Transport()
        var store = OrbisAtlasBootstrapStore(storage, atlas, transport, scope)
        init { gate() }
        fun gate() { atlas.accessAllowed = { store.state.value.loaded && store.state.value.phase in setOf(
            OrbisAtlasBootstrapPhase.NONE, OrbisAtlasBootstrapPhase.ACTIVE) } }
        fun restart() { store = OrbisAtlasBootstrapStore(storage, atlas, transport, scope); gate() }
        override fun close() { scope.cancel() }
    }
    private fun provider() = ProviderSetting.OpenAI(name = "synthetic ST", apiKey = gatewayKey,
        baseUrl = "https://synthetic.example/proxy/v1")
    private fun Memory.root() = Json.parseToJsonElement(checkNotNull(saved)).jsonObject

    @Test fun passiveConstructionAndReloadAndManualSaveHaveNoNetwork() = runBlocking {
        Fixture().use { f ->
            f.store.reload()
            f.store.saveManual("https://manual.example", oldToken, true)
            assertEquals(oldToken, f.atlas.readCredential()?.token)
            assertEquals(0, f.transport.capabilities + f.transport.registers + f.transport.revokes)
            assertNull(f.storage.saved)
        }
    }

    @Test fun enrollmentPersistsIndependentTokenBeforeRegisterWithoutCopyingGatewayCredential() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.transport.afterRegister = {
                assertEquals("PENDING", f.storage.root().getValue("phase").jsonPrimitive.content)
                assertNull(f.atlas.readCredential())
            }
            f.store.connect(p.id.toString()) { listOf(p) }
            assertEquals(OrbisAtlasBootstrapPhase.ACTIVE, f.store.state.value.phase)
            val token = f.atlas.readCredential()!!.token
            assertTrue(token.matches(Regex("orb_atlas_[A-Za-z0-9_-]{43}")))
            assertNotEquals(gatewayKey, token)
            assertEquals(atlasBootstrapDigest(token), f.transport.requests.single()[3])
            assertFalse(f.storage.saved!!.contains(gatewayKey))
            assertFalse(f.store.state.value.toString().contains(token))
            assertEquals("https://synthetic.example/proxy", f.atlas.state.value.baseUrl)
        }
    }

    @Test fun timeoutRestartDoesNotNetworkAndManualRetryUsesExactOriginalIntent() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.atlas.save("https://manual.example", oldToken, true)
            val old = f.atlasStorage.saved
            f.transport.failRegister = true
            f.store.connect(p.id.toString()) { listOf(p) }
            val intent = f.storage.saved
            assertEquals(OrbisAtlasBootstrapPhase.PENDING, f.store.state.value.phase)
            assertEquals(old, f.atlasStorage.saved)
            assertNull(f.atlas.readCredential())
            f.restart()
            assertEquals(1, f.transport.registers)
            assertEquals(intent, f.storage.saved)
            f.transport.failRegister = false
            f.store.retry { listOf(p) }
            assertEquals(1, f.transport.capabilities)
            assertEquals(f.transport.requests[0], f.transport.requests[1])
            assertEquals(OrbisAtlasBootstrapPhase.ACTIVE, f.store.state.value.phase)
        }
    }

    @Test fun modifiedProviderAfterCapabilityStopsBeforePendingOrPost() = runBlocking {
        listOf("key", "url", "enabled", "missing").forEach { change ->
            Fixture().use { f ->
                val p = provider()
                var providers: List<ProviderSetting> = listOf(p)
                f.transport.afterCapability = {
                    providers = when (change) {
                        "key" -> listOf(p.copy(apiKey = "other-synthetic-credential-".repeat(2)))
                        "url" -> listOf(p.copy(baseUrl = "https://changed.example/v1"))
                        "enabled" -> listOf(p.copy(enabled = false))
                        else -> emptyList()
                    }
                }
                f.store.connect(p.id.toString()) { providers }
                assertEquals(0, f.transport.registers)
                assertNull(f.storage.saved)
                assertTrue(f.store.state.value.error!!.contains("原连接已更改"))
            }
        }
    }

    @Test fun providerChangedDuringRegisterPreservesPendingAndOldManualConnection() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            var providers = listOf(p)
            f.atlas.save("https://manual.example", oldToken, true)
            val old = f.atlasStorage.saved
            f.transport.afterRegister = { providers = listOf(p.copy(apiKey = "changed-synthetic-gateway-".repeat(2))) }
            f.store.connect(p.id.toString()) { providers }
            assertEquals(OrbisAtlasBootstrapPhase.PENDING, f.store.state.value.phase)
            assertEquals(old, f.atlasStorage.saved)
            f.store.retry { providers }
            assertEquals(1, f.transport.registers)
            assertNotNull(f.storage.saved)
            assertNull(f.atlas.readCredential())
        }
    }

    @Test fun concurrentManualConfigurationCannotBeOverwrittenByOldEnrollment() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.transport.afterRegister = { f.atlas.save("https://new-manual.example", oldToken, true) }
            f.store.connect(p.id.toString()) { listOf(p) }
            assertEquals(OrbisAtlasBootstrapPhase.PENDING, f.store.state.value.phase)
            assertTrue(f.store.state.value.error!!.contains("未覆盖"))
            assertEquals("https://new-manual.example", f.atlas.state.value.baseUrl)
            f.store.forgetLocal()
            assertEquals(oldToken, f.atlas.readCredential()?.token)
        }
    }

    @Test fun revokedRegisterNeverInstallsOrReenrolls() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.atlas.save("https://manual.example", oldToken, true)
            val old = f.atlasStorage.saved
            f.transport.active = false
            f.store.connect(p.id.toString()) { listOf(p) }
            assertEquals(OrbisAtlasBootstrapPhase.REVOKED, f.store.state.value.phase)
            assertEquals(old, f.atlasStorage.saved)
            f.store.retry { listOf(p) }
            assertEquals(1, f.transport.registers)
            assertNull(f.atlas.readCredential())
        }
    }

    @Test fun failedPendingDurabilityPreventsAnyRegister() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.storage.failAt = 1
            f.store.connect(p.id.toString()) { listOf(p) }
            assertEquals(0, f.transport.registers)
            assertNull(f.storage.saved)
            assertNull(f.atlas.readCredential())
            assertFalse(f.store.state.value.error!!.contains("private"))
        }
    }

    @Test fun installedButUnacknowledgedIntentRemainsGatedAndExactRetryFinishesWithoutOverwritingOtherConfig() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.storage.failAt = 3
            f.store.connect(p.id.toString()) { listOf(p) }
            assertEquals(OrbisAtlasBootstrapPhase.PENDING, f.store.state.value.phase)
            val installed = f.atlasStorage.saved
            assertNotNull(installed)
            assertNull(f.atlas.readCredential())
            f.restart()
            f.store.retry { listOf(p) }
            assertEquals(OrbisAtlasBootstrapPhase.ACTIVE, f.store.state.value.phase)
            assertEquals(installed, f.atlasStorage.saved)
            assertEquals(f.transport.requests[0], f.transport.requests[1])
        }
    }

    @Test fun revokeUnknownRetainsSoleCredentialAndExactRetryDoesNotNeedProvider() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.store.connect(p.id.toString()) { listOf(p) }
            val token = f.atlas.readCredential()!!.token
            f.transport.failRevoke = true
            f.store.revoke()
            assertEquals(OrbisAtlasBootstrapPhase.REVOKING, f.store.state.value.phase)
            assertTrue(f.storage.saved!!.contains(token))
            assertNull(f.atlas.readCredential())
            f.restart()
            assertEquals(1, f.transport.revokes)
            f.transport.failRevoke = false
            f.store.revoke()
            assertEquals(f.transport.revocations[0], f.transport.revocations[1])
            assertEquals(OrbisAtlasBootstrapPhase.REVOKED, f.store.state.value.phase)
            assertFalse(f.atlas.state.value.configured)
            assertTrue(f.storage.saved!!.contains(token))
            f.store.forgetLocal()
            assertNull(f.storage.saved)
            assertEquals(OrbisAtlasBootstrapPhase.NONE, f.store.state.value.phase)
        }
    }

    @Test fun managedCredentialCannotBeOverwrittenByManualSaveOrClear() = runBlocking {
        Fixture().use { f ->
            val p = provider()
            f.store.connect(p.id.toString()) { listOf(p) }
            val saved = f.atlasStorage.saved
            f.store.saveManual("https://other.example", oldToken, true)
            assertEquals(saved, f.atlasStorage.saved)
            f.store.clearManual()
            assertEquals(saved, f.atlasStorage.saved)
            f.store.setEnabled(false)
            assertNull(f.atlas.readCredential())
            f.store.setEnabled(true)
            assertNotNull(f.atlas.readCredential())
        }
    }

    @Test fun corruptIntentFailsClosedAndFailedForgetDoesNotUnlockWrites() = runBlocking {
        Fixture().use { f ->
            f.atlas.save("https://manual.example", oldToken, true)
            val saved = f.atlasStorage.saved
            f.storage.saved = "corrupt encrypted payload fixture"
            f.restart()
            assertEquals(OrbisAtlasBootstrapPhase.UNREADABLE, f.store.state.value.phase)
            assertNull(f.atlas.readCredential())
            f.storage.failAt = f.storage.writes + 1
            f.store.forgetLocal()
            assertEquals(OrbisAtlasBootstrapPhase.UNREADABLE, f.store.state.value.phase)
            f.store.saveManual("https://other.example", gatewayKey, true)
            assertEquals(saved, f.atlasStorage.saved)
            assertEquals(0, f.transport.registers + f.transport.revokes + f.transport.capabilities)
        }
    }
}
