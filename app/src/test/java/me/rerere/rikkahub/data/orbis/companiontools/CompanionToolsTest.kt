package me.rerere.rikkahub.data.orbis.companiontools

import com.lover.connect.CompanionToolDescriptor
import com.lover.connect.CompanionNativeTools
import com.lover.connect.CompanionToolImage
import com.lover.connect.CompanionToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class CompanionToolsTest {
    private val syntheticImage = CompanionToolImage("/9j/synthetic-only", 4, 4)
    private fun descriptor(name: String, write: Boolean = false) = CompanionToolDescriptor(name, "synthetic tool",
        "{\"type\":\"object\",\"properties\":{}}", if (write) "write" else "read", name !in COMPANION_MEMORY_TOOL_NAMES)
    private val catalog = listOf(descriptor("get_battery"), descriptor("read_memory"), descriptor("save_memory", true),
        descriptor("lock_screen", true), descriptor("send_notification", true), descriptor("test_sentinel", true))

    @Test fun familiesOnlyRegisterExplicitSelectedNames() {
        val memory = buildCompanionTools(catalog, COMPANION_MEMORY_TOOL_NAMES, { "one" }) { _, _, _ -> error("not called") }
        assertEquals(setOf("companion_read_memory", "companion_save_memory"), memory.map { it.name }.toSet())
        assertTrue(buildCompanionTools(catalog, emptySet(), { "one" }) { _, _, _ -> error("not called") }.isEmpty())
    }
    @Test fun allWritesIncludingDeviceLocksSupportRememberedApproval() {
        val tools = buildCompanionTools(catalog, null, { "one" }) { _, _, _ -> error("not called") }.associateBy { it.name }
        val args = JsonObject(emptyMap())
        assertFalse(tools.getValue("companion_get_battery").needsApproval(args))
        assertTrue(tools.getValue("companion_save_memory").needsApproval(args))
        assertFalse(tools.getValue("companion_save_memory").requiresFreshApproval(args))
        assertFalse(tools.getValue("companion_lock_screen").requiresFreshApproval(args))
        assertTrue(tools.getValue("companion_lock_screen").hostApproval!!.rememberable)
        assertTrue(tools.getValue("companion_test_sentinel").hostApproval!!.rememberable)
    }
    @Test fun changedConfigurationInvalidatesOldToolDefinition() {
        var revision = "one"
        val first = buildCompanionTools(catalog, setOf("test_sentinel"), { revision }) { _, _, _ -> error("not called") }.single()
        assertTrue(first.isApprovalCurrent())
        revision = "two"
        assertFalse(first.isApprovalCurrent())
        val second = buildCompanionTools(catalog, setOf("test_sentinel"), { revision }) { _, _, _ -> error("not called") }.single()
        assertNotEquals(first.hostApproval!!.revision, second.hostApproval!!.revision)
    }
    @Test fun executionPassesSnapshotAndReturnsStructuredNativeData() = runTest {
        var calls = 0
        val tool = buildCompanionTools(catalog, setOf("read_memory"), { "one" }) { name, args, revision ->
            calls++
            assertEquals("read_memory", name); assertEquals("{}", args); assertEquals("one", revision)
            CompanionToolResult(true, "synthetic")
        }.single()
        val result = tool.execute(JsonObject(emptyMap()))
        assertEquals(1, calls)
        assertTrue(result.toString().contains("orbis_companion_native"))
    }
    @Test fun stoppedServiceDiagnosticsAreNotDescribedAsOfflineMemory() {
        val status = descriptor("get_l_service_status").copy(requiresService = false)
        val tool = buildCompanionTools(listOf(status), null, { "one" }) { _, _, _ -> error("not called") }.single()
        assertTrue(tool.description.contains("只读运行诊断"))
        assertTrue(tool.description.contains("服务停止也可用"))
        assertTrue(tool.description.contains("不会因此启动服务"))
        assertFalse(tool.description.contains("离线记忆库"))
        assertFalse(tool.needsApproval(JsonObject(emptyMap())))
    }
    @Test fun alarmQueryHasNativeNameNoApprovalAndNoOfflineMemoryOrServiceRequirement() = runTest {
        val descriptor = CompanionNativeTools.descriptors().single { it.name == "get_alarms" }
        var calls = 0
        val tools = buildCompanionTools(listOf(descriptor), setOf("get_alarms"), { "alarm-read-only" }) { name, args, revision ->
            assertEquals("get_alarms", name)
            assertEquals("{}", args)
            assertEquals("alarm-read-only", revision)
            calls++
            CompanionToolResult(true, "{\"ok\":true,\"alarms\":[],\"history_complete\":false}")
        }
        val tool = tools.single()
        val args = JsonObject(emptyMap())
        assertEquals("companion_get_alarms", tool.name)
        assertFalse(tool.needsApproval(args))
        assertFalse(tool.requiresFreshApproval(args))
        assertNull(tool.hostApproval)
        assertTrue(tool.description.contains("服务停止也可用"))
        assertTrue(tool.description.contains("无需执行确认"))
        assertTrue(tool.description.contains("不会启动服务或触发铃声"))
        assertFalse(tool.description.contains("离线记忆库"))
        assertFalse(tool.description.contains("需要陪伴服务"))
        assertEquals(0, calls)
        val text = (tool.execute(args).single() as UIMessagePart.Text).text
        assertTrue(Json.parseToJsonElement(text).jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, calls)
    }

    @Test fun structuredAlarmReadFailureRemainsFalseThroughHostWrapper() = runTest {
        val descriptor = CompanionNativeTools.descriptors().single { it.name == "get_alarms" }
        val tool = buildCompanionTools(listOf(descriptor), null, { "alarm-read-only" }) { _, _, _ ->
            CompanionToolResult(false, content = "{\"ok\":false,\"error\":{\"code\":\"alarm_store_unreadable\"}}",
                errorCode = "alarm_store_unreadable", outcome = "failed")
        }.single()
        val output = (tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text
        val receipt = Json.parseToJsonElement(output).jsonObject
        assertFalse(receipt.getValue("ok").jsonPrimitive.boolean)
        assertEquals("failed", receipt.getValue("outcome").jsonPrimitive.content)
        assertEquals("alarm_store_unreadable", receipt.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun alarmQueryPaginationArgumentsAndContinuationReceiptSurviveHostWrapper() = runTest {
        val descriptor = CompanionNativeTools.descriptors().single { it.name == "get_alarms" }
        val arguments = Json.parseToJsonElement("{\"include_history\":false,\"limit\":1,\"offset\":30}").jsonObject
        val page = "{\"ok\":true,\"alarms\":[],\"offset\":30,\"next_offset\":31,\"has_more\":true}"
        var calls = 0
        val tool = buildCompanionTools(listOf(descriptor), null, { "alarm-read-only" }) { name, args, _ ->
            assertEquals("get_alarms", name)
            assertEquals(arguments, Json.parseToJsonElement(args).jsonObject)
            calls++
            CompanionToolResult(true, content = page)
        }.single()
        assertFalse(tool.needsApproval(arguments))
        val receipt = Json.parseToJsonElement((tool.execute(arguments).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(receipt.getValue("ok").jsonPrimitive.boolean)
        assertEquals(page, receipt.getValue("content").jsonPrimitive.content)
        assertEquals(1, calls)
    }

    @Test fun runtimeToolDescriptionLimitsRecoveryToPreviouslyEnabledServices() {
        val tool = buildCompanionTools(listOf(descriptor("get_battery")), null, { "one" }) { _, _, _ -> error("not called") }.single()
        assertTrue(tool.description.contains("原先已启用的服务"))
        assertTrue(tool.description.contains("不会打开用户关闭的服务或自动授权"))
        assertFalse(tool.description.contains("离线记忆库"))
    }

    @Test fun screenshotIsOneTextReceiptAndOneActualImageAttachment() = runTest {
        var captures = 0
        var attachments = 0
        val tool = buildCompanionTools(listOf(descriptor("take_screenshot", true)), null, { "one" },
            imagePart = { image ->
                attachments++
                assertEquals(syntheticImage, image)
                UIMessagePart.Image("file:///synthetic-orbis-screen.jpg")
            }) { name, _, _ ->
                assertEquals("take_screenshot", name)
                captures++
                CompanionToolResult(true, "raw observation", images = listOf(syntheticImage))
            }.single()
        val args = JsonObject(emptyMap())
        assertFalse(tool.requiresFreshApproval(args))
        assertTrue(tool.hostApproval!!.rememberable)
        val output = tool.execute(args)
        assertEquals(1, captures)
        assertEquals(1, attachments)
        assertEquals(2, output.size)
        assertTrue((output[0] as UIMessagePart.Text).text.contains("raw observation"))
        assertFalse((output[0] as UIMessagePart.Text).text.contains("synthetic-only"))
        assertEquals("file:///synthetic-orbis-screen.jpg", (output[1] as UIMessagePart.Image).url)
    }

    @Test fun captureRefusalDoesNotStoreOrReturnAnyImage() = runTest {
        val output = companionResultParts(CompanionToolResult(false, errorCode = "device_locked", images = listOf(syntheticImage))) {
            error("A denied screenshot must not reach attachment storage")
        }
        assertEquals(1, output.size)
        assertTrue((output.single() as UIMessagePart.Text).text.contains("device_locked"))
        assertFalse((output.single() as UIMessagePart.Text).text.contains("separate_image_content"))
    }

    @Test fun attachmentFailureDoesNotPretendThatTheModelSawPixels() = runTest {
        val output = companionResultParts(CompanionToolResult(true, images = listOf(syntheticImage))) {
            error("private-path-secret-must-not-leak")
        }
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("screen_image_attachment_failed"))
        assertTrue(text.contains("没有把图片交给当前 AI"))
        assertFalse(text.contains("private-path-secret"))
        assertFalse(text.contains("synthetic-only"))
    }

    @Test fun cancellationWhileStoringScreenshotIsNotConvertedIntoACompletedObservation() = runTest {
        try {
            companionResultParts(CompanionToolResult(true, images = listOf(syntheticImage))) {
                throw CancellationException("fixture cancellation")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
