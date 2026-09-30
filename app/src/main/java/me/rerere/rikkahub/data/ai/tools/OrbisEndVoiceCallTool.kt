package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus

/** Deferred voice turns retain their original host scope, even after another call starts. */
internal fun bindEndVoiceCallId(generationCallId: String?, ambientCallId: String?, allowAmbient: Boolean): String? =
    generationCallId ?: ambientCallId.takeIf { allowAmbient }

/** The generation captures one call ID. A stale tool can never target a newer call. */
internal fun createOrbisEndVoiceCallTool(
    assistantId: String,
    conversationId: String,
    boundCallId: String?,
    load: suspend (String) -> OrbisVoiceCallRecord?,
    claim: suspend (String, String?) -> OrbisVoiceCallRecord,
    endExact: suspend (String, String, String?) -> Boolean,
): Tool = Tool(
    name = "end_voice_call",
    description = "结束本轮宿主绑定的 Orbis 语音通话。call_id 可省略（当前绑定通话）；reason 可选。只允许当前AI和当前聊天的该通话，不能操作其他App的电话或其他通话。返回 ended/already_ended/failed；挂断停止收音播放，但不重发旧消息或工具。若已换成新通话，旧工具调用会拒绝，不追踪新通话。结束后的摘要是独立步骤，不能把挂断成功说成归档已完成。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("call_id", buildJsonObject { put("type", "string"); put("maxLength", 128) })
        put("reason", buildJsonObject { put("type", "string"); put("maxLength", 2000) })
    }) },
    needsApproval = { false },
    execute = { arguments ->
        suspend fun run(): JsonObject {
            val args = arguments as? JsonObject ?: return endCallReceipt("failed", "invalid_arguments")
            if (args.keys.any { it !in setOf("call_id", "reason") }) return endCallReceipt("failed", "invalid_arguments")
            fun string(name: String): String? {
                val value = args[name] ?: return null
                if (value == JsonNull) return null
                require(value is JsonPrimitive && value.isString)
                return value.content
            }
            val requested = try { string("call_id") } catch (_: IllegalArgumentException) {
                return endCallReceipt("failed", "invalid_call_id")
            }
            val reason = try { string("reason") } catch (_: IllegalArgumentException) {
                return endCallReceipt("failed", "invalid_reason")
            }
            if (requested != null && (requested.isBlank() || requested.length > 128)) return endCallReceipt("failed", "invalid_call_id")
            if (reason != null && reason.length > 2000) return endCallReceipt("failed", "invalid_reason")
            val id = boundCallId ?: return endCallReceipt("failed", "no_bound_call")
            if (requested != null && requested != id) return endCallReceipt("failed", "call_scope_mismatch")
            val before = load(id)?.takeIf { it.assistantId == assistantId && it.conversationId == conversationId }
                ?: return endCallReceipt("failed", "call_not_owned")
            if (before.status in setOf(OrbisVoiceCallStatus.ENDED, OrbisVoiceCallStatus.INTERRUPTED)) {
                return endCallReceipt("already_ended", "no_action", id)
            }
            if (before.status != OrbisVoiceCallStatus.ACTIVE || before.connectedAtMs == null) {
                return endCallReceipt("failed", "call_not_connected", id)
            }
            // Persist the intent before device side effects. The claim also revalidates ownership.
            val claimed = claim(id, reason?.trim()?.takeIf { it.isNotEmpty() })
            if (claimed.id != id || claimed.assistantId != assistantId || claimed.conversationId != conversationId ||
                claimed.status != OrbisVoiceCallStatus.ACTIVE || claimed.connectedAtMs == null || claimed.aiEndRequestedAtMs == null) {
                return endCallReceipt("failed", "call_changed", id)
            }
            return if (endExact(id, conversationId, claimed.aiEndReasonText)) endCallReceipt("ended", "ai_ended", id)
                else endCallReceipt("failed", "call_not_active", id)
        }
        val result = try { run() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { endCallReceipt("failed", "end_request_failed") }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun endCallReceipt(outcome: String, code: String, callId: String? = null) = buildJsonObject {
    put("ok", outcome != "failed"); put("outcome", outcome); put("reason_code", code)
    put("call_id", callId?.let(::JsonPrimitive) ?: JsonNull)
    put("archive_status", "not_checked"); put("instruction_authority", "none")
}
