package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.body.*
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

internal const val ORBIS_BODY_TOOL = "orbis_body"

internal fun bodyTool(conversation: String, enabled: () -> Boolean, open: () -> BodyRepository) = Tool(
    name = ORBIS_BODY_TOOL,
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_body"),
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("rules", "state", "visibility", "pause", "resume", "draft", "activate", "event", "undo", "self_report").map(::JsonPrimitive))) })
        put("definition", buildJsonObject { put("type", "object"); put("description", "完整安全规则JSON，参照工具说明；未知字段会拒绝") })
        for(name in listOf("expected_revision", "version", "strength", "seq")) put(name, buildJsonObject { put("type", "integer") })
        for(name in listOf("event", "text")) put(name, buildJsonObject { put("type", "string") })
        put("visible", buildJsonObject { put("type", "boolean") })
        put("shared", buildJsonObject { put("type", "boolean") })
    }, required = listOf("action")) }, needsApproval = { false }, execute = { raw ->
        val invocation = currentCoroutineContext()[CloudToolInvocationContext]
        val result = try { withContext(Dispatchers.IO) {
            check(enabled()) { "body_disabled" }
            val obj = raw as? JsonObject ?: error("body_parameters_invalid")
            require(obj.toString().toByteArray().size <= 32768) { "body_definition_limit" }
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("body_parameters_invalid")
            val fields = when(action) {
                "rules", "state" -> emptySet(); "draft" -> setOf("definition", "expected_revision")
                "visibility" -> setOf("shared", "expected_revision")
                "activate", "pause", "resume" -> setOf("expected_revision"); "event" -> setOf("event", "version", "strength", "visible")
                "undo" -> setOf("seq"); "self_report" -> setOf("text"); else -> error("body_parameters_invalid")
            }
            require(obj.keys.all { it == "action" || it in fields }) { "body_parameters_invalid" }
            fun num(name: String): Long = (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("body_parameters_invalid")
            fun text(name: String): String = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("body_parameters_invalid")
            val repo = open()
            if(action == "state") buildJsonObject {
                val state = repo.viewSnapshot(); val definition = state.versions.lastOrNull()?.definition ?: BodyDefinition(triggerMode = "auto")
                val session = state.snapshot.sessions[conversation]
                put("ok", true); put("running", state.running); put("revision", state.revision)
                put("version", state.versions.lastOrNull()?.version ?: 0); put("triggerMode", definition.triggerMode)
                put("privateShared", session?.privateShared == true)
                put("values", buildJsonObject { BodyRules.values(state.snapshot, conversation, definition).forEach { (id, value) -> put(id, value) } })
                put("cooldownUntil", state.auto.cooldownUntil)
                putBodySetup(state)
            } else if(action == "rules") buildJsonObject {
                val state = repo.snapshot(); put("ok", true); put("running", state.running); put("revision", state.revision)
                put("version", state.versions.lastOrNull()?.version ?: 0)
                val definition = state.versions.lastOrNull()?.definition ?: BodyDefinition(triggerMode = "auto")
                put("definition", JsonObject(BodyRules.json.encodeToJsonElement(definition).jsonObject +
                    ("triggerMode" to JsonPrimitive(definition.triggerMode))))
                put("has_draft", state.draft != null)
                putBodySetup(state)
            } else {
                require(invocation?.conversationId == conversation) { "body_invocation_missing" }
                val key = "$conversation:${invocation!!.messageId}:${invocation.toolCallId}"
                when(action) {
                    "visibility" -> {
                        val shared = (obj["shared"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("body_parameters_invalid")
                        repo.sharePrivate(conversation, shared, num("expected_revision"), key)
                    }
                    "pause", "resume" -> repo.setRunningFromTool(conversation, action == "resume", num("expected_revision"), key)
                    "draft" -> {
                        val definition = obj["definition"] as? JsonObject ?: error("body_parameters_invalid")
                        val explicitMode = if ("triggerMode" in definition) definition else JsonObject(definition +
                            ("triggerMode" to JsonPrimitive(BodySetup.defaultDraftMode(repo.snapshot()))))
                        repo.define(BodyRules.json.decodeFromJsonElement(explicitMode), num("expected_revision"), key)
                    }
                    "activate" -> repo.activate(num("expected_revision"), key)
                    "event" -> {
                        val version = num("version"); require(version in 1..Int.MAX_VALUE.toLong()) { "body_version_changed" }
                        val strength = if("strength" in obj) num("strength") else 0
                        require(strength in 0..3) { "body_strength_invalid" }
                        val visible = if("visible" !in obj) true else (obj["visible"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("body_parameters_invalid")
                        repo.event(conversation, text("event"), strength.toInt(), visible, key, version.toInt())
                    }
                    "undo" -> repo.undo(conversation, num("seq"), key)
                    "self_report" -> repo.selfReport(conversation, text("text"), key)
                }
                buildJsonObject {
                    put("ok", true); put("status", "已记"); put("seq", repo.receiptSeq(key))
                    if (action in setOf("draft", "activate", "pause", "resume", "visibility")) {
                        val state = repo.snapshot()
                        put("revision", state.revision); put("version", state.versions.lastOrNull()?.version ?: 0)
                        put("has_draft", state.draft != null)
                        put("running", state.running)
                    }
                    // Even a legacy caller that only sends events learns that auto is available.
                    putBodySetup(repo.snapshot())
                }
            }
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { buildJsonObject {
            val code = error.message?.takeIf { it.matches(Regex("body_[a-z_]+")) } ?: "body_unavailable"
            put("ok", false); put("code", code)
            put("message", when (code) {
                "body_invocation_missing" -> "应用未提供匹配的会话调用信息，未写入。这不是人类未开启运行；先修复或更新应用的工具执行链。"
                "body_pause_before_definition" -> "当前正在运行，请先调用 pause 暂停，再 draft/activate 修改规则，最后 resume 继续。无需人类点运行。"
                "body_definition_empty" -> "尚无已发布规则。先 draft 定义、再 activate 发布，之后调用 resume 开始运行。"
                "body_private_downgrade_forbidden" -> "已标为私密的维度不能改回普通；请保留 private=true。你可用visibility选择是否公开，人类选择是否展开。"
                "body_manual_event_disabled", "body_auto_undo_disabled" -> "当前为auto，事件与计算由系统按规则触发；不能手动event或undo。可以读state，或暂停后修改规则。"
                "body_cooling_active" -> "当前仍在规则配置的冷静期，尚未恢复运行；可读state核对结束时间。"
                "body_disabled" -> "当前助手尚未启用身体模块，请先由人类开启模块，再从新的聊天轮次调用。"
                else -> "本次未确认完成，未自动重试；请读 rules 核对版本，并根据 code 检查参数。"
            })
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun JsonObjectBuilder.putBodySetup(state: BodyState) {
    put("triggerMode", state.versions.lastOrNull()?.definition?.triggerMode ?: "unconfigured")
    put("defaultTriggerMode", "auto")
    val notice = BodySetup.notice(state)
    put("setup", buildJsonObject {
        put("needs_attention", notice != null)
        put("code", notice?.code ?: "auto_ready")
        notice?.let { put("message", it.aiNextStep) }
    })
}
