package com.lover.connect

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Host wiring guard without launching a real activity, reading preferences or changing permissions. */
class BridgeLegacyAutomationHostPolicyTest {
    private fun source(relative: String): String = listOf(File(relative), File("../$relative"))
        .firstOrNull { it.isFile }?.readText(Charsets.UTF_8)?.replace("\r\n", "\n")
        ?: error("Source fixture not found: $relative")
    private val bridgeRoot = "lcbridge/src/main/java/com/lover/connect/"

    @Test fun `only explicit Orbis application IDs select read only notification shortcut`() {
        assertTrue(isOrbisLegacyAutomationHost("org.orbis.agent"))
        assertTrue(isOrbisLegacyAutomationHost("org.orbis.agent.dev"))
        assertFalse(isOrbisLegacyAutomationHost("com.lover.connect"))
        assertFalse(isOrbisLegacyAutomationHost("org.orbis.agent.fake"))
        assertFalse(isOrbisLegacyAutomationHost(""))
    }

    @Test fun `standalone dashboard and MainScreen defaults retain their original editing path`() {
        val dashboard = source(bridgeRoot + "BridgeDashboard.kt")
        val main = source(bridgeRoot + "MainActivity.kt")
        assertTrue(dashboard.contains("legacyAutomationReadOnly: Boolean = false"))
        assertTrue(main.contains("legacyAutomationReadOnly: Boolean = false"))
        assertTrue(main.contains("Text(\"保存小L配置\")"))
    }

    @Test fun `Orbis page and notification shortcut both forward the host mode`() {
        val page = source("app/src/main/java/me/rerere/rikkahub/ui/pages/orbis/OrbisPhonePage.kt")
        val dashboard = source(bridgeRoot + "BridgeDashboard.kt")
        val main = source(bridgeRoot + "MainActivity.kt")
        assertTrue(page.contains("legacyAutomationReadOnly = true"))
        assertTrue(main.contains("BridgeDashboard(legacyAutomationReadOnly = isOrbisLegacyAutomationHost(packageName))"))
        assertTrue(dashboard.contains("MainScreen(Modifier.weight(1f), selected, legacyAutomationReadOnly = legacyAutomationReadOnly)"))
        assertTrue(main.contains("BridgeSentinelSection(legacyAutomationReadOnly = legacyAutomationReadOnly)"))
    }

    @Test fun `read only sentinel returns before editable credentials and passes only configured booleans`() {
        val source = source(bridgeRoot + "BridgeSettingsSections.kt")
        val start = source.indexOf("if (legacyAutomationReadOnly) {")
        val end = source.indexOf("    var url by remember", start)
        assertTrue(start >= 0 && end > start)
        val branch = source.substring(start, end)
        assertTrue(branch.contains("BridgeLegacySentinelReadOnlyCard("))
        assertTrue(branch.contains("credentialConfigured = !prefs.getString(\"sentinel_token\", \"\").isNullOrBlank()"))
        assertTrue(branch.contains("return"))
        assertFalse(branch.contains("OutlinedTextField"))
        assertFalse(branch.contains("prefs.edit()"))
    }

    @Test fun `host legacy automation branch can only stop collection not enable or edit behavior`() {
        val source = source(bridgeRoot + "MainActivity.kt")
        val start = source.indexOf("if (legacyAutomationReadOnly) {")
        val end = source.indexOf("        } else {\n        Text(\"小L", start)
        assertTrue(start >= 0 && end > start)
        val branch = source.substring(start, end)
        assertTrue(branch.contains("BridgeLegacyAutomationReadOnlyCard("))
        assertTrue(branch.contains("putBoolean(\"eyes_enabled\", false)"))
        assertTrue(branch.contains("ScreenCaptureService.stop(context)"))
        assertFalse(branch.contains("putBoolean(\"eyes_enabled\", true)"))
        assertFalse(branch.contains("putString(\"eyes_personality\""))
        assertFalse(branch.contains("putInt(\"eyes_interval\""))
        assertFalse(branch.contains("putInt(\"rest_threshold_minutes\""))
        assertFalse(branch.contains("OutlinedTextField"))
    }

    @Test fun `system permission and privacy collection controls remain available`() {
        val main = source(bridgeRoot + "MainActivity.kt")
        assertTrue(main.contains("BridgePermissionRequests()"))
        assertTrue(main.contains("screenCaptureLauncher.launch(manager.createScreenCaptureIntent())"))
        assertTrue(main.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertTrue(main.contains("DeviceContextSection()"))
        assertTrue(main.contains("LocationSafetySection(legacyAutomationReadOnly = legacyAutomationReadOnly)"))
        val privacy = source(bridgeRoot + "DeviceContextSection.kt")
        assertTrue(privacy.contains("DeviceContextSettings.KEY_NOTIFICATION_TEXT"))
        assertTrue(privacy.contains("DeviceContextStore(context).stripNotificationText()"))
    }

    @Test fun `location alert threshold is host read only while reporting and privacy actions remain outside that branch`() {
        val location = source(bridgeRoot + "LocationSafetySection.kt")
        assertTrue(location.contains("fun LocationSafetySection(legacyAutomationReadOnly: Boolean = false)"))
        assertTrue(location.contains("if (legacyAutomationReadOnly) {\n        BridgeLegacyReminderDistanceReadOnlyCard(reminderKmText)\n    } else {"))
        assertTrue(location.contains(") { Text(\"保存提醒距离\") }\n    }\n\n    Button("))
        assertTrue(location.contains("LocationSafetyManager.handleReportButton(context)"))
        assertTrue(location.contains("LocationSafetyManager.pause(context)"))
        assertTrue(location.contains("LocationSafetyManager.stop(context)"))
        assertTrue(location.contains("Text(\"清除全部安全位置数据\")"))
    }
}
