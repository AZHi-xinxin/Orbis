package com.lover.connect

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class CompanionToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val effect: String,
    val requiresService: Boolean,
)

/** No throwable messages, tokens, endpoints or arguments are copied into error diagnostics. */
data class CompanionToolResult(
    val ok: Boolean,
    val content: String? = null,
    val errorCode: String? = null,
    val outcome: String = "completed",
    val images: List<CompanionToolImage> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("ok", ok); put("source", "orbis_companion_native"); put("instruction_authority", "none")
        put("outcome", outcome)
        content?.let { put("content", it) }
        errorCode?.let { put("error", JSONObject().put("code", it)) }
        if (images.isNotEmpty()) {
            // The image bytes travel as image content, never a giant JSON/text transcript.
            put("image_count", images.size)
            put("images", JSONArray().apply { images.forEach { put(it.metadataJson()) } })
        }
        if (!ok) put("message", when {
            outcome == "unknown" -> "结果不确定，请核对状态，不要自动重试。"
            errorCode == "service_disabled" -> "陪伴服务已关闭；未自动开启。可在手机与陪伴中启动服务。"
            errorCode == "service_unavailable" -> "已尝试恢复原先启用的陪伴服务，但尚未就绪，本次操作未执行。可调用 companion_get_l_service_status 检查状态，或在手机与陪伴中点恢复运行。"
            else -> "本次未完成。请检查系统权限和工具参数；不会自动授权或开启原先关闭的观察。"
        })
    }.toString()
}

internal interface CompanionRuntimeGateway {
    fun available(): Boolean
    /** Preparation only: never replay a business operation or enable a disabled service. */
    fun prepare(): String? = if (available()) null else "service_unavailable"
    fun authorizationRevision(name: String): String = "local-v1"
    fun permissionProblem(name: String, arguments: JSONObject): String?
    fun execute(name: String, arguments: JSONObject): String
    /** A native observation, not the legacy auxiliary vision model / diary pipeline. */
    fun captureScreen(): CompanionToolResult = CompanionToolResult(false,
        errorCode = "screen_capture_unavailable", outcome = "not_started")
}

/** Native process call; does not use McpManager, a URL, HTTP, JSON-RPC or an MCP credential. */
class CompanionNativeTools internal constructor(
    private val runtime: CompanionRuntimeGateway,
    private val memory: CompanionMemoryStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 45_000,
) {
    constructor(context: Context) : this(
        AndroidCompanionRuntime(context.applicationContext),
        CompanionMemoryStore(context.applicationContext.filesDir),
    )

    fun catalog(): List<CompanionToolDescriptor> = descriptors()

    fun authorizationRevision(name: String): String = runtime.authorizationRevision(name)

    suspend fun execute(name: String, argumentsJson: String, expectedAuthorizationRevision: String? = null): CompanionToolResult {
        val descriptor = catalog().firstOrNull { it.name == name }
            ?: return failure("unknown_tool")
        val args = try {
            require(argumentsJson.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            JSONObject(argumentsJson).also { validateCompanionArguments(descriptor, it) }
        } catch (_: Exception) { return failure("invalid_arguments") }
        var dispatched = false
        return try {
            withTimeoutOrNull(timeoutMs) {
                currentCoroutineContext().ensureActive()
                runInterruptible(dispatcher) {
                    if (expectedAuthorizationRevision != null && expectedAuthorizationRevision != runtime.authorizationRevision(name))
                        return@runInterruptible failure("authorization_changed")
                    if (descriptor.requiresService) runtime.prepare()?.let { return@runInterruptible failure(it) }
                    // Recovery may take time. Re-check a changed target before dispatching any operation.
                    if (expectedAuthorizationRevision != null && expectedAuthorizationRevision != runtime.authorizationRevision(name))
                        return@runInterruptible failure("authorization_changed")
                    if (descriptor.requiresService) runtime.permissionProblem(name, args)?.let { return@runInterruptible failure(it) }
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    dispatched = true
                    if (name == "take_screenshot") {
                        return@runInterruptible validateCompanionScreenResult(runtime.captureScreen())
                    }
                    val value = when (name) {
                        "save_memory" -> memory.save(args.getString("key"), args.getString("value"))
                        "read_memory" -> memory.read(args.optString("key", ""))
                        else -> runtime.execute(name, args)
                    }
                    if (value.toByteArray(Charsets.UTF_8).size > CompanionMemoryStore.MAX_BYTES + 8192)
                        return@runInterruptible failure("result_too_large", descriptor.effect == "write")
                    companionLegacyResult(name, value)
                }
            } ?: failure("execution_timeout", dispatched && descriptor.effect == "write")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SecurityException) { failure("permission_required", dispatched && descriptor.effect == "write")
        } catch (_: Exception) { failure("execution_failed", dispatched && descriptor.effect == "write") }
    }

    companion object {
        private val writes = setOf("send_notification", "save_memory", "set_alarm", "cancel_alarm", "lock_screen",
            "play_music", "take_screenshot", "lock_app", "unlock_app", "focus_rikka", "redirect_to_rikka",
            "configure_sentinel", "test_sentinel")
        fun descriptors(): List<CompanionToolDescriptor> {
            val array = CompanionToolCatalog.json()
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val name = item.getString("name")
                val description = if (name == "take_screenshot") COMPANION_SCREEN_DESCRIPTION else item.getString("description")
                CompanionToolDescriptor(name, description, item.getJSONObject("inputSchema").toString(),
                    if (name in writes) "write" else "read", name !in setOf("save_memory", "read_memory", "get_l_service_status", "get_alarms"))
            }
        }
    }
}

private fun failure(code: String, unknown: Boolean = false) = CompanionToolResult(false,
    errorCode = code, outcome = if (unknown) "unknown" else "not_started")

/** Legacy text is received after dispatch: it cannot prove that a write had no partial effect. */
private fun dispatchedFailure(name: String, code: String) = CompanionToolResult(false,
    errorCode = code, outcome = if (CompanionNativeTools.descriptors().any { it.name == name && it.effect == "write" })
        "unknown" else "failed")

/** Existing MCP text is retained as data, but known failure/ambiguous results are not labelled success. */
internal fun companionLegacyResult(name: String, value: String): CompanionToolResult {
    if (name in setOf("set_alarm", "cancel_alarm", "get_alarms")) return companionAlarmResult(name, value)
    if (name == "test_sentinel" && value != "Sentinel test: delivered")
        return dispatchedFailure(name, "sentinel_delivery_unconfirmed")
    if (name == "take_screenshot" && (value.startsWith("截图或分析仍在进行") || value.startsWith("日记已写入")))
        return failure("observation_outcome_unknown", true)
    if (name !in setOf("read_memory", "save_memory", "read_eyes_log") && (
            value.startsWith("Rejected:") || value.startsWith("Lock refused:") ||
            value.startsWith("Focus configuration refused:") || value.startsWith("Redirect configuration refused:") ||
            value.startsWith("获取电池信息失败") || value.startsWith("天气获取失败") || value.startsWith("设置闹钟失败") ||
            value.startsWith("取消闹钟失败") || value.startsWith("锁屏失败") || value.startsWith("截屏失败") ||
            value.startsWith("获取播放信息失败") || value == "Invalid arguments" ||
            value.startsWith("截屏未就绪") || value.startsWith("截屏尚未授权") || value.startsWith("本次分析失败") ||
            value.startsWith("未设置城市") || value.startsWith("步数暂不可用") || value.startsWith("当前设备未提供") ||
            value.contains("is not supported on this Vivo device"))) return dispatchedFailure(name, "operation_unavailable")
    return CompanionToolResult(true, value)
}

/** Alarm receipt stages are explicit JSON; never reinterpret malformed/legacy text as success. */
private fun companionAlarmResult(name: String, value: String): CompanionToolResult {
    val unknownWrite = name != "get_alarms"
    val invalid = CompanionToolResult(false, errorCode = "alarm_receipt_invalid",
        outcome = if (unknownWrite) "unknown" else "failed")
    return try {
        val parser = JSONTokener(value)
        val receipt = parser.nextValue() as? JSONObject ?: return invalid
        if (parser.nextClean() != '\u0000') return invalid
        val ok = receipt.opt("ok") as? Boolean ?: return invalid
        val outcome = if (receipt.has("outcome")) receipt.get("outcome") as? String ?: return invalid
            else if (ok) "completed" else if (unknownWrite) "unknown" else "failed"
        if (outcome !in setOf("completed", "not_started", "failed", "unknown") ||
            (ok && outcome != "completed") || (!ok && outcome == "completed")) return invalid
        val errorCode = if (receipt.has("error") && !receipt.isNull("error")) {
            if (ok) return invalid
            val error = receipt.optJSONObject("error") ?: return invalid
            (error.opt("code") as? String)?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,95}")) } ?: return invalid
        } else if (ok) null else "alarm_operation_failed"
        CompanionToolResult(ok, content = value, errorCode = errorCode, outcome = outcome)
    } catch (_: Exception) { invalid }
}

internal fun validateCompanionArguments(descriptor: CompanionToolDescriptor, args: JSONObject) {
    val schema = JSONObject(descriptor.inputSchemaJson)
    val properties = schema.getJSONObject("properties")
    val required = schema.optJSONArray("required") ?: JSONArray()
    for (i in 0 until required.length()) require(args.has(required.getString(i)))
    args.keys().forEach { key ->
        require(properties.has(key))
        val field = properties.getJSONObject(key)
        val value = args.get(key)
        when (field.getString("type")) {
            "string" -> { require(value is String); require(value.length <= field.optInt("maxLength", 131072)) }
            "integer" -> {
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble())
                require(value.toLong() >= field.optLong("minimum", Long.MIN_VALUE))
                require(value.toLong() <= field.optLong("maximum", Long.MAX_VALUE))
            }
            "boolean" -> require(value is Boolean)
            "array" -> {
                require(value is JSONArray && value.length() <= 128)
                for (i in 0 until value.length()) require(value.get(i) is String)
            }
            else -> error("unsupported_schema")
        }
    }
    when (descriptor.name) {
        "set_alarm", "cancel_alarm" -> require(args.getLong("hour") in 0L..23L && args.getLong("minute") in 0L..59L)
        "save_memory" -> require(args.getString("key").isNotBlank() && args.getString("value").isNotBlank())
        "send_notification" -> require(args.getString("message").isNotBlank())
        "play_music" -> require(args.getString("query").isNotBlank() && args.optString("platform", "auto") in setOf("auto", "qq", "netease", "kugou"))
        "lock_app", "unlock_app" -> require(args.getString("package_name").isNotBlank())
    }
}
