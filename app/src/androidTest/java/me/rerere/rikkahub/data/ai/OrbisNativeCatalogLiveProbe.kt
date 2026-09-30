package me.rerere.rikkahub.data.ai

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolCatalogClient
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolCredentialStore
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolFamily
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit real-device metadata-only check; never selected by the fake UI regression suite. */
class OrbisNativeCatalogLiveProbe {
    @Test fun encryptedDeviceAuthorizationReachesAllThreeCatalogs() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbisProbeCatalog") == "metadata-only-v1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = instrumentation.targetContext.applicationContext
        check(context.packageName == "org.orbis.agent.dev")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = CloudToolCredentialStore(context, scope)
            store.reload()
            check(store.state.value.configured)
            val client = CloudToolCatalogClient(store)
            val expected = mapOf(CloudToolFamily.ORBIS to 23, CloudToolFamily.READING to 26, CloudToolFamily.TURTLESOUP to 8)
            for ((family, count) in expected) {
                val catalog = client.catalog(family)
                check(catalog.size == count && catalog.map { it.name }.toSet().size == count)
                check(catalog.all { it.effect in setOf("read", "write") && (it.effect == "read" || it.needsApproval) })
                check(catalog.none { it.name == "toggle_ledger_like" })
            }
            println("Device HTTPS metadata check passed: Orbis 23, reading 26, game 8. No business tools executed.")
        } catch (_: Exception) {
            throw AssertionError("Native metadata check failed; private response and credentials suppressed.")
        } finally { scope.cancel() }
    }
}
