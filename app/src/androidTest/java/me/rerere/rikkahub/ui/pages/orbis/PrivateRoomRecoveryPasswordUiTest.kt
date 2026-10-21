package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privacy.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Password interaction only: isolated Application, synthetic cache room, no model/network. */
@RunWith(AndroidJUnit4::class)
class PrivateRoomRecoveryPasswordUiTest {
    @get:Rule val compose = createShellComposeRule()
    private lateinit var directory: File
    private lateinit var cache: File
    private lateinit var repo: PrivateVaultRepository
    private lateinit var lifecycle: LifecycleRegistry
    private val owner = UUID.randomUUID().toString()
    private val aliases = linkedSetOf<String>()
    private val visible = mutableStateOf(true)
    private var shown = false
    private val password = "ORPV1-合成口令保留空格 "
    private val passwordTag = "private-room-password"
    private val confirmationTag = "private-room-password-confirm"

    @Before fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
        cache = inst.targetContext.cacheDir.canonicalFile
        directory = Files.createTempDirectory(cache.toPath(), "private-password-ui-").toFile().canonicalFile
        check(directory.parentFile == cache)
        repo = repository(TrackedProtector())
    }

    @After fun tearDown() {
        try {
            if (shown) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            try {
                val store = keyStore()
                aliases.forEach { name ->
                    check(name.matches(Regex("orbis-private-vault-v1-[0-9a-f]{64}")))
                    store.deleteEntry(name)
                }
            } finally {
                if (::directory.isInitialized) {
                    check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("private-password-ui-"))
                    check(directory.deleteRecursively())
                }
            }
        }
    }

    private fun show() {
        val lifecycleOwner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = this@PrivateRoomRecoveryPasswordUiTest.lifecycle }
        compose.runOnUiThread { lifecycle = LifecycleRegistry(lifecycleOwner).apply { currentState = Lifecycle.State.RESUMED } }
        val settings = Settings(init = false, assistantId = Uuid.parse(owner),
            assistants = listOf(Assistant(id = Uuid.parse(owner), name = "密码合成助手")))
        compose.setContent { MaterialTheme {
            val activityContext = LocalContext.current
            val fixtureContext = remember(activityContext) { object : ContextWrapper(activityContext) {
                override fun getApplicationContext(): Context = this
                override fun getDataDir(): File = activityContext.dataDir.canonicalFile
                override fun getNoBackupFilesDir(): File = File(directory, "ui-no-backup")
            } }
            CompositionLocalProvider(LocalContext provides fixtureContext, LocalLifecycleOwner provides lifecycleOwner) {
                if (visible.value) OrbisPrivateRoomPage(owner, "密码合成助手", { visible.value = false },
                    repo, { settings }, { check(it == owner); repo })
            }
        } }
        shown = true
        scroll(hasText("房间设置"))
    }

    private fun scroll(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) {
            try {
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                    try { compose.onNode(matcher).assertIsDisplayed(); return@waitUntil true }
                    catch (_: AssertionError) { /* Continue through the lazy form. */ }
                }
                compose.onNodeWithTag("private-room-list").performScrollToNode(matcher)
                compose.onNode(matcher).assertIsDisplayed()
                true
            } catch (_: AssertionError) { false }
        }
    }
    private fun click(text: String) { scroll(hasText(text)); compose.onNodeWithText(text).performClick() }
    private fun beginCreate() {
        click("选择助手新建")
        click("密码合成助手")
        scroll(hasTestTag(passwordTag))
    }
    private fun enter(first: String = password, second: String = first) {
        scroll(hasTestTag(passwordTag)); compose.onNodeWithTag(passwordTag).performTextReplacement(first)
        scroll(hasTestTag(confirmationTag)); compose.onNodeWithTag(confirmationTag).performTextReplacement(second)
    }
    private fun fieldsEmpty() {
        listOf(passwordTag, confirmationTag).forEach { tag ->
            scroll(hasTestTag(tag))
            assertEquals("Secret field must be cleared", "",
                compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
    private fun noticeContains(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("private-room-notice").assertIsDisplayed()
        compose.onNodeWithText(text, substring = true).assertExists()
    }

    @Test fun defaultPasswordCreationRequiresExplicitConfirmationThenSeparateEnable() {
        show(); beginCreate()
        enter()
        scroll(hasTestTag("$passwordTag-visibility"))
        compose.onNodeWithTag("$passwordTag-visibility").performClick()
        compose.onNodeWithTag("$passwordTag-visibility").performClick()
        click("创建加密隐私室")
        compose.waitUntil(30_000) { repo.status().availability == PrivateVaultAvailability.READY }
        assertTrue(repo.status().hasRecoveryPassword)
        assertFalse(repo.status().hasRecoveryCode)
        assertFalse(repo.status().enabled)
        assertFalse(repo.status().recoveryConfirmed)
        compose.onNodeWithTag(passwordTag).assertDoesNotExist()
        compose.onNodeWithTag(confirmationTag).assertDoesNotExist()
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        click("我已妥善保管恢复密码")
        compose.waitUntil(15_000) { repo.status().recoveryConfirmed }
        assertFalse(repo.status().enabled)
        click("开启隐私室")
        compose.waitUntil(15_000) { repo.status().enabled }
        compose.onNodeWithText(password).assertDoesNotExist()
    }

    @Test fun mismatchedPasswordsShowExplicitNoticeWithoutCreatingRoom() {
        show(); beginCreate()
        enter(password, "另一条不同的合成恢复密码")
        click("创建加密隐私室")
        noticeContains("两次输入的恢复密码不一致，请核对后再保存。")
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
        compose.onNodeWithTag("private-room-recovery-code").assertDoesNotExist()
        compose.onNodeWithText("我已妥善保管恢复密码").assertDoesNotExist()
    }

    @Test fun existingReadableCodeRoomAddsPasswordWithoutOldCodeAndKeepsGrantedContents() {
        repo.create(); repo.confirmRecoverySaved(); repo.setEnabled(true)
        val record = repo.openAiSession("synthetic-password-ui").use { it.writeRecord("HIDDEN-TITLE", "HIDDEN-BODY") }
        val request = repo.requestAccess("synthetic existing-room request", listOf(record.id), 600_000)
        repo.openAiSession("synthetic-password-ui").use { it.decideAccess(request.id, true) }
        show(); click("房间设置"); click("设置 / 修改恢复密码")
        enter()
        scroll(hasTestTag("private-room-password-submit"))
        compose.onNodeWithTag("private-room-password-submit").performClick()
        noticeContains("恢复密码已保存。")
        noticeContains("请重新导出密文备份")
        assertTrue(repo.status().hasRecoveryPassword)
        assertTrue(repo.status().hasRecoveryCode)
        assertTrue(repo.status().enabled)
        assertTrue(repo.status().recoveryConfirmed)
        assertTrue(repo.isAccessGranted(request.id))
        assertEquals("HIDDEN-BODY", repo.readGranted(request.id, record.id).body)
        compose.onNodeWithTag(passwordTag).assertDoesNotExist()
        compose.onNodeWithTag(confirmationTag).assertDoesNotExist()
        click("设置 / 修改恢复密码")
        fieldsEmpty()
        compose.onNodeWithText("HIDDEN-TITLE").assertDoesNotExist()
        compose.onNodeWithText("HIDDEN-BODY").assertDoesNotExist()
    }

    @Test fun failedCreationShowsReasonClearsInputsAndDoesNotAutomaticallyRetry() {
        val attempts = AtomicInteger()
        repo = repository(object : PrivateVaultKeyProtector {
            override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
                attempts.incrementAndGet()
                throw PrivateVaultException("device_key_unavailable")
            }
            override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray = error("No synthetic key saved")
        })
        show(); beginCreate(); enter()
        click("创建加密隐私室")
        noticeContains("device_key_unavailable")
        fieldsEmpty()
        compose.waitForIdle()
        assertEquals(1, attempts.get())
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
        compose.onNodeWithText("我已妥善保管恢复密码").assertDoesNotExist()
    }

    @Test fun switchingCreationMethodClearsBothPasswordsInsteadOfReinterpretingPrefix() {
        show(); beginCreate(); enter()
        click("随机恢复码")
        compose.onNodeWithTag(passwordTag).assertDoesNotExist()
        compose.onNodeWithTag(confirmationTag).assertDoesNotExist()
        click("自设恢复密码")
        fieldsEmpty()
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
    }

    @Test fun backgroundNavigationAndDialogCloseAllClearUnsavedPasswords() {
        show(); beginCreate(); enter()
        compose.runOnUiThread { lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnUiThread { lifecycle.currentState = Lifecycle.State.RESUMED }
        fieldsEmpty()
        enter()
        compose.onNodeWithText("返回隐私室").performClick()
        beginCreate(); fieldsEmpty()
        enter()
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(passwordTag).assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        beginCreate(); fieldsEmpty()
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
    }

    @Test fun recoveryRejectsWrongPasswordThenAcceptsExactPrefixAndSpacesWithoutSwitchingToCode() {
        repo.createWithRecoveryPassword(password.toCharArray())
        repo.confirmRecoverySaved(); repo.setEnabled(true)
        val record = repo.openAiSession("synthetic-ui-recovery").use { it.writeRecord("RESTORED-TITLE", "RESTORED-BODY") }
        val ownedAlias = aliases.single()
        keyStore().deleteEntry(ownedAlias) // Only the key minted by this synthetic fixture.
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, repo.status().availability)
        val before = vaultHashes()
        show(); click("房间设置")
        val recoveryTag = "private-room-recovery-password"
        scroll(hasTestTag(recoveryTag))
        compose.onNodeWithTag(recoveryTag).performTextReplacement("wrong-synthetic-password")
        click("用密码恢复（紧急解密）")
        noticeContains("authentication_failed")
        assertEquals(before, vaultHashes())
        assertFalse(keyStore().containsAlias(ownedAlias))
        scroll(hasTestTag(recoveryTag))
        assertEquals("", compose.onNodeWithTag(recoveryTag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("private-room-entered-code").assertDoesNotExist()
        compose.onNodeWithTag(recoveryTag).performTextReplacement(password)
        click("用密码恢复（紧急解密）")
        noticeContains("已恢复并绑定本机")
        assertEquals(PrivateVaultAvailability.READY, repo.status().availability)
        assertFalse(repo.status().enabled)
        compose.onNodeWithTag(recoveryTag).assertDoesNotExist()
        repo.setEnabled(true)
        repo.openAiSession("synthetic-ui-recovered").use { assertEquals("RESTORED-BODY", it.readRecord(record.id).body) }
        compose.onNodeWithText("RESTORED-TITLE").assertDoesNotExist()
        compose.onNodeWithText("RESTORED-BODY").assertDoesNotExist()
    }

    private fun vaultHashes(): Map<String, String> {
        val vault = File(directory, "vault")
        return vault.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(vault).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }
    }

    private fun repository(protector: PrivateVaultKeyProtector) = PrivateVaultRepository(
        File(directory, "vault"), owner, protector, elapsedNow = android.os.SystemClock::elapsedRealtime)
    private inner class TrackedProtector : PrivateVaultKeyProtector {
        private val actual = AndroidPrivateVaultKeyProtector()
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            check(UUID.fromString(vaultId).toString() == vaultId)
            val digest = MessageDigest.getInstance("SHA-256").digest(vaultId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val name = "orbis-private-vault-v1-$digest"
            check(name in aliases || !keyStore().containsAlias(name))
            aliases += name
            return actual.wrap(vaultId, dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray) = actual.unwrap(vaultId, wrappedKey)
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
