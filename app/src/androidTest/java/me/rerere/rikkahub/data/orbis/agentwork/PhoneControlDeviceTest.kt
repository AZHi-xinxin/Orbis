package me.rerere.rikkahub.data.orbis.agentwork

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneControlDeviceTest {
    @Test fun readClickInputScrollAndStaleRejectionOnSyntheticApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "org.orbis.agent.bodylab")
        check(android.os.Build.FINGERPRINT.startsWith("google/sdk_gphone") || android.os.Build.FINGERPRINT.startsWith("Android/sdk"))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {
            automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        }
        // Instrumentation force-stops the target. Rebind only on this isolated emulator after it starts.
        shell("settings put secure enabled_accessibility_services null")
        shell("settings put secure enabled_accessibility_services org.orbis.agent.bodylab/me.rerere.rikkahub.data.orbis.agentwork.PhoneControlService")
        shell("settings put secure accessibility_enabled 1")
        val fixturePackage = instrumentation.context.packageName
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(fixturePackage,PhoneFixtureActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.currentTimeMillis() + 15000
        while (PhoneControlService.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val service = checkNotNull(PhoneControlService.instance) { "Enable service on isolated emulator before running this test" }
        val allowed = setOf(fixturePackage)
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun read(): PhonePage {
            var page: PhonePage? = null
            val until = System.currentTimeMillis() + 5000
            while (page == null && System.currentTimeMillis() < until) {
                page = runCatching { main { service.page(allowed) } }.getOrNull()
                if (page == null) Thread.sleep(100)
            }
            return checkNotNull(page)
        }
        Thread.sleep(500)
        val first = read()
        assertTrue(first.elements.any { it.text == "计数 0" })
        assertTrue("Message bodies excluded from the default accessibility tree must remain readable",
            first.elements.any { it.text == "已发送的合成消息" })
        assertFalse(first.elements.any { it.text.contains("fixture-secret") || it.description == "测试密码" })
        val button = first.elements.first { it.text == "增加一次" && it.clickable }
        assertTrue(main { service.act(first,first.token,allowed,"click",button.id,"") })
        Thread.sleep(300)
        val second = read()
        assertTrue(second.elements.any { it.text == "计数 1" })
        try { main { service.act(first,first.token,allowed,"click",button.id,"") }; fail("stale page accepted") }
        catch (e: IllegalStateException) { assertEquals("phone_page_changed_read_again",e.message) }
        val input = second.elements.first { it.description == "测试输入" && it.editable }
        assertTrue(main { service.act(second,second.token,allowed,"input",input.id,"Orbis test") })
        Thread.sleep(300)
        val third = read()
        assertTrue(third.elements.any { it.text == "Orbis test" })
        try { main { service.page(emptySet()) }; fail("revoked grant accepted") }
        catch (e: IllegalStateException) { assertEquals("phone_app_not_allowed",e.message) }
        val scroll = third.elements.first { it.scrollable }
        assertTrue(main { service.act(third,third.token,allowed,"scroll_forward",scroll.id,"") })
        Thread.sleep(300)
        val after = read()
        assertNotEquals(third.elements,after.elements)

        instrumentation.context.startActivity(Intent().setComponent(ComponentName(fixturePackage,PhoneFixtureActivity::class.java.name))
            .putExtra("dense", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Thread.sleep(600)
        val dense = read()
        assertTrue("Large history must report omitted content", dense.truncated)
        val composer = dense.elements.single { it.editable && it.description == "深层输入框" }
        assertTrue("Fixture must cover nesting deeper than the old 25 level cutoff", composer.path.size > 25)
        assertTrue(dense.elements.any { it.text == "合成发送" && it.clickable })
        assertTrue(main { service.act(dense,dense.token,allowed,"input",composer.id,"本机合成测试") })
        Thread.sleep(300)
        val typed = read()
        assertEquals("本机合成测试", typed.elements.single { it.editable }.text)
        val send = typed.elements.single { it.text == "合成发送" && it.clickable }
        assertTrue(main { service.act(typed,typed.token,allowed,"click",send.id,"") })
        Thread.sleep(300)
        assertTrue(read().elements.any { it.text == "合成已发送" })
    }
}
