package me.rerere.rikkahub.ui.pages.orbis

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAvailability
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultRequestStatus
import me.rerere.rikkahub.data.orbis.privacy.AndroidPrivateVaultKeyProtector
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultKeyProtector
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultRepository
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultException
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PrivateRoomUiTest {
    @get:Rule val compose = createShellComposeRule()
    private lateinit var directory: File
    private lateinit var cacheRoot: File
    private lateinit var repo: PrivateVaultRepository
    private val owner = UUID.randomUUID().toString()
    private val anotherOwner = UUID.randomUUID().toString()
    private val newOwner = UUID.randomUUID().toString()
    private val repositories = linkedMapOf<String, PrivateVaultRepository>()
    private val testAliases = linkedSetOf<String>()
    private val visible = mutableStateOf(true)
    private val networkCalls = AtomicInteger()
    private lateinit var networkServer: ServerSocket
    private lateinit var networkThread: Thread
    private lateinit var settings: Settings
    private lateinit var testLifecycle: LifecycleRegistry
    private var shown = false

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheRoot = context.cacheDir.canonicalFile
        directory = Files.createTempDirectory(cacheRoot.toPath(), "private-ui-").toFile().canonicalFile
        check(directory.parentFile == cacheRoot)
        listOf(owner, anotherOwner, newOwner).forEach { id ->
            repositories[id] = PrivateVaultRepository(File(directory, id), id, TrackedAndroidProtector(),
                elapsedNow = android.os.SystemClock::elapsedRealtime)
        }
        repo = repositories.getValue(owner)
        repo.create(); repo.confirmRecoverySaved(); repo.setEnabled(true)
        repo.openAiSession("synthetic-ui").use { it.writeRecord("SYNTHETIC_HIDDEN_TITLE", "SYNTHETIC_HIDDEN_BODY") }
        repositories.getValue(anotherOwner).apply {
            create(); confirmRecoverySaved(); setEnabled(true)
            openAiSession("synthetic-other").use { it.writeRecord("OTHER_PRIVATE_TITLE", "OTHER_PRIVATE_BODY") }
        }
        // The configured model has a synthetic loopback endpoint. Any accidental independent
        // model/capability request is counted and gets a fixed local rejection, never the internet.
        networkServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        networkThread = Thread {
            while (!networkServer.isClosed) {
                try { networkServer.accept().use { socket ->
                    networkCalls.incrementAndGet()
                    socket.getOutputStream().write("HTTP/1.1 503 Synthetic\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } } catch (_: SocketException) { if (!networkServer.isClosed) throw AssertionError("Synthetic listener failed") }
            }
        }.apply { isDaemon = true; start() }
        val model = Model(modelId = "synthetic-ui-model", displayName = "合成当前模型")
        settings = Settings(init = false, assistantId = Uuid.parse(owner), chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${networkServer.localPort}/v1",
                apiKey = "synthetic-not-a-real-key", models = listOf(model))), assistants = listOf(
            Assistant(id = Uuid.parse(owner), name = "合成测试助手"),
            Assistant(id = Uuid.parse(anotherOwner), name = "另一位合成助手"),
            Assistant(id = Uuid.parse(newOwner), name = "新房间合成助手"),
        ))
    }
    @After fun tearDown() {
        try {
            // Dispose the viewer/polling job before removing this fixture's encrypted files/keys.
            if (shown) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            if (::networkServer.isInitialized) networkServer.close()
            if (::networkThread.isInitialized) networkThread.join(2_000)
            try {
                val store = keyStore()
                testAliases.forEach { alias ->
                    check(alias.matches(Regex("orbis-private-vault-v1-[0-9a-f]{64}")))
                    store.deleteEntry(alias) // Only aliases minted by this random synthetic fixture.
                }
            } finally {
                if (::directory.isInitialized) {
                    check(directory.canonicalFile.parentFile == cacheRoot)
                    check(directory.name.startsWith("private-ui-"))
                    check(directory.deleteRecursively())
                }
            }
        }
    }

    private fun show(fontScale: Float = 1f) {
        val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = testLifecycle
        }
        compose.runOnUiThread {
            testLifecycle = LifecycleRegistry(lifecycleOwner).apply { currentState = Lifecycle.State.RESUMED }
        }
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current.density
            val activityContext = LocalContext.current
            val fixtureContext = remember(activityContext) { object : ContextWrapper(activityContext) {
                override fun getApplicationContext(): Context = this
                override fun getDataDir(): File = activityContext.dataDir.canonicalFile
                override fun getNoBackupFilesDir(): File = File(directory, "ui-no-backup")
            } }
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner,
                LocalContext provides fixtureContext,
                LocalDensity provides Density(density, fontScale)) {
                if (visible.value) OrbisPrivateRoomPage(owner, "合成测试助手", { visible.value = false },
                    repo, { settings }, { repositories.getValue(it) })
            }
        } }
        shown = true
        scrollToText("隐私室已开启 · 内容仍上锁")
    }

    private fun scrollToText(text: String) {
        // LazyColumn may not have composed an offscreen item yet. Search through its scroll
        // semantics rather than requiring the destination node to exist before scrolling.
        compose.waitUntil(10_000) {
            try {
                // Fixed notices are intentionally outside the scrolling form.
                if (compose.onAllNodesWithText(text).fetchSemanticsNodes().any()) {
                    try { compose.onNodeWithText(text).assertIsDisplayed(); return@waitUntil true }
                    catch (_: AssertionError) { /* Find an offscreen form item below. */ }
                }
                compose.onNodeWithTag("private-room-list").performScrollToNode(hasText(text))
                compose.onNodeWithText(text).assertIsDisplayed()
                true
            } catch (_: AssertionError) { false }
        }
    }

    @Test fun ordinaryLockedViewContainsNoPrivateTitleOrBody() {
        show()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
        openSettings()
        scrollToText("暂停并撤销当前许可")
        compose.onNodeWithText("暂停并撤销当前许可").performClick()
        compose.waitUntil(10_000) { !repo.status().enabled }
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
    }

    @Test fun approvedContentRequiresExplicitOpenAndDisappearsAfterRevocation() {
        val request = repo.requestAccess("合成界面验收", accessDurationMs = 60_000)
        repo.openAiSession("synthetic-review").use { it.decideAccess(request.id, true) }
        show()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
        openRequests()
        scrollToText("查看本次获准内容")
        compose.onNodeWithText("查看本次获准内容").performClick()
        scrollToText("SYNTHETIC_HIDDEN_TITLE")
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").performClick()
        scrollToText("SYNTHETIC_HIDDEN_BODY")
        repo.revokeAccess(request.id)
        scrollToText("许可已到期或撤销，内容已收起。")
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
    }

    private fun openSettings() {
        scrollToText("房间设置")
        compose.onNodeWithText("房间设置").performClick()
    }

    private fun openRequests() {
        scrollToText("填写进入申请")
        compose.onNodeWithText("填写进入申请").performClick()
    }

    @Test fun homeHasThreeActionsAndNoConnectionOrRecoveryControls() {
        show()
        listOf("选择隐私室主人", "进入申请", "新建隐私室").forEach(::scrollToText)
        compose.onNodeWithText("隐私室专用模型（手机端独立处理）").assertDoesNotExist()
        compose.onNodeWithText("重新签发恢复码").assertDoesNotExist()
        compose.onNodeWithText("提交进入申请").assertDoesNotExist()
        assertEquals(0, networkCalls.get())
        assertEquals(Uuid.parse(owner), settings.assistantId)
    }

    @Test fun selectingAnotherOwnerDoesNotSwitchChatOrCopyPrivateRecords() {
        show()
        scrollToText("选择主人")
        compose.onNodeWithText("选择主人").performClick()
        scrollToText("另一位合成助手")
        compose.onNodeWithText("另一位合成助手").performClick()
        compose.onNodeWithText("另一位合成助手的隐私室").assertIsDisplayed()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        compose.onNodeWithText("OTHER_PRIVATE_TITLE").assertDoesNotExist()
        scrollToText("暂停并撤销当前许可")
        compose.onNodeWithText("暂停并撤销当前许可").performClick()
        compose.waitUntil(10_000) { !repositories.getValue(anotherOwner).status().enabled }
        assertTrue(repo.status().enabled)
        assertEquals(Uuid.parse(owner), settings.assistantId)
        assertEquals(0, networkCalls.get())
    }

    @Test fun newRoomRequiresCreateThenOfflineConfirmationThenSeparateEnable() {
        show()
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performClick()
        val fresh = repositories.getValue(newOwner)
        assertEquals(PrivateVaultAvailability.ABSENT, fresh.status().availability)
        scrollToText("创建加密隐私室")
        compose.onNodeWithText("隐私室专用模型（手机端独立处理）").assertDoesNotExist()
        compose.onNodeWithText("创建加密隐私室").performClick()
        compose.waitUntil(10_000) { fresh.status().availability == PrivateVaultAvailability.READY }
        assertFalse(fresh.status().enabled)
        assertFalse(fresh.status().recoveryConfirmed)
        compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
        compose.onNodeWithTag("private-room-recovery-code").assertExists()
        scrollToText("我已在应用外安全保管恢复码")
        compose.onNodeWithText("我已在应用外安全保管恢复码").performClick()
        compose.waitUntil(10_000) { fresh.status().recoveryConfirmed }
        assertFalse(fresh.status().enabled)
        scrollToText("开启隐私室")
        compose.onNodeWithText("开启隐私室").performClick()
        compose.waitUntil(10_000) { fresh.status().enabled }
        assertEquals(0, networkCalls.get())
        assertEquals(1, repo.status().recordCount)
        assertEquals(Uuid.parse(owner), settings.assistantId)
    }

    @Test fun largeFontCreateCanBeTouchedWithoutConfiguringAModel() {
        show(fontScale = 2f)
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performTouchInput { click(center) }
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performTouchInput { click(center) }
        scrollToText("创建加密隐私室")
        compose.onNodeWithText("隐私室专用模型（手机端独立处理）").assertDoesNotExist()
        compose.onNodeWithText("创建加密隐私室").performTouchInput { click(center) }
        val fresh = repositories.getValue(newOwner)
        compose.waitUntil(10_000) { fresh.status().availability == PrivateVaultAvailability.READY }
        scrollToText("我已在应用外安全保管恢复码")
        assertFalse(fresh.status().enabled)
        assertFalse(fresh.status().recoveryConfirmed)
        assertEquals(0, networkCalls.get())
    }

    @Test fun failedCreationShowsSafeFixedReasonAndDoesNotRetryOrClaimSuccess() {
        val attempts = AtomicInteger()
        repositories[newOwner] = PrivateVaultRepository(File(directory, "failed-create"), newOwner,
            object : PrivateVaultKeyProtector {
                override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
                    attempts.incrementAndGet()
                    throw PrivateVaultException("device_key_unavailable")
                }
                override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray =
                    throw AssertionError("No key should have been saved")
            })
        show(fontScale = 2f)
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performClick()
        scrollToText("创建加密隐私室")
        compose.onNodeWithText("创建加密隐私室").performTouchInput { click(center) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("device_key_unavailable", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("private-room-notice").assertIsDisplayed()
        compose.onNodeWithText("device_key_unavailable", substring = true).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(1, attempts.get())
        assertEquals(PrivateVaultAvailability.ABSENT, repositories.getValue(newOwner).status().availability)
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        compose.onNodeWithText("开启隐私室").assertDoesNotExist()
        assertEquals(1, repo.status().recordCount)
        assertEquals(0, networkCalls.get())
    }

    @Test fun creationProgressRemainsVisibleAndPreventsRepeatedPhysicalClicks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        repositories[newOwner] = PrivateVaultRepository(File(directory, "blocked-create"), newOwner,
            object : PrivateVaultKeyProtector {
                override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
                    attempts.incrementAndGet(); entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Synthetic fixture deadline" }
                    throw PrivateVaultException("device_key_unavailable")
                }
                override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray =
                    throw AssertionError("No synthetic key saved")
            })
        show()
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performClick()
        scrollToText("创建加密隐私室")
        try {
            compose.onNodeWithText("创建加密隐私室").performTouchInput { click(center) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("private-room-progress").assertIsDisplayed()
            compose.onNodeWithText("创建加密隐私室").assertIsNotEnabled()
            compose.onNodeWithText("创建加密隐私室").performTouchInput { click(center) }
            assertEquals(1, attempts.get())
        } finally {
            release.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("device_key_unavailable", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithTag("private-room-progress").assertDoesNotExist()
        assertEquals(1, attempts.get())
        assertEquals(0, networkCalls.get())
    }

    @Test fun backgroundDuringRecoveryStepClearsCodeWithoutConfirmingOrEnabling() {
        show()
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performClick()
        scrollToText("创建加密隐私室")
        compose.onNodeWithText("创建加密隐私室").performClick()
        val fresh = repositories.getValue(newOwner)
        compose.waitUntil(10_000) { fresh.status().availability == PrivateVaultAvailability.READY }
        scrollToText("我已在应用外安全保管恢复码")
        compose.onNodeWithTag("private-room-recovery-code").assertExists()
        compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        compose.onNodeWithText("我已在应用外安全保管恢复码").assertDoesNotExist()
        assertFalse(fresh.status().recoveryConfirmed)
        assertFalse(fresh.status().enabled)
        assertEquals(0, networkCalls.get())
    }

    @Test fun foregroundRecheckNeverOffersStaleAbsentWhileCommittedRoomIsUnreadable() {
        val rechecking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failRead = AtomicBoolean(false)
        val creations = AtomicInteger()
        val actual = TrackedAndroidProtector()
        val fresh = PrivateVaultRepository(File(directory, "foreground-check"), newOwner,
            object : PrivateVaultKeyProtector {
                override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
                    creations.incrementAndGet()
                    return actual.wrap(vaultId, dataKey)
                }
                override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray {
                    if (failRead.get()) {
                        rechecking.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Synthetic fixture deadline" }
                        throw IllegalStateException("Synthetic local read failure")
                    }
                    return actual.unwrap(vaultId, wrappedKey)
                }
            })
        repositories[newOwner] = fresh
        show()
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("新房间合成助手")
        compose.onNodeWithText("新房间合成助手").performClick()
        scrollToText("创建加密隐私室")
        compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.CREATED }
        // Simulate a synchronous commit completing after the prior UI lifetime was stopped.
        fresh.create()
        failRead.set(true)
        try {
            compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.RESUMED }
            assertTrue(rechecking.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
            scrollToText("正在检查空间状态；未确认不存在前不能创建。")
        } finally { release.countDown() }
        scrollToText("已有空间需要恢复或暂不可读；不会新建空空间覆盖原件。")
        compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        assertEquals(1, creations.get())
        assertEquals(0, networkCalls.get())
        failRead.set(false)
        assertEquals(PrivateVaultAvailability.READY, fresh.status().availability)
        assertFalse(fresh.status().recoveryConfirmed)
        assertFalse(fresh.status().enabled)
    }

    @Test fun noModelPickerOrSavedRouteIsReadOrExposedInRoomSettings() {
        val oldRoute = File(directory, "ui-no-backup/orbis-private-room-routes/$owner.json")
        check(oldRoute.parentFile!!.mkdirs())
        oldRoute.writeText("SYNTHETIC_LEGACY_ROUTE_MUST_STAY_UNUSED")
        val original = oldRoute.readBytes()
        show(); openSettings()
        scrollToText("沿用这位助手当前配置，无需另设模型或 ST 连接。")
        compose.onNodeWithText("隐私室专用模型（手机端独立处理）").assertDoesNotExist()
        compose.onNodeWithText("模型与连接（单独配置）").assertDoesNotExist()
        compose.onNodeWithText("确认专用模型").assertDoesNotExist()
        compose.onNodeWithText("另选模型").assertDoesNotExist()
        compose.onNodeWithText("SYNTHETIC_LEGACY_ROUTE_MUST_STAY_UNUSED").assertDoesNotExist()
        compose.onNodeWithText("专用模型配置不可读，未自动改用其他连接。").assertDoesNotExist()
        compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
        Assert.assertArrayEquals(original, oldRoute.readBytes())
        assertEquals(1, repo.status().recordCount)
        assertEquals(0, networkCalls.get())
    }

    @Test fun changingOwnersChatModelKeepsTheSameEncryptedRoomAndRecords() {
        val before = repo.auditEvents()
        show(); openSettings()
        scrollToText("房间所属助手：合成测试助手")
        val replacement = Model(modelId = "synthetic-replacement", displayName = "合成替换模型")
        compose.runOnIdle {
            visible.value = false
            settings = settings.copy(chatModelId = replacement.id,
                providers = listOf((settings.providers.single() as ProviderSetting.OpenAI).copy(models = listOf(replacement))))
        }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        scrollToText("隐私室已开启 · 内容仍上锁")
        openSettings()
        scrollToText("房间所属助手：合成测试助手")
        assertEquals(Uuid.parse(owner), settings.assistantId)
        assertEquals(replacement.id, settings.chatModelId)
        assertEquals(before, repo.auditEvents())
        assertEquals(1, repo.status().recordCount)
        compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
        assertEquals(0, networkCalls.get())
    }

    @Test fun newActionOnExistingOwnerOnlyOpensSettingsAndDoesNotOverwrite() {
        show()
        val before = repo.auditEvents()
        scrollToText("选择助手新建")
        compose.onNodeWithText("选择助手新建").performClick()
        scrollToText("合成测试助手")
        compose.onNodeWithText("合成测试助手").performClick()
        scrollToText("隐私室已开启 · 内容仍上锁")
        compose.onNodeWithText("创建加密隐私室").assertDoesNotExist()
        assertEquals(before, repo.auditEvents())
        assertEquals(1, repo.status().recordCount)
        assertEquals(0, networkCalls.get())
    }

    @Test fun requestIsSavedOnceAndRefersBackToSameOwnerChatWithoutIndependentRequest() {
        show(); openRequests()
        scrollToText("申请目的（AI 可拒绝）")
        compose.onNodeWithText("申请目的（AI 可拒绝）").performTextInput("合成申请目的")
        scrollToText("提交进入申请")
        compose.onNodeWithText("提交进入申请").performClick()
        compose.waitUntil(10_000) { repo.requestsForHuman().size == 1 }
        compose.onNodeWithText("已提交。请回到合成测试助手的聊天，请他查看隐私室申请；没有自动发送消息或另起模型请求。").assertIsDisplayed()
        compose.onNodeWithText("请 AI 独立处理（会使用模型额度）").assertDoesNotExist()
        compose.onNodeWithText("请 AI 处理待批申请（使用模型额度）").assertDoesNotExist()
        scrollToText("已有待批申请。请回到这位助手的聊天，请他查看隐私室申请；无需反复提交。")
        compose.onNodeWithText("提交进入申请").assertIsNotEnabled()
        val request = repo.requestsForHuman().single()
        assertEquals(PrivateVaultRequestStatus.PENDING, request.status)
        scrollToText("刷新申请状态（不调用模型）")
        compose.onNodeWithText("刷新申请状态（不调用模型）").performClick()
        compose.waitForIdle()
        assertEquals(request.id, repo.requestsForHuman().single().id)
        assertEquals(0, networkCalls.get())
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        compose.onNodeWithText("批准").assertDoesNotExist()
    }

    @Test fun leavingRequestScreenAndBackgroundBothClearGrantedPlaintext() {
        val request = repo.requestAccess("合成收起验收", accessDurationMs = 60_000)
        repo.openAiSession("synthetic-review").use { it.decideAccess(request.id, true) }
        show(); openRequests()
        fun openBody() {
            scrollToText("查看本次获准内容")
            compose.onNodeWithText("查看本次获准内容").performClick()
            scrollToText("SYNTHETIC_HIDDEN_TITLE")
            compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").performClick()
            scrollToText("SYNTHETIC_HIDDEN_BODY")
        }
        openBody()
        compose.onNodeWithText("返回隐私室").performClick()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
        openRequests()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        openBody()
        compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_BODY").assertDoesNotExist()
        compose.runOnUiThread { testLifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText("SYNTHETIC_HIDDEN_TITLE").assertDoesNotExist()
        assertEquals(0, networkCalls.get())
    }

    @Test fun onlyExplicitlyApprovedRecordIsListedNotTheOtherPrivateTitle() {
        val ids = repo.openAiSession("synthetic-subset-records").use { session ->
            val allowed = session.listRecords().single().id
            val denied = session.writeRecord("NEVER_SHARED_TITLE", "NEVER_SHARED_BODY").id
            allowed to denied
        }
        val request = repo.requestAccess("只分享 AI 选定条目", accessDurationMs = 60_000)
        repo.openAiSession("synthetic-subset-review").use { it.decideAccess(request.id, true, listOf(ids.first)) }
        show(); openRequests()
        scrollToText("查看本次获准内容")
        compose.onNodeWithText("查看本次获准内容").performClick()
        scrollToText("SYNTHETIC_HIDDEN_TITLE")
        compose.onNodeWithText("NEVER_SHARED_TITLE").assertDoesNotExist()
        compose.onNodeWithText("NEVER_SHARED_BODY").assertDoesNotExist()
        assertEquals(listOf(ids.first), repo.requestsForHuman().single().recordIds)
        assertEquals(2, repo.status().recordCount)
        assertEquals(0, networkCalls.get())
    }

    private inner class TrackedAndroidProtector : PrivateVaultKeyProtector {
        private val actual = AndroidPrivateVaultKeyProtector()
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            check(UUID.fromString(vaultId).toString() == vaultId)
            val digest = MessageDigest.getInstance("SHA-256").digest(vaultId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val alias = "orbis-private-vault-v1-$digest"
            // An unexpected pre-existing alias is not ours and must never be removed by cleanup.
            check(alias in testAliases || !keyStore().containsAlias(alias))
            testAliases += alias
            return actual.wrap(vaultId, dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray) = actual.unwrap(vaultId, wrappedKey)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
