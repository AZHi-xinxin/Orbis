package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Only saved tool receipts in this exact private turn count, never the model's prose. */
internal fun tabletopPreparationReceipt(messages:List<UIMessage>, request:String, restore:Boolean):String {
    val index=messages.indexOfLast { it.role==MessageRole.USER && it.parts.filterIsInstance<UIMessagePart.Text>().any { p->p.text.contains("[圆桌准备编号：$request]") } }
    if(index<0)return "尚未找到原窗口的输入回执；不自动重发。"
    val turn=messages.drop(index+1).takeWhile { it.role!=MessageRole.USER }
    val tools=turn.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName.startsWith("orbis_sentinel_") }
    val action=if(restore)"orbis_sentinel_resume" else "orbis_sentinel_pause"
    val writes=tools.filter { it.toolName==action }
    val saved=writes.filter { t->t.output.filterIsInstance<UIMessagePart.Text>().any { p->runCatching { Json.parseToJsonElement(p.text).jsonObject["saved"]?.jsonPrimitive?.booleanOrNull==true }.getOrDefault(false) } }.map { it.input }.distinct().size
    val pending=tools.count { !it.isExecuted }
    val failed=writes.count { it.isExecuted && it.output.filterIsInstance<UIMessagePart.Text>().none { p->runCatching { Json.parseToJsonElement(p.text).jsonObject["saved"]?.jsonPrimitive?.booleanOrNull==true }.getOrDefault(false) } }
    if(saved>0)return "已核对 AI ${if(restore)"恢复" else "暂停"} $saved 项的保存回执；待完成 $pending 项，未确认成功 $failed 项。当前状态如被后来修改，以原窗口核对为准。"
    if(pending>0)return "AI 有 $pending 项操作待完成或待批准，请回原窗口处理。"
    if(failed>0)return "操作未完成，请查看原窗口具体原因。"
    return "请求已存入原窗口，尚无${if(restore)"恢复" else "暂停"}成功回执。AI 可以决定保留；请结合原窗口回复自行确认准备状态。"
}

internal fun tabletopPreparationPrompt(request:String, room:String, restore:Boolean, prior:String?):String =
    "[圆桌准备编号：$request]\n" + if(restore)
        "我已结束圆桌（$room）。请核对开局准备编号 ${prior ?: "未记录"} 的实际工具回执和当前哨兵状态，再自行决定恢复哪些条目。只考虑本次亲自暂停且后来未被人类改动的条目；不要批量全开，不补发旧任务。若人类后来改动过，先说明并询问。工具关闭或失败时如实说明，不宣称已恢复。"
    else "我准备参加圆桌（$room）。请在这个原私人窗口读取自己的哨兵状态，自行决定哪些需要暂停、哪些保留，并使用现有且已授权的工具逐条操作，核对实际回执。不要改人类总开关，不把私人规则或聊天发到圆桌。工具关闭或失败时说明原因；可以决定保留，不要求全部关闭。请记住本次实际暂停的条目和版本，散场时再核对是否恢复。"
