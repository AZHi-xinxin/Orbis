package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.OrbisConversationPrompt

/** The host binds the current conversation; callers cannot supply another window's ID. */
internal fun createOrbisConversationSettingsTool(
    enabled: () -> Boolean,
    read: suspend () -> OrbisConversationPrompt,
    update: suspend ((OrbisConversationPrompt) -> OrbisConversationPrompt) -> OrbisConversationPrompt,
) = Tool(
    name = "orbis_conversation_settings",
    description = orbisToolFullDescription("orbis_conversation_settings"),
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject {
            put("type", "string"); put("enum", JsonArray(listOf("inspect", "update").map(::JsonPrimitive)))
        })
        listOf("text", "world_book_text").forEach { name ->
            put(name, buildJsonObject { put("type", "string"); put("description", "省略保留原文；空字符串清空正文。") })
        }
        listOf("enabled", "world_book_enabled").forEach { name ->
            put(name, buildJsonObject { put("type", "boolean"); put("description", "省略保留开关；false关闭但保留正文。") })
        }
    }, required = listOf("action")) },
    isSelected = enabled,
    execute = { input ->
        check(enabled()) { "当前助手尚未开启会话设置工具" }
        val args = input as? JsonObject ?: error("参数必须是对象")
        require(args.keys.all { it in setOf("action", "text", "enabled", "world_book_text", "world_book_enabled") }) {
            "仅支持当前会话的提示词、世界书正文和启用开关"
        }
        fun string(name: String): String? = if (name !in args) null else
            (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$name 必须是字符串")
        fun boolean(name: String): Boolean? = if (name !in args) null else
            (args[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("$name 必须是布尔值")
        val action = string("action")
        val result = when (action) {
            "inspect" -> {
                require(args.keys == setOf("action")) { "inspect不修改设置，请使用update" }
                read()
            }
            "update" -> {
                require(args.size > 1) { "请提供要修改的字段" }
                val text = string("text")
                val promptEnabled = boolean("enabled")
                val worldBook = string("world_book_text")
                val worldBookEnabled = boolean("world_book_enabled")
                update { latest -> latest.copy(
                    text = text ?: latest.text,
                    enabled = promptEnabled ?: latest.enabled,
                    worldBookText = worldBook ?: latest.worldBookText,
                    worldBookEnabled = worldBookEnabled ?: latest.worldBookEnabled,
                ) }
            }
            else -> error("action需为inspect或update")
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("status", if (action == "update") "saved" else "ok")
            put("scope", "current_conversation")
            put("effective_from", "next_turn")
            put("text", result.text); put("enabled", result.enabled)
            put("world_book_text", result.worldBookText); put("world_book_enabled", result.worldBookEnabled)
        }.toString()))
    },
)
