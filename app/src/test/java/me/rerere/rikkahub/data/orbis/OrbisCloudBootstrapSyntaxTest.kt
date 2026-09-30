package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Keep desktop validation aligned with the Android/ICU bootstrap regression fixture. */
class OrbisCloudBootstrapSyntaxTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val home = "https://golden-example-6db696.example.org/"
    @After fun closeScope() { scope.cancel() }

    private class MemoryPersistence(private val bootstrap: String) : OrbisCloudSettingsPersistence {
        var stored: String? = null
        var writes = 0
        override suspend fun read(): String? = stored
        override suspend fun write(value: String) { stored = value; writes++ }
        override suspend fun readBootstrap(): String = bootstrap
    }

    @Test fun literalClosingBraceWorksWithCompactAndFormattedJson() {
        listOf(
            """{"homeUrl":"$home"}""",
            "\r\n {\n \"homeUrl\" : \"https:\\/\\/golden-example-6db696.example.org\\/\"\n } \r\n",
        ).forEach { bootstrap ->
            val persistence = MemoryPersistence(bootstrap)
            val store = OrbisCloudSettingsStore(persistence, scope)
            assertNull(store.state.value.error)
            assertEquals(OrbisCloudHomeConfig(false, home), store.state.value.config)
            assertEquals(1, persistence.writes)
        }
    }

    @Test fun closingBraceFixDoesNotRelaxWholeObjectOrSingleKeyConstraint() {
        listOf(
            "{\"homeUrl\":\"$home\"",
            """{"homeUrl":"$home"}}""",
            """{"homeUrl":"$home",}""",
            """{"homeUrl":"$home"} trailing""",
            """{"homeUrl":"$home","enabled":true}""",
            """{"homeUrl":"$home","homeUrl":"$home"}""",
        ).forEach { bootstrap ->
            val persistence = MemoryPersistence(bootstrap)
            val store = OrbisCloudSettingsStore(persistence, scope)
            assertNotNull(store.state.value.error)
            assertEquals(OrbisCloudHomeConfig(), store.state.value.config)
            assertEquals(0, persistence.writes)
        }
    }
}
