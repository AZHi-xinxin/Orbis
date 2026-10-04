package com.lover.connect

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionNativeToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private class FakeRuntime : CompanionRuntimeGateway {
        var ready = true
        var problem: String? = null
        var revision = "target-one"
        var calls = 0
        var captures = 0
        var screenResult = CompanionToolResult(true, "synthetic screen", images = listOf(CompanionToolImage("/9j/test", 4, 4)))
        var preparations = 0
        var preparation: (() -> String?)? = null
        var action: (String) -> String = { "ok" }
        override fun available() = ready
        override fun prepare(): String? { preparations++; return preparation?.invoke() ?: if (ready) null else "service_unavailable" }
        override fun authorizationRevision(name: String) = revision
        override fun permissionProblem(name: String, arguments: JSONObject) = problem
        override fun execute(name: String, arguments: JSONObject): String { calls++; return action(name) }
        override fun captureScreen(): CompanionToolResult { captures++; return screenResult }
    }

    @Test fun catalogHasExactly27UniqueNamesAndNoDuplicateUsageTools() {
        val catalog = CompanionNativeTools.descriptors()
        assertEquals(27, catalog.size)
        assertEquals(27, catalog.map { it.name }.toSet().size)
        assertFalse(catalog.any { it.name in setOf("get_screen_time", "get_app_timeline", "reset_screen_time") })
        assertEquals(setOf("save_memory", "read_memory", "get_runtime_status", "get_alarms"), catalog.filter { !it.requiresService }.map { it.name }.toSet())
        assertFalse(catalog.any { it.name == "get_l_service_status" || it.description.contains("Little L") })
        val legacy = CompanionToolCatalog.json()
        assertEquals((0 until legacy.length()).map { legacy.getJSONObject(it).getString("name") }, catalog.map { it.name })
    }

    @Test fun catalogDoesNotContactRuntime() {
        val runtime = FakeRuntime().also { it.action = { error("must not execute") } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root))
        assertEquals(27, bridge.catalog().size)
        assertEquals(0, runtime.calls)
    }

    @Test fun effectsMarkAllWritesIncludingScreenshotAndSentinel() {
        val writes = CompanionNativeTools.descriptors().filter { it.effect == "write" }.map { it.name }.toSet()
        assertEquals(setOf("send_notification", "save_memory", "set_alarm", "cancel_alarm", "lock_screen", "play_music",
            "take_screenshot", "lock_app", "unlock_app", "focus_rikka", "redirect_to_rikka", "configure_sentinel", "test_sentinel"), writes)
    }

    @Test fun directRuntimeExecutesOnceWithoutTransport() = runTest {
        val runtime = FakeRuntime()
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        assertEquals("ok", bridge.execute("get_battery", "{}").content)
        assertEquals(1, runtime.calls)
    }

    @Test fun nativeScreenshotReturnsImageWithoutCallingLegacyAnalysisOrDiary() = runTest {
        val runtime = FakeRuntime().also { it.action = { error("Legacy analysis must not execute") } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("take_screenshot", "{}", "target-one")
        assertTrue(result.ok)
        assertEquals(runtime.screenResult.images, result.images)
        assertEquals(1, runtime.captures)
        assertEquals(0, runtime.calls)
        val descriptor = bridge.catalog().single { it.name == "take_screenshot" }
        assertTrue(descriptor.description.contains("实际截图"))
        assertTrue(descriptor.description.contains("不写日记"))
        assertTrue(descriptor.description.contains("没有图像能力"))
    }

    @Test fun screenshotPermissionDenialNeverCapturesOrFallsBackToVisionAnalysis() = runTest {
        val runtime = FakeRuntime().also { it.problem = "accessibility_permission_required" }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("take_screenshot", "{}")
        assertEquals("accessibility_permission_required", result.errorCode)
        assertTrue(result.images.isEmpty())
        assertEquals(0, runtime.captures)
        assertEquals(0, runtime.calls)
    }

    @Test fun screenshotCaptureFailureDoesNotReturnStaleImageOrRetry() = runTest {
        val runtime = FakeRuntime().also { it.screenResult = CompanionToolResult(false,
            errorCode = "screen_capture_failed_or_protected", images = it.screenResult.images) }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("take_screenshot", "{}")
        assertFalse(result.ok)
        assertTrue(result.images.isEmpty())
        assertEquals("screen_capture_failed_or_protected", result.errorCode)
        assertEquals(1, runtime.captures)
        assertEquals(0, runtime.calls)
    }

    @Test fun screenshotCannotClaimSuccessWithOnlyAnAuxiliarySummary() = runTest {
        val runtime = FakeRuntime().also { it.screenResult = CompanionToolResult(true, "分析完成：a summary") }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("take_screenshot", "{}")
        assertFalse(result.ok)
        assertEquals("screen_capture_missing_image", result.errorCode)
        assertEquals(1, runtime.captures)
    }

    @Test fun missingServiceDoesNotStartAnything() = runTest {
        val runtime = FakeRuntime().also { it.ready = false }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("send_notification", "{\"message\":\"synthetic\"}")
        assertEquals("service_unavailable", result.errorCode)
        assertEquals("not_started", result.outcome)
        assertEquals(0, runtime.calls)
        assertEquals(1, runtime.preparations)
    }

    @Test fun successfulPreparationDispatchesWriteOnceWithoutReplayingIt() = runTest {
        val runtime = FakeRuntime().also { it.ready = false }
        runtime.preparation = { runtime.ready = true; null }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        assertTrue(bridge.execute("send_notification", "{\"message\":\"synthetic\"}").ok)
        assertEquals(1, runtime.preparations)
        assertEquals(1, runtime.calls)
    }

    @Test fun disabledPreparationDoesNotExecuteAndReportsAUsefulReason() = runTest {
        val runtime = FakeRuntime().also { it.ready = false; it.preparation = { "service_disabled" } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("get_battery", "{}")
        assertEquals("service_disabled", result.errorCode)
        assertEquals("not_started", result.outcome)
        assertEquals(0, runtime.calls)
    }

    @Test fun changedAuthorizationDuringRecoveryDoesNotExecute() = runTest {
        val runtime = FakeRuntime()
        runtime.preparation = { runtime.revision = "changed"; null }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        assertEquals("authorization_changed", bridge.execute("test_sentinel", "{}", "target-one").errorCode)
        assertEquals(0, runtime.calls)
    }

    @Test fun diagnosticToolWorksWithoutStartingTheUnavailableService() = runTest {
        val runtime = FakeRuntime().also {
            it.ready = false; it.problem = "permission_required"
            it.action = { "{\"native_runtime_ready\":false}" }
            it.preparation = { error("status must not initiate recovery") }
        }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        listOf("get_runtime_status", "get_l_service_status").forEach { name ->
            val result = bridge.execute(name, "{}")
            assertTrue(result.ok)
            assertFalse(JSONObject(result.content!!).getBoolean("native_runtime_ready"))
        }
        assertEquals(0, runtime.preparations)
        assertEquals(2, runtime.calls)
    }

    @Test fun alarmCatalogIsReadOnlyWithBoundedOptionalHistoryArgumentsAndHonestScope() {
        val descriptor = CompanionNativeTools.descriptors().single { it.name == "get_alarms" }
        assertEquals("read", descriptor.effect)
        assertFalse(descriptor.requiresService)
        val schema = JSONObject(descriptor.inputSchemaJson)
        val fields = schema.getJSONObject("properties")
        assertEquals(setOf("include_history", "limit", "offset"), fields.keys().asSequence().toSet())
        assertEquals("boolean", fields.getJSONObject("include_history").getString("type"))
        assertTrue(fields.getJSONObject("include_history").getBoolean("default"))
        assertEquals(30, fields.getJSONObject("limit").getInt("default"))
        assertEquals(1, fields.getJSONObject("limit").getInt("minimum"))
        assertEquals(100, fields.getJSONObject("limit").getInt("maximum"))
        assertEquals("integer", fields.getJSONObject("offset").getString("type"))
        assertEquals(0, fields.getJSONObject("offset").getInt("default"))
        assertEquals(0, fields.getJSONObject("offset").getInt("minimum"))
        assertEquals(10000, fields.getJSONObject("offset").getInt("maximum"))
        assertTrue(fields.getJSONObject("offset").getString("description").contains("next_offset"))
        assertFalse(schema.has("required"))
        listOf("本应用", "不是系统时钟", "旧版记录", "不等于一定响铃", "不触发真实铃声").forEach {
            assertTrue(descriptor.description.contains(it))
        }
        val setDescriptor = CompanionNativeTools.descriptors().single { it.name == "set_alarm" }
        listOf("一次性", "次日", "替换", "完整日期").forEach { assertTrue(setDescriptor.description.contains(it)) }
        assertEquals(4096, JSONObject(setDescriptor.inputSchemaJson).getJSONObject("properties")
            .getJSONObject("message").getInt("maxLength"))
    }

    @Test fun alarmQueryDoesNotPrepareServiceOrRequestWritePermissionsOrDispatchAlarmActions() = runTest {
        val receipt = "{\"ok\":true,\"alarms\":[],\"history_complete\":false}"
        val runtime = FakeRuntime().also {
            it.ready = false
            it.problem = "notification_permission_required"
            it.preparation = { error("Alarm reads must never prepare or start a service") }
            it.action = { name -> assertEquals("get_alarms", name); receipt }
        }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        for (args in listOf("{}", "{\"include_history\":false,\"limit\":1,\"offset\":0}", "{\"include_history\":true,\"limit\":100,\"offset\":10000}")) {
            val result = bridge.execute("get_alarms", args)
            assertTrue(result.ok)
            assertEquals("completed", result.outcome)
            assertEquals(receipt, result.content)
        }
        assertEquals(0, runtime.preparations)
        assertEquals(0, runtime.captures)
        assertEquals(3, runtime.calls)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun malformedAlarmQueryArgumentsNeverDispatchOrFallBackToSettingAnAlarm() = runTest {
        val runtime = FakeRuntime().also { it.action = { error("Invalid query must never dispatch") } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        listOf("{\"limit\":0}", "{\"limit\":101}", "{\"limit\":1.5}", "{\"limit\":\"30\"}",
            "{\"include_history\":\"true\"}", "{\"include_history\":null}", "{\"hour\":13}",
            "{\"limit\":4294967296}", "{\"offset\":-1}", "{\"offset\":10001}", "{\"offset\":1.5}",
            "{\"offset\":\"30\"}", "{\"offset\":null}", "{\"offset\":4294967296}").forEach { arguments ->
            val result = bridge.execute("get_alarms", arguments)
            assertEquals(arguments, "invalid_arguments", result.errorCode)
            assertEquals("not_started", result.outcome)
        }
        assertEquals(0, runtime.calls)
        assertEquals(0, runtime.preparations)
    }

    @Test fun alarmMessageLengthIsValidatedBeforeDispatch() = runTest {
        val runtime = FakeRuntime().also { it.action = { "{\"ok\":true}" } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val arguments = JSONObject().put("hour", 13).put("minute", 0).put("message", "a".repeat(4097))
        val tooLong = bridge.execute("set_alarm", arguments.toString())
        assertEquals("invalid_arguments", tooLong.errorCode)
        assertEquals("not_started", tooLong.outcome)
        assertEquals(0, runtime.calls)
        assertEquals(0, runtime.preparations)
        arguments.put("message", "a".repeat(4096))
        assertTrue(bridge.execute("set_alarm", arguments.toString()).ok)
        assertEquals(1, runtime.calls)
    }

    @Test fun alarmReceiptPreservesFullDateAndStageWithoutInferringRingingOrHearing() {
        val receipt = "{\"ok\":true,\"outcome\":\"completed\",\"scheduled_at\":\"2026-09-24T14:45:00+08:00\",\"scheduler_accepted\":true,\"heard\":null}"
        for (name in listOf("set_alarm", "cancel_alarm", "get_alarms")) {
            val result = companionLegacyResult(name, receipt)
            assertTrue(result.ok)
            assertEquals(receipt, result.content)
            assertNull(result.errorCode)
            assertEquals("completed", result.outcome)
        }
        val failure = companionLegacyResult("set_alarm", "{\"ok\":false,\"error\":{\"code\":\"exact_alarm_permission_required\"},\"outcome\":\"not_started\"}")
        assertFalse(failure.ok)
        assertEquals("exact_alarm_permission_required", failure.errorCode)
        assertEquals("not_started", failure.outcome)
        assertTrue(failure.content!!.contains("exact_alarm_permission_required"))
        assertEquals("unknown", companionLegacyResult("cancel_alarm", "{\"ok\":false}").outcome)
        assertEquals("failed", companionLegacyResult("get_alarms", "{\"ok\":false}").outcome)
    }

    @Test fun malformedOrContradictoryAlarmReceiptsNeverBecomeSuccessAndRawTextDoesNotLeak() {
        val malformed = listOf("private-error-sentinel", "已设置闹钟：14:45", "{}", "[]", "{\"ok\":\"true\"}",
            "{\"ok\":1}", "{\"ok\":true} trailing", "{\"ok\":true,\"outcome\":\"unknown\"}",
            "{\"ok\":false,\"outcome\":\"completed\"}", "{\"ok\":true,\"error\":{\"code\":\"failed\"}}",
            "{\"ok\":false,\"error\":\"private-error-sentinel\"}", "{\"ok\":false,\"outcome\":12}")
        for (name in listOf("set_alarm", "cancel_alarm", "get_alarms")) for (value in malformed) {
            val result = companionLegacyResult(name, value)
            assertFalse("$name: $value", result.ok)
            assertEquals("alarm_receipt_invalid", result.errorCode)
            assertEquals(if (name == "get_alarms") "failed" else "unknown", result.outcome)
            assertNull(result.content)
            assertFalse(result.toJson().contains("private-error-sentinel"))
        }
        assertTrue(companionLegacyResult("read_memory", "{\"ok\":false}").ok)
        assertTrue(companionLegacyResult("get_l_service_status", "{\"native_runtime_ready\":false}").ok)
    }

    @Test fun recoveryTimeoutDoesNotDispatchAndIsNotAnUnknownWrite() = runBlocking {
        val runtime = FakeRuntime().also { it.preparation = { Thread.sleep(10_000); null } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), Dispatchers.IO, 100)
        val result = bridge.execute("send_notification", "{\"message\":\"synthetic\"}")
        assertEquals("execution_timeout", result.errorCode)
        assertEquals("not_started", result.outcome)
        assertEquals(0, runtime.calls)
    }

    @Test fun permissionRevokedDuringRecoveryDoesNotExecute() = runTest {
        val runtime = FakeRuntime()
        runtime.preparation = { runtime.problem = "notification_permission_required"; null }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        assertEquals("notification_permission_required", bridge.execute("send_notification", "{\"message\":\"synthetic\"}").errorCode)
        assertEquals(0, runtime.calls)
    }

    @Test fun permissionRefusalDoesNotExecuteOrRequestPermission() = runTest {
        val runtime = FakeRuntime().also { it.problem = "permission_required" }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("lock_screen", "{}")
        assertEquals("permission_required", result.errorCode)
        assertEquals("not_started", result.outcome)
        assertEquals(0, runtime.calls)
    }

    @Test fun changedTargetInvalidatesPreviousApprovalBeforeDispatch() = runTest {
        val runtime = FakeRuntime()
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val previous = bridge.authorizationRevision("test_sentinel")
        runtime.revision = "target-two"
        assertEquals("authorization_changed", bridge.execute("test_sentinel", "{}", previous).errorCode)
        assertEquals(0, runtime.calls)
    }

    @Test fun memoryUsesExistingFileWithoutServiceOrRuntimeCalls() = runTest {
        Files.write(temporary.root.resolve("lc_memory.json").toPath(), "{\"old\":\"kept\"}".toByteArray())
        val runtime = FakeRuntime().also { it.ready = false; it.problem = "no permissions" }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        assertTrue(bridge.execute("save_memory", "{\"key\":\"new\",\"value\":\"added\"}").ok)
        val result = bridge.execute("read_memory", "{}")
        assertTrue(result.ok)
        assertTrue(result.content!!.contains("old：kept"))
        assertTrue(result.content!!.contains("new：added"))
        assertEquals(0, runtime.calls)
        assertEquals(0, runtime.preparations)
    }

    @Test fun malformedAndUnknownArgumentsNeverDispatch() = runTest {
        val runtime = FakeRuntime()
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        for (args in listOf("not json", "[]", "{\"hour\":24,\"minute\":0}", "{\"hour\":12,\"minute\":60}",
            "{\"hour\":\"12\",\"minute\":0}", "{\"hour\":12.5,\"minute\":0}", "{\"hour\":12}",
            "{\"hour\":4294967296,\"minute\":0}", "{\"hour\":12,\"minute\":0,\"unexpected\":true}")) {
            val result = bridge.execute("set_alarm", args)
            assertEquals(args, "invalid_arguments", result.errorCode)
            assertEquals(args, "not_started", result.outcome)
        }
        assertEquals("unknown_tool", bridge.execute("get_screen_time", "{}").errorCode)
        assertEquals(0, runtime.calls)
        assertEquals(0, runtime.preparations)
    }

    @Test fun errorsHideExceptionMessagesAndDoNotRetryWrites() = runTest {
        val runtime = FakeRuntime().also { it.action = { error("secret-token-private") } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("send_notification", "{\"message\":\"synthetic\"}")
        assertFalse(result.ok)
        assertEquals("unknown", result.outcome)
        assertFalse(result.toJson().contains("secret-token-private"))
        assertEquals(1, runtime.calls)
    }

    @Test fun parentCancellationIsNotConvertedIntoSuccessOrRetry() = runTest {
        val runtime = FakeRuntime().also { it.action = { throw CancellationException("cancelled") } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        try { bridge.execute("get_battery", "{}"); fail("must propagate cancellation") } catch (_: CancellationException) { }
        assertEquals(1, runtime.calls)
    }

    @Test fun timeoutInterruptsWaitAndMarksWriteOutcomeUnknown() = runBlocking {
        val runtime = FakeRuntime().also { it.action = { Thread.sleep(10_000); "late" } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), Dispatchers.IO, 100)
        val result = bridge.execute("send_notification", "{\"message\":\"synthetic\"}")
        assertFalse(result.ok)
        assertEquals("execution_timeout", result.errorCode)
        // Dispatch could miss the deadline on a saturated test host; never claim an attempted write was not started.
        assertEquals(if (runtime.calls == 0) "not_started" else "unknown", result.outcome)
        assertTrue(runtime.calls <= 1)
    }

    @Test fun legacyFailureAndUnknownRepliesAreNotSuccess() {
        assertFalse(companionLegacyResult("set_alarm", "设置闹钟失败：hidden").ok)
        assertEquals("unknown", companionLegacyResult("set_alarm", "设置闹钟失败：hidden").outcome)
        assertFalse(companionLegacyResult("test_sentinel", "Sentinel test: uncertain").ok)
        assertEquals("unknown", companionLegacyResult("test_sentinel", "Sentinel test: uncertain").outcome)
        assertEquals("unknown", companionLegacyResult("test_sentinel", "Sentinel test: rejected").outcome)
        assertEquals("unknown", companionLegacyResult("test_sentinel", "Sentinel test: suppressed").outcome)
        assertTrue(companionLegacyResult("test_sentinel", "Sentinel test: delivered").ok)
        assertFalse(companionLegacyResult("take_screenshot", "截图或分析仍在进行，稍后核对").ok)
        assertTrue(companionLegacyResult("read_memory", "设置闹钟失败：这是记忆内容").ok)
    }

    @Test fun dispatchedAlarmFailuresAreUnknownAndMustNotBeAutomaticallyRetried() = runTest {
        val runtime = FakeRuntime().also {
            it.action = { "{\"ok\":false,\"error\":{\"code\":\"operation_unavailable\"},\"outcome\":\"unknown\"}" }
        }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        for (name in listOf("set_alarm", "cancel_alarm")) {
            val result = bridge.execute(name, "{\"hour\":12,\"minute\":0}")
            assertFalse(result.ok)
            assertEquals("operation_unavailable", result.errorCode)
            assertEquals("unknown", result.outcome)
            assertTrue(result.toJson().contains("不要自动重试"))
        }
        assertEquals(2, runtime.calls)
    }

    @Test fun dispatchedReadFailureIsFailedRatherThanNotStarted() = runTest {
        val runtime = FakeRuntime().also { it.action = { "天气获取失败：hidden" } }
        val bridge = CompanionNativeTools(runtime, CompanionMemoryStore(temporary.root), StandardTestDispatcher(testScheduler))
        val result = bridge.execute("get_weather", "{}")
        assertFalse(result.ok)
        assertEquals("operation_unavailable", result.errorCode)
        assertEquals("failed", result.outcome)
        assertEquals(1, runtime.calls)
    }
}
