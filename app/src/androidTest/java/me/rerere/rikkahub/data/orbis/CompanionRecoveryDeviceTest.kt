package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import com.lover.connect.McpService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.companiontools.createCompanionTools
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real native adapter, with synthetic preferences and an owned cache directory only. */
@RunWith(AndroidJUnit4::class)
class CompanionRecoveryDeviceTest {
    private lateinit var owner: Context
    private lateinit var cacheRoot: File
    private lateinit var directory: File
    private lateinit var preferencePrefix: String
    private lateinit var testContext: Context
    private var directoryCreated = false
    private val serviceStartAttempts = AtomicInteger()
    private val preferenceNames = setOf("lc_config", "lc_service_control", "lc_diagnostics")

    @Before fun createIsolatedFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        owner = instrumentation.targetContext
        assertEquals(Application::class.java, owner.applicationContext.javaClass)
        assertFalse("The isolated runner must close external recovery before Application startup", LcExternalRecoveryGate.isAllowed())
        assertNull("The fixture must not inherit a live companion service", McpService.instance)
        check(owner.packageName == "org.orbis.agent.dev")

        cacheRoot = owner.cacheDir.canonicalFile
        val fixtureId = UUID.randomUUID().toString()
        directory = File(cacheRoot, "stage26-companion-recovery-$fixtureId").canonicalFile
        check(directory.parentFile == cacheRoot && !directory.exists())
        check(directory.mkdir()) { "Could not create the owned recovery fixture cache directory" }
        directoryCreated = true
        preferencePrefix = "stage26-companion-recovery-$fixtureId-"
        testContext = object : ContextWrapper(owner) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getCacheDir(): File = directory
            override fun getNoBackupFilesDir(): File = directory

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name in preferenceNames) { "Unexpected preference access in isolated recovery test" }
                check(mode == Context.MODE_PRIVATE)
                return owner.getSharedPreferences(preferencePrefix + name, mode)
            }

            override fun getSystemService(name: String): Any? =
                error("Recovery diagnostics must not access device services")

            override fun startService(service: Intent): ComponentName? = forbiddenServiceStart()
            override fun startForegroundService(service: Intent): ComponentName? = forbiddenServiceStart()
            override fun stopService(service: Intent): Boolean =
                error("Recovery diagnostics must not stop device services")
        }
        // Even the seed data is confined to the prefix owned by this fixture.
        preferenceNames.forEach { assertTrue(prefs(it).all.isEmpty()) }
    }

    @After fun removeOnlyOwnedFixture() {
        try {
            if (::preferencePrefix.isInitialized) {
                check(preferencePrefix.startsWith("stage26-companion-recovery-"))
                preferenceNames.forEach { name ->
                    val ownedName = preferencePrefix + name
                    check(ownedName != name && ownedName.startsWith(preferencePrefix))
                    check(owner.deleteSharedPreferences(ownedName)) { "Could not remove owned synthetic preferences" }
                }
            }
        } finally {
            if (directoryCreated && directory.exists()) {
                check(directory.canonicalFile.parentFile == cacheRoot)
                check(directory.name.startsWith("stage26-companion-recovery-"))
                check(directory.deleteRecursively()) { "Could not remove owned synthetic cache directory" }
            }
        }
        assertEquals("No service start may escape the isolated context", 0, serviceStartAttempts.get())
        assertNull(McpService.instance)
        assertFalse(LcExternalRecoveryGate.isAllowed())
    }

    @Test fun unavailableServiceStillReturnsReadOnlyRuntimeStatus() = runBlocking {
        seedEnabled(true)
        val before = preferenceSnapshot()

        val result = execute("get_l_service_status")
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("completed", result.getValue("outcome").jsonPrimitive.content)
        val status = Json.parseToJsonElement(result.getValue("content").jsonPrimitive.content).jsonObject
        assertTrue(status.getValue("mcp_desired_enabled").jsonPrimitive.boolean)
        assertStoppedRuntime(status)
        assertEquals(before, preferenceSnapshot())
        assertEquals(0, serviceStartAttempts.get())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun disabledBatteryRequestDoesNotStartOrEnableService() = runBlocking {
        seedEnabled(false)
        val before = preferenceSnapshot()

        val result = execute("get_battery")
        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("service_disabled", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("not_started", result.getValue("outcome").jsonPrimitive.content)
        assertFalse(prefs("lc_service_control").getBoolean("mcp_enabled", true))
        assertEquals(before, preferenceSnapshot())
        assertEquals(0, serviceStartAttempts.get())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun persistedAliveAndReadyHistoryCannotClaimLiveReadiness() = runBlocking {
        seedEnabled(true)
        assertTrue(prefs("lc_diagnostics").edit()
            .putBoolean("mcp_service_alive", true)
            .putBoolean("native_runtime_ready", true)
            .putString("native_runtime_phase", "ready")
            .putLong("native_runtime_ready_at", 123_456L)
            .commit())
        val before = preferenceSnapshot()

        val result = execute("get_l_service_status")
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        val status = Json.parseToJsonElement(result.getValue("content").jsonPrimitive.content).jsonObject
        assertStoppedRuntime(status)
        assertFalse(status.getValue("diagnostic_history_is_live_state").jsonPrimitive.boolean)
        assertEquals(123_456L, status.getValue("native_runtime_ready_at_ms").jsonPrimitive.long)
        assertEquals(before, preferenceSnapshot())
        assertEquals(0, serviceStartAttempts.get())
    }

    private fun assertStoppedRuntime(status: JsonObject) {
        assertFalse(status.getValue("mcp_service_alive").jsonPrimitive.boolean)
        assertFalse(status.getValue("native_runtime_ready").jsonPrimitive.boolean)
        assertEquals("not_running", status.getValue("native_runtime_phase").jsonPrimitive.content)
        assertFalse(status.getValue("mcp_server_listening").jsonPrimitive.boolean)
    }

    private suspend fun execute(name: String): JsonObject {
        val tool = createCompanionTools(testContext, setOf(name)).single()
        assertEquals("companion_$name", tool.name)
        val result = tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text
        return Json.parseToJsonElement(result.text).jsonObject
    }

    private fun prefs(name: String): SharedPreferences = testContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun seedEnabled(enabled: Boolean) {
        assertTrue(prefs("lc_service_control").edit().putBoolean("mcp_enabled", enabled).commit())
    }

    private fun preferenceSnapshot(): Map<String, Map<String, *>> =
        preferenceNames.associateWith { prefs(it).all.toMap() }

    private fun forbiddenServiceStart(): Nothing {
        serviceStartAttempts.incrementAndGet()
        throw AssertionError("An isolated companion request must never start a device service")
    }
}
