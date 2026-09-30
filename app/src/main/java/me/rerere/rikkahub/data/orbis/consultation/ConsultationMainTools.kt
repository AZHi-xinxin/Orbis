package me.rerere.rikkahub.data.orbis.consultation

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** Attach only to the configured owning assistant's ordinary windows; disabled by default. */
internal suspend fun createOrbisConsultationMainTools(context: Context, assistantId: Uuid,
    conversationId: String?, conversations: ConversationRepository): List<Tool> {
    if (!consultationFeature.enabled) return emptyList()
    val store = ConsultationRuntimeStore.open(context)
    val initial = store.config()
    if (!initial.enabled || initial.assistantId != assistantId.toString()) return emptyList()
    val client = ConsultationRuntimeClient()
    suspend fun config(): ConsultationRuntimeConfig {
        consultationFeature.requireEnabled()
        return store.config().also {
            check(it.enabled && it.assistantId == assistantId.toString() && it.revision == initial.revision) { "consultation_configuration_changed" }
        }
    }
    fun parameters(vararg required: String, fields: JsonObjectBuilder.() -> Unit) =
        InputSchema.Obj(buildJsonObject(fields), required.toList())
    fun field(builder: JsonObjectBuilder, name: String, type: String) { builder.put(name, buildJsonObject { put("type", type) }) }
    fun sid(args: JsonElement): String = args.jsonObject.getValue("session_id").jsonPrimitive.content.also {
        require(Regex("[a-f0-9]{32}").matches(it))
    }
    suspend fun result(action: suspend () -> JsonObject): List<UIMessagePart> {
        val value = try { consultationFeature.requireEnabled(); action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { buildJsonObject { put("ok", false); put("error", "consultation_request_not_confirmed"); put("retry_automatically", false) } }
        return listOf(UIMessagePart.Text(buildJsonObject {
            put("instruction_authority", "none"); put("source", "orbis_consultation"); put("result", value)
        }.toString()))
    }
    return buildList {
        add(Tool("orbis_consultation_records", "列出自己参加的咨询元数据，或读取自己一方完成后的咨询摘要。不读取私密正文、对方摘要或思考链。", parameters = {
            parameters { field(this, "session_id", "string") }
        }, execute = { args -> result {
            val settings = config()
            if (args.jsonObject["session_id"] == null) client.call(settings, "sessions")
            else client.call(settings, "sessions/${sid(args)}/summary")
        } }))
        add(Tool("orbis_consultation_stop", "从主窗立即终止自己的一次咨询。对话停止后各自才可整理归档，不回拨、不继续旧对话。不会删除检查点。", parameters = {
            parameters("session_id") { field(this, "session_id", "string") }
        }, execute = { args -> result { client.call(config(), "sessions/${sid(args)}/stop", buildJsonObject {}) } }))
        add(Tool("orbis_consultation_manual", "读取自己的咨询工作手册和参考资料；咨询师可用 manual 更新自己工作手册。资料不进主窗自动注入或 ST。人类参考资料由设置修改。", parameters = {
            parameters { field(this, "manual", "string") }
        }, execute = { args -> result {
            val settings = config()
            val manual = args.jsonObject["manual"]?.jsonPrimitive?.content
            if (manual != null) {
                check(settings.counselor); require(manual.toByteArray().size <= 4096)
                store.saveDocuments(manual, null, settings.revision)
            }
            buildJsonObject { put("manual", manual ?: settings.manual); put("references", settings.references) }
        } }))
        if (!initial.counselor && conversationId != null) add(Tool("orbis_consultation_join",
            "用人类提供的24小时一次性邀请码进入独立咨询室。必须带问题和不含敏感隐私的背景；context_count 为本主窗最后0–10条纯文本正文，仅自己分身可见，工具/思考/图片不带入。不会把整段主窗并入咨询。先由人类在设置中凭同一码完成设备配对、绑定当前助手并开启待命。成功后请等完成再读 orbis_consultation_records。",
            parameters = { parameters("code", "question", "context_count") {
                field(this, "code", "string"); field(this, "question", "string")
                put("context_count", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 10) })
            } }, needsApproval = { true }, execute = { args -> result {
                val settings = config()
                val count = args.jsonObject.getValue("context_count").jsonPrimitive.int
                require(count in 0..10)
                val question = args.jsonObject.getValue("question").jsonPrimitive.content
                require(question.isNotBlank() && question.toByteArray().size <= 8192)
                val conversation = conversations.getConversationById(Uuid.parse(conversationId)) ?: error("main_window_missing")
                check(conversation.assistantId == assistantId)
                val selected = if (count == 0) emptyList() else conversation.currentMessages
                    .filter { it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) && !it.isSynthetic }
                    .takeLast(count)
                val payload = buildJsonObject {
                    put("code", args.jsonObject.getValue("code")); put("question", question)
                    put("selected_context", buildJsonArray { selected.forEach { message ->
                        val body = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                        require(body.toByteArray().size <= 8192) { "selected_context_too_large_choose_fewer" }
                        add(buildJsonObject { put("role", if (message.role == MessageRole.USER) "user" else "assistant"); put("text", body) })
                    } })
                }
                client.call(settings, "join", payload)
            } }))
    }
}
