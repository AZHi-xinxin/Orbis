package me.rerere.rikkahub.data.orbis.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.SelfCheckCardContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelfCheckDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun requireIsolated() { check(context.packageName == "org.orbis.agent.bodylab") }
    @Test fun humanCanInspectAndChangeFocusWithAiToolOff() {
        requireIsolated()
        val enabled = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme { SelfCheckCardContent("synthetic-owner", enabled.value, { enabled.value = it }) { focus ->
            calls.add(focus)
            SelfCheckReport("synthetic", 12345, focus, listOf(SelfCheckItem("probe", "实际检查：$focus", "info", "无需模型请求", "下一步")))
        } } }
        compose.onNodeWithTag("self-check-enable").assertIsOff()
        compose.onNodeWithTag("self-check-open").performClick()
        compose.waitUntil { calls.size == 1 }
        compose.onNodeWithText("实际检查：overview").assertExists()
        compose.onNodeWithTag("self-check-focus-theme").performClick()
        compose.waitUntil { calls.size == 2 }
        compose.onNodeWithText("实际检查：theme").assertExists()
        compose.onNodeWithText("复制报告").assertIsEnabled().performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("self-check-enable").assertIsOff()
        assertEquals(listOf("overview", "theme"), calls)
    }
    @Test fun failedHumanCheckIsUnknownAndDoesNotClaimNormal() {
        requireIsolated()
        compose.setContent { MaterialTheme { SelfCheckCardContent("synthetic-owner", false, {}) { error("PRIVATE_ERROR") } } }
        compose.onNodeWithTag("self-check-open").performClick()
        compose.waitUntil { compose.onAllNodesWithText("本次检查未完成，请稍后重试；当前不能判断配置是否正常。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("复制报告").assertIsNotEnabled()
        compose.onNodeWithText("PRIVATE_ERROR").assertDoesNotExist()
    }
    @Test fun journalIsFivePerOwnerSurvivesReadAndStoresNoErrorBody() {
        requireIsolated()
        val owner = Uuid.random().toString(); val other = Uuid.random().toString()
        try {
            repeat(8) { DiagnosticJournal.record(context, owner, "model", diagnosticCode("HTTP 401 SECRET-$it")) }
            val stored = DiagnosticJournal.read(context, owner)
            assertEquals(5, stored.size)
            assertTrue(stored.all { it.code == "model_auth_rejected" })
            assertTrue(DiagnosticJournal.read(context, other).isEmpty())
            val raw = File(context.noBackupFilesDir, "orbis-self-check/$owner.json").readText()
            assertFalse(raw.contains("SECRET")); assertFalse(raw.contains("HTTP"))
            DiagnosticJournal.record(context, owner, "bad-source", "SECRET")
            assertEquals(stored, DiagnosticJournal.read(context, owner))
        } finally { File(context.noBackupFilesDir, "orbis-self-check/$owner.json").delete() }
    }
    @Test fun actualThemeToolFailureReturnsFixAndThenNextCallPublishesWithoutAutoApplyGrant() = runBlocking {
        requireIsolated()
        val owner = Uuid.random().toString(); val root = File(context.filesDir.canonicalFile, "orbis-themes/$owner")
        try {
            val store = ThemeRepository(root)
            val tool = themeTool({ store }, { true })
            suspend fun call(args: JsonElement) = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
            val missing = "a".repeat(64) + ".png"
            val missingImage = call(buildJsonObject { put("action", "write"); put("body", """{"name":"photo","background":{"type":"image","asset":"$missing"},"assets":["$missing"]}""") })
            assertEquals("theme_asset_missing", missingImage["code"]!!.jsonPrimitive.content)
            assertEquals("background.asset", missingImage["field"]!!.jsonPrimitive.content)
            assertEquals("missing_file", missingImage["issue"]!!.jsonPrimitive.content)
            val invalidImage = call(buildJsonObject { put("action", "write"); put("body", """{"name":"photo","background":{"type":"image","asset":"sample.png"},"assets":["sample.png"]}""") })
            assertEquals("theme_asset_invalid", invalidImage["code"]!!.jsonPrimitive.content)
            assertEquals("background.asset", invalidImage["field"]!!.jsonPrimitive.content)
            assertEquals("invalid_name", invalidImage["issue"]!!.jsonPrimitive.content)
            assertEquals("asset_validation", invalidImage["phase"]!!.jsonPrimitive.content)
            assertTrue(store.snapshot().entries.isEmpty())
            val written = call(buildJsonObject { put("action", "write"); put("body", """{"name":"Synthetic test"}""") })
            assertTrue(written.toString(), written["ok"]!!.jsonPrimitive.boolean)
            val error = call(buildJsonObject { put("action", "publish"); put("id", written["id"]!!); put("revision", written["revision"]!!) })
            assertFalse(error["ok"]!!.jsonPrimitive.boolean)
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("expected_revision"))
            assertEquals("argument_validation",error["phase"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive(false),error["storage_checked"])
            assertTrue(store.snapshot().entries.single().versions.isEmpty())
            val published = call(written["next_call"]!!)
            assertTrue(published["ok"]!!.jsonPrimitive.boolean)
            assertFalse(store.snapshot().allowAiApply)
            val result = call(published["next_call"]!!)
            assertEquals("awaiting_human_preview", result["status"]!!.jsonPrimitive.content)
        } finally { root.deleteRecursively() }
    }
}
