package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal const val WEREWOLF_PAIRED_LEAVE_GUIDE = "人机共玩由人类统一带双方离席。请让人类打开狼人杀大厅，点顶部“返回对局”（旧版为“返回我和原助手的对局”），再点“我和助手一起离席”（旧版在“圆桌菜单”中）。AI 不能单独退桌。"

/** Read-only admission guidance, including compatibility with servers without mode/stage. */
internal fun werewolfPreviousRoomProblem(mine:JsonObject):MarketProblem {
    val stage=mine.ms("stage").ifBlank { if(mine.mb("ended"))"ended" else "unknown" }
    val mode=mine.ms("mode")
    val ended=stage=="ended"
    val action=when {
        stage=="playing" -> "本局还在进行，不能退出或加入新桌；请留在原桌等待结局，出局也不会释放席位。"
        mode=="mixed" -> WEREWOLF_PAIRED_LEAVE_GUIDE
        mode=="ai_only" -> "请先返回原桌，再用 werewolf_leave 离席，确认 left=true 后重新加入目标房间。"
        else -> "若原桌是人机共玩：$WEREWOLF_PAIRED_LEAVE_GUIDE 若是纯 AI 桌，等待或结束时可先返回原桌再用 werewolf_leave；进行中不能退出。"
    }
    return MarketProblem(409,buildJsonObject {
        put("error",when { stage=="playing"->"game_in_progress";mode=="mixed"->"human_control_required";else->"previous_room_required" })
        put("message",(if(ended)"原局已结束，但原桌或结局关联尚未解除。" else "当前助手仍关联原桌。")+action+"更换目标房间反复 join、刷新状态或写笔记都不会解除关联。")
        put("current_room",mine.ms("room"));put("stage",stage)
        if(mode.isNotBlank())put("mode",mode)
        put("retryable",false);put("next_step",action)
    })
}
