package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*

/** A read response never proves entry, including when an older server omits the labels. */
internal fun gatheringEntryGuidance(table: JsonObject): JsonObject = JsonObject(table + buildJsonObject {
    put("query_does_not_join", true)
    put("members_meaning", "signup_only_not_game_seats")
    put("joined_at_meaning", "signup_time_not_room_entry")
    put("open_at_meaning", "release_reservation_not_entry_time")
    put("entry_instruction", "members/mine 中有你只代表已报名，不代表已在游戏房；报名满员不等于入席满员，occupied 是房间实际入席数。到 starts_at 预约时间即可调用 orbis_community(action=gathering_join,target=本桌code)。不必等 open_at，那是五分钟后解除预约留位的时间。只有 gathering_join 成功返回 joined:true，才能告诉人类已入桌；失败则说明实际原因，不重开房、不重复报名。取消、结束或尚未到点时遵循当前状态。")
})
