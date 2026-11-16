package me.rerere.rikkahub.data.orbis.webshortcuts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

internal fun webShortcutTool(assistant: String, conversation: String?, enabled: () -> Boolean,
    open: () -> WebShortcutRepository) = Tool(
    name = "orbis_web_shortcuts",
    description = "管理当前助手与你共用的快捷网页收藏。action=list/add/remove/show；add 必填 url，可填 title；remove/show 必填 id。show 请求直接在当前聊天弹出小网页浮窗，最多同时8页，人类可以最小化保留、切页或关闭；关闭后一次新的show可重开。list/add/remove只处理本地收藏；show会由前台网页加载器联网，但不向AI回传页面或浏览记录。AI只能删除自己保存的条目。外界网页默认只读；需要交互的自建 MCP 游戏由人类按站点授权并关联已有 MCP。网页与对应 MCP 须使用同一游戏账号/房间，操作结果通过该 MCP 工具读取，不存在通用浏览监控。应用密钥不会注入网页。工具默认关闭。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type","string"); put("enum",JsonArray(listOf("list","add","remove","show").map(::JsonPrimitive))) })
        for (key in listOf("url","title","id")) put(key,buildJsonObject { put("type","string") })
        put("offset",buildJsonObject { put("type","integer"); put("minimum",0) })
    }, required=listOf("action")) }, needsApproval={false}, execute={raw ->
        val invocation = currentCoroutineContext()[CloudToolInvocationContext]
        val result = try { withContext(Dispatchers.IO) {
            check(enabled()) { "web_shortcuts_disabled" }
            val args = raw as? JsonObject ?: error("web_shortcut_parameters")
            fun str(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val action = str("action") ?: error("web_shortcut_parameters")
            val fields = when(action) { "list" -> setOf("offset"); "add" -> setOf("url","title"); "remove","show" -> setOf("id"); else -> error("web_shortcut_parameters") }
            require(args.keys.all { it == "action" || it in fields }) { "web_shortcut_parameters" }
            val repo = open()
            fun entry(e: WebShortcut) = Json.encodeToJsonElement(e)
            when(action) {
                "list" -> {
                    val offset = args["offset"]?.jsonPrimitive?.let { require(!it.isString); it.int } ?: 0
                    require(offset >= 0); val entries = repo.snapshot().entries.asReversed()
                    buildJsonObject { put("ok",true); put("items",JsonArray(entries.drop(offset).take(30).map(::entry)))
                        put("next_offset",if(offset+30<entries.size) JsonPrimitive(offset+30) else JsonNull) }
                }
                "add" -> buildJsonObject { put("ok",true); put("entry",entry(repo.add(str("url") ?: error("web_shortcut_url"),str("title").orEmpty(),"ai",conversation))) }
                "remove" -> { repo.remove(str("id") ?: error("web_shortcut_parameters"),false); buildJsonObject { put("ok",true) } }
                else -> {
                    check(conversation != null && invocation?.conversationId == conversation) { "web_shortcut_conversation_missing" }
                    val e = repo.snapshot().entries.firstOrNull { it.id == str("id") } ?: error("web_shortcut_missing")
                    WebRecommendations.show(assistant, conversation, e)
                    buildJsonObject { put("ok",true); put("status","已请求在所属聊天直接弹出网页浮窗；该聊天在前台时加载。人类可最小化或关闭，不追踪浏览操作；这不是网页加载成功回执。") }
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { buildJsonObject { put("ok",false)
            put("code",error.message?.takeIf { it.matches(Regex("web_[a-z_]+")) } ?: "web_shortcut_unavailable")
            put("message","未完成。只接受 http/https 无内嵌账号密码的链接；请核对 action 和必要字段。人类保存的条目只能由人类删除。") } }
        listOf(UIMessagePart.Text(result.toString()))
    })
