package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.ombre.OmbreRuntimeSpec
import me.rerere.rikkahub.data.orbis.ombre.OmbreRuntimeState

internal fun createOrbisOmbreRuntimeTool(
    allowed: suspend () -> Boolean,
    start: suspend (OmbreRuntimeSpec) -> OmbreRuntimeState,
    status: () -> OmbreRuntimeState,
    stop: () -> Unit,
) = Tool(
    name = "orbis_ombre_runtime",
    description = "管理用户已选定且启用托管的本手机OmbreBrain：start/status/stop。先确认部署位置；指南按需查orbis_help guide/ombre_phone。只托管已安装实例，不安装、不修改Termux。starting不是连通；ready只说明本地健康与MCP目录通过，模型/记忆仍需测试。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("start", "status", "stop").map(::JsonPrimitive))) })
        put("target", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("this_phone_orbis")))) })
        listOf("source", "python", "config").forEach { key -> put(key, buildJsonObject { put("type", "string"); put("maxLength", 400) }) }
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content == "start" },
    isApprovalCurrent = { true },
    execute = { args ->
        val result = try {
            check(allowed()) { "ombre_host_unavailable" }
            val a = args.jsonObject
            fun field(key: String) = (a[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("ombre_invalid_arguments")
            val state = when (field("action")) {
                "start" -> {
                    require(a.keys == setOf("action", "target", "source", "python", "config"))
                    require(field("target") == "this_phone_orbis")
                    val spec = OmbreRuntimeSpec(field("source"), field("python"), field("config")); spec.validate(); start(spec)
                }
                "status" -> { require(a.keys == setOf("action")); status() }
                "stop" -> { require(a.keys == setOf("action")); stop(); status() }
                else -> error("ombre_invalid_arguments")
            }
            buildJsonObject {
                put("ok", true); put("phase", state.phase); state.code?.let { put("code", it) }
                state.dashboardPort?.let { put("dashboard_url", "http://127.0.0.1:$it") }
                state.mcpPort?.let { put("mcp_url", "http://127.0.0.1:$it/mcp") }
                state.checkedAt?.let { put("health_checked_at", it) }
                put("model_reply_verified", false); put("memory_roundtrip_verified", false)
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { buildJsonObject {
            put("ok", false); put("code", "ombre_host_request_rejected")
            put("help", "先确认部署位置；本手机托管须在当前工作区开启OmbreBrain持续运行且Rootfs就绪。start须target=this_phone_orbis及已安装源码、Python、配置的绝对路径，不传密钥。先status核对，勿重复安装。")
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
