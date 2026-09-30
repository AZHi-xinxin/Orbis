package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.CompanionAlarmRecord
import com.lover.connect.CompanionAlarmStore
import com.lover.connect.LcExternalRecoveryGate
import com.lover.connect.McpService
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.companiontools.createCompanionTools
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only alarm query through the real Android adapter. The fixture owns only a random cache
 * directory and a synthetic preference namespace; it must never schedule/cancel an alarm, ring,
 * or start/stop the companion service.
 */
@RunWith(AndroidJUnit4::class)
class CompanionAlarmQueryDeviceTest {
    private lateinit var owner: Context
    private lateinit var cacheRoot: File
    private lateinit var directory: File
    private lateinit var testContext: Context
    private lateinit var preferenceName: String
    private val serviceStartAttempts = AtomicInteger()
    private val serviceStopAttempts = AtomicInteger()

    @Before fun createOwnedReadOnlyFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        owner = instrumentation.targetContext
        assertEquals(Application::class.java, owner.applicationContext.javaClass)
        assertEquals("org.orbis.agent.dev", owner.packageName)
        assertFalse(LcExternalRecoveryGate.isAllowed())
        assertNull(McpService.instance)

        cacheRoot = owner.cacheDir.canonicalFile
        val fixtureId = UUID.randomUUID().toString()
        directory = File(cacheRoot, "stage-alarm-query-$fixtureId").canonicalFile
        check(directory.parentFile == cacheRoot && !directory.exists())
        check(directory.mkdir())
        preferenceName = "stage-alarm-query-$fixtureId-lc_config"

        testContext = object : ContextWrapper(owner) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getCacheDir(): File = directory
            override fun getNoBackupFilesDir(): File = directory

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "lc_config") { "Unexpected preference access: $name" }
                check(mode == Context.MODE_PRIVATE)
                return owner.getSharedPreferences(preferenceName, mode)
            }

            override fun getSystemService(name: String): Any? {
                check(name in setOf(Context.ALARM_SERVICE, Context.NOTIFICATION_SERVICE, Context.AUDIO_SERVICE)) {
                    "Alarm query attempted unexpected device service: $name"
                }
                return owner.getSystemService(name)
            }

            override fun startService(service: Intent): ComponentName? = forbiddenServiceStart()
            override fun startForegroundService(service: Intent): ComponentName? = forbiddenServiceStart()
            override fun stopService(service: Intent): Boolean {
                serviceStopAttempts.incrementAndGet()
                throw AssertionError("Alarm query must never stop a device service")
            }
        }
    }

    @After fun removeOnlyOwnedFixture() {
        try {
            if (::preferenceName.isInitialized) {
                check(preferenceName.startsWith("stage-alarm-query-") && preferenceName.endsWith("-lc_config"))
                owner.deleteSharedPreferences(preferenceName)
            }
        } finally {
            if (::directory.isInitialized && directory.exists()) {
                check(directory.canonicalFile.parentFile == cacheRoot)
                check(directory.name.startsWith("stage-alarm-query-"))
                check(directory.deleteRecursively())
            }
        }
        assertEquals(0, serviceStartAttempts.get())
        assertEquals(0, serviceStopAttempts.get())
        assertNull(McpService.instance)
        assertFalse(LcExternalRecoveryGate.isAllowed())
    }

    @Test fun historicalAlarmQueryLeavesLedgerByteExactAndNeedsNoApprovalOrService() = runBlocking {
        val now = System.currentTimeMillis()
        val historical = CompanionAlarmRecord(
            id = UUID.randomUUID().toString(),
            hour = 8,
            minute = 15,
            message = "synthetic historical alarm",
            triggerAt = now - 120_000,
            timeZone = ZoneId.systemDefault().id,
            createdAt = now - 180_000,
            updatedAt = now - 60_000,
            status = "fired",
            detail = "receiver_received",
        )
        CompanionAlarmStore(directory).put(historical)
        val ledger = File(directory, CompanionAlarmStore.FILE_NAME)
        val before = ledger.readBytes()
        val tool = createCompanionTools(testContext, setOf("get_alarms")).single()
        val arguments = buildJsonObject { put("include_history", true); put("limit", 30) }

        assertEquals("companion_get_alarms", tool.name)
        assertFalse(tool.needsApproval(arguments))
        assertNull(tool.hostApproval)
        val outer = execute(tool, arguments)
        assertTrue(outer.getValue("ok").jsonPrimitive.boolean)
        assertEquals("completed", outer.getValue("outcome").jsonPrimitive.content)
        val content = Json.parseToJsonElement(outer.getValue("content").jsonPrimitive.content).jsonObject
        assertTrue(content.getValue("read_only").jsonPrimitive.boolean)
        assertEquals("orbis_companion_alarm_ledger", content.getValue("scope").jsonPrimitive.content)
        val alarm = content.getValue("alarms").jsonArray.single().jsonObject
        assertEquals(historical.id, alarm.getValue("alarm_id").jsonPrimitive.content)
        assertEquals("fired", alarm.getValue("state").jsonPrimitive.content)
        assertFalse(alarm.getValue("pending_token_exists").jsonPrimitive.boolean)
        assertFalse(alarm.getValue("system_queue_verified").jsonPrimitive.boolean)
        assertArrayEquals(before, ledger.readBytes())
        assertEquals(0, serviceStartAttempts.get())
        assertEquals(0, serviceStopAttempts.get())
        assertNull(McpService.instance)
    }

    @Test fun emptyQueryDoesNotCreateAnAlarmLedger() = runBlocking {
        assertTrue(directory.listFiles()!!.isEmpty())
        val tool = createCompanionTools(testContext, setOf("get_alarms")).single()

        val outer = execute(tool, JsonObject(emptyMap()))

        assertTrue(outer.getValue("ok").jsonPrimitive.boolean)
        val content = Json.parseToJsonElement(outer.getValue("content").jsonPrimitive.content).jsonObject
        assertTrue(content.getValue("alarms").jsonArray.isEmpty())
        assertFalse(File(directory, CompanionAlarmStore.FILE_NAME).exists())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun corruptLedgerFailsClosedWithoutReplacingOrRepairingIt() = runBlocking {
        val ledger = File(directory, CompanionAlarmStore.FILE_NAME)
        ledger.writeText("broken synthetic alarm ledger", Charsets.UTF_8)
        val before = ledger.readBytes()
        val tool = createCompanionTools(testContext, setOf("get_alarms")).single()

        val result = execute(tool, JsonObject(emptyMap()))

        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("execution_failed", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertArrayEquals(before, ledger.readBytes())
        assertEquals(setOf(CompanionAlarmStore.FILE_NAME), directory.listFiles()!!.map { it.name }.toSet())
    }

    private suspend fun execute(
        tool: me.rerere.ai.core.Tool,
        arguments: JsonObject,
    ): JsonObject {
        val part = tool.execute(arguments).single() as UIMessagePart.Text
        return Json.parseToJsonElement(part.text).jsonObject
    }

    private fun forbiddenServiceStart(): Nothing {
        serviceStartAttempts.incrementAndGet()
        throw AssertionError("Alarm query must never start a device service")
    }
}
