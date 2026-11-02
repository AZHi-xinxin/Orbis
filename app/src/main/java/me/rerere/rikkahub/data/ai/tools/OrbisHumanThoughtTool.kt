package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal fun humanThoughtTool(
    owner: Uuid,
    snapshot: () -> Settings,
    update: suspend ((Settings) -> Settings) -> Boolean,
) = Tool(
    name = "orbis_human_thought",
    description = "开启、关闭或查看当前助手的人类思考链输入模式。开启后，人类发送的【人类思考链】与【正文回复】属于同一条消息；思考链可留空，空栏标为为空。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("status", "enable", "disable").map(::JsonPrimitive)))
        })
    }, required = listOf("action")) },
    needsApproval = { false },
    execute = { args ->
        val result = try {
            val obj = args as? JsonObject ?: error("invalid_arguments")
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(obj.keys == setOf("action") && action in setOf("status", "enable", "disable")) { "invalid_arguments" }
            val current = snapshot().assistants.find { it.id == owner } ?: error("assistant_missing")
            check(current.allowHumanThought) { "human_thought_not_authorized" }
            if (action != "status") {
                check(update { settings ->
                    val live = settings.assistants.find { it.id == owner } ?: error("assistant_missing")
                    check(live.allowHumanThought) { "human_thought_not_authorized" }
                    settings.copy(assistants = settings.assistants.map {
                        if (it.id == owner) it.copy(humanThoughtEnabled = action == "enable") else it
                    })
                }) { "human_thought_save_failed" }
            }
            val live = snapshot().assistants.find { it.id == owner } ?: error("assistant_missing")
            buildJsonObject {
                put("ok", true)
                put("enabled", live.allowHumanThought && live.humanThoughtEnabled)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = error.message?.takeIf { it in setOf("invalid_arguments", "assistant_missing",
                "human_thought_not_authorized", "human_thought_save_failed") } ?: "human_thought_failed"
            buildJsonObject { put("ok", false); put("code", code) }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
