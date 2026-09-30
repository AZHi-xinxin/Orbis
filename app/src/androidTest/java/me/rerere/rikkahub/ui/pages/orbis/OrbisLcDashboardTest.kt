package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.StrictMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import me.rerere.rikkahub.testutil.createShellComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.BridgeDashboard
import com.lover.connect.BridgeSection
import com.lover.connect.BootReceiver
import com.lover.connect.LCAccessibilityService
import com.lover.connect.LcExternalRecoveryGate
import com.lover.connect.LocationSafetyRuntimeStore
import com.lover.connect.LocationSafetyManager
import com.lover.connect.LocationEventUploadReceiver
import com.lover.connect.McpService
import com.lover.connect.McpServiceController
import com.lover.connect.ScreenCaptureService
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real dashboard/module composition, but all LC storage is synthetic. Never
 * click an enable, permission, test-event, capture, import or control action.
 * Run only with -PorbisIsolatedTests=true / IsolatedGenerationLoopRunner.
 * No application initialization, production preferences, Keystore data,
 * service start/bind, permission request, alarm or network is required.
 */
@RunWith(AndroidJUnit4::class)
class OrbisLcDashboardTest {
    private val compose = createShellComposeRule()
    private var fixture: FixtureContext? = null
    private var previousPolicy: StrictMode.ThreadPolicy? = null
    private val isolation = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
            assertFalse("Real external recovery must not bypass fixture storage", LcExternalRecoveryGate.isAllowed())
            assertNull("Do not run this fixture inside an active LC service process", McpService.instance)
            assertNull(LCAccessibilityService.instance)
            assertFalse(ScreenCaptureService.isReady())
        }

        override fun after() {
            // RuleChain closes the activity/composition before deleting fixture-only files.
            previousPolicy?.let { policy ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { StrictMode.setThreadPolicy(policy) }
            }
            fixture?.dispose()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolation).around(compose)

    private val markers = linkedMapOf(
        BridgeSection.CONNECTION to "陪伴服务（兼容 MCP）",
        BridgeSection.VISION to "视觉API配置",
        BridgeSection.REST to "同一非聊天应用连续使用门槛（60–1440 分钟）",
        BridgeSection.LOCATION to "安全位置播报",
        BridgeSection.CONTEXT to "设备情境（实验性）",
        BridgeSection.CONTROLS to "应用限制与回到 Orbis",
        BridgeSection.SENTINEL to "保存哨兵配置",
        BridgeSection.LOCAL to "天气城市",
    )

    private fun show(widthDp: Int = 390, fontScale: Float = 1f, trackingPreviouslyEnabled: Boolean = false) {
        val context = FixtureContext(compose.activity)
        fixture = context
        if (trackingPreviouslyEnabled) LocationSafetyRuntimeStore(context).setTracking(true)
        compose.runOnUiThread {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previousPolicy!!)
                .detectNetwork().penaltyDeathOnNetwork().build())
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context,
                LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(widthDp.dp)) { BridgeDashboard() }
                }
            }
        }
    }

    private fun open(section: BridgeSection) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(section.title))
        compose.onNodeWithText(section.title).performClick()
        compose.onNodeWithText("‹ 返回手机与陪伴").assertIsDisplayed()
    }

    private fun assertNoSideEffects() {
        compose.runOnIdle {
            val context = fixture!!
            assertEquals(0, context.forbiddenOperations.get())
            assertNull(McpService.instance)
            assertNull(LCAccessibilityService.instance)
            assertFalse(ScreenCaptureService.isReady())
            assertFalse(McpServiceController.isEnabled(context))
            assertFalse(context.getSharedPreferences("lc_config", 0).getBoolean("eyes_enabled", false))
            assertFalse(context.getSharedPreferences("lc_config", 0).getBoolean("sentinel_enabled", false))
            assertTrue(context.getSharedPreferences("lc_location_secure", 0).all.isEmpty())
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "lc-location-uploader" })
        }
    }

    @Test fun allEightTilesOpenOnlyTheirOwnModuleAndReturnHome() {
        show()
        for ((section, marker) in markers) {
            open(section)
            compose.onNodeWithText(marker).performScrollTo().assertIsDisplayed()
            markers.filterKeys { it != section }.values.forEach { other ->
                compose.onNodeWithText(other).assertDoesNotExist()
            }
            assertNoSideEffects()
            compose.onNodeWithText("‹ 返回手机与陪伴").performClick()
            compose.onNodeWithText("手机与陪伴").assertIsDisplayed()
            compose.onNodeWithText("‹ 返回手机与陪伴").assertDoesNotExist()
        }
        assertNoSideEffects()
        assertTrue(fixture!!.databaseList().contains("lc_location_events.db"))
    }

    private fun assertColumns(expected: Int) {
        val first = compose.onNodeWithText(BridgeSection.CONNECTION.title).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(BridgeSection.VISION.title).fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithText(BridgeSection.REST.title).fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.left)
        if (expected == 3) {
            assertEquals(first.top, third.top, 1f)
            assertTrue(third.left > second.left)
        } else {
            assertTrue(third.top > first.top)
            assertEquals(first.left, third.left, 1f)
        }
        assertNoSideEffects()
    }

    @Test fun normalPhoneWidthUsesThreeColumns() { show(widthDp = 390); assertColumns(3) }
    @Test fun narrowPhoneWidthUsesTwoColumns() { show(widthDp = 320); assertColumns(2) }
    @Test fun largeTextUsesTwoColumns() { show(widthDp = 390, fontScale = 1.3f); assertColumns(2) }

    @Test fun companionCopyNamesTheHostWithoutHidingTheConnectionBoundary() {
        show()
        compose.onNodeWithText("内嵌陪伴功能已分组收纳", substring = true).assertIsDisplayed()
        open(BridgeSection.CONNECTION)
        compose.onNodeWithText("内嵌陪伴功能 · Orbis 独立配置与授权", substring = true).assertIsDisplayed()
        compose.onNodeWithText("兼容 MCP 地址（仅供外部客户端；Orbis 原生无需填写）：")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("不需复制此地址", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("内嵌 LC MCP 地址（在 Orbis 工具连接中使用）：").assertDoesNotExist()
        assertNoSideEffects()
    }

    @Test fun browsingPreviouslyEnabledLocationDoesNotRestoreOrUpload() {
        show(trackingPreviouslyEnabled = true)
        open(BridgeSection.LOCATION)
        compose.onNodeWithText("安全位置播报").assertIsDisplayed()
        // The host intentionally removed the legacy page's restoreAfterBoot
        // side effect; BootReceiver and explicit start/resume remain separate.
        compose.mainClock.advanceTimeBy(3_000)
        assertNoSideEffects()
        assertTrue(LocationSafetyRuntimeStore(fixture!!).isTrackingEnabled())
    }

    @Test fun productionDefaultOffRecoveryDoesNotCreateQueueOrWorker() {
        show()
        val context = fixture!!
        // Call the production restore function directly, bypassing the receiver
        // test-process gate. All storage still belongs to this empty fixture.
        LocationSafetyManager.restoreAfterBoot(context)
        assertNoSideEffects()
        assertTrue("No empty event database should be created by recovery", context.databaseList().isEmpty())
    }

    @Test fun externalRecoveryGateReturnsBeforeAccessingAnyHostContext() {
        show()
        val forbiddenContext = object : ContextWrapper(compose.activity) {
            override fun getApplicationContext(): Context = error("Receiver escaped test isolation")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("Receiver touched real preferences")
            override fun getSystemService(name: String): Any? = error("Receiver touched a system service")
        }
        BootReceiver().onReceive(forbiddenContext, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        LocationEventUploadReceiver().onReceive(forbiddenContext, Intent("com.lover.connect.location.UPLOAD_RETRY"))
        assertNoSideEffects()
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        private val ownerCache = base.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(ownerCache.toPath(), "orbis-lc-fixture-").toFile().canonicalFile
        private val preferences = ConcurrentHashMap<String, SharedPreferences>()
        val forbiddenOperations = AtomicInteger()
        private fun forbidden(): Nothing {
            forbiddenOperations.incrementAndGet()
            throw AssertionError("LC browsing attempted a forbidden system operation")
        }
        private fun child(folder: String): File = File(root, folder).also { it.mkdirs() }
        private fun safeName(name: String): String {
            require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
            return name
        }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = child("files")
        override fun getCacheDir(): File = child("cache")
        override fun getCodeCacheDir(): File = child("code-cache")
        override fun getNoBackupFilesDir(): File = child("no-backup")
        override fun getDir(name: String, mode: Int): File = child("dir-${safeName(name)}")
        override fun getFileStreamPath(name: String): File = File(filesDir, safeName(name))
        override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
        override fun openFileOutput(name: String, mode: Int): FileOutputStream = FileOutputStream(getFileStreamPath(name), false)
        override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
        override fun fileList(): Array<String> = filesDir.list()?.map { it }?.toTypedArray() ?: emptyArray()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferences.getOrPut(safeName(name)) { MemoryPreferences() }
        override fun getDatabasePath(name: String): File = File(child("databases"), safeName(name))
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun databaseList(): Array<String> = child("databases").list()?.map { it }?.toTypedArray() ?: emptyArray()
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
        override fun startService(service: Intent): ComponentName? = forbidden()
        override fun startForegroundService(service: Intent): ComponentName? = forbidden()
        override fun stopService(name: Intent): Boolean = forbidden()
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = forbidden()
        override fun startActivity(intent: Intent): Unit = forbidden()
        override fun startActivity(intent: Intent, options: Bundle?): Unit = forbidden()
        override fun getSystemService(name: String): Any? = when (name) {
            ALARM_SERVICE, LOCATION_SERVICE, SENSOR_SERVICE, MEDIA_PROJECTION_SERVICE,
            DEVICE_POLICY_SERVICE, CLIPBOARD_SERVICE -> forbidden()
            else -> super.getSystemService(name)
        }
        fun dispose() {
            check(root.parentFile == ownerCache && root.name.startsWith("orbis-lc-fixture-"))
            check(root.deleteRecursively()) { "Could not remove the owned synthetic fixture directory" }
        }
    }

    /** No call is forwarded to the real application's SharedPreferences. */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        @Synchronized override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
        @Synchronized override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        @Synchronized override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        @Synchronized override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        @Synchronized override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        @Synchronized override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listener?.let(listeners::add) }
        @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
        private inner class Editor : SharedPreferences.Editor {
            private val writes = mutableMapOf<String, Any?>()
            private var clearAll = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { writes[requireNotNull(key)] = value }
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                synchronized(this@MemoryPreferences) {
                    if (clearAll) values.clear()
                    writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    writes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPreferences, key) } }
                }
                return true
            }
        }
    }
}
