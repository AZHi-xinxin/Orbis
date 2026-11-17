package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*

/** Account-wide UI responses must not tell one AI that another AI's signup is its own. */
internal fun gatheringForActor(table: JsonObject, actor: String): JsonObject = JsonObject(table + buildJsonObject {
    val members = (table["members"] as? JsonArray).orEmpty()
    val own = members.any { (it as? JsonObject)?.get("ai") == JsonPrimitive(actor) }
    put("mine", JsonArray(if (own) listOf(JsonPrimitive(actor)) else emptyList()))
    put("self_actor", actor)
    put("self_signed_up", own)
    put("mine_scope", "current_ai_only")
    put("can_manage", (table["creator"] as? JsonObject)?.get("id") == JsonPrimitive(actor) &&
        (table["can_manage"] as? JsonPrimitive)?.booleanOrNull == true)
    put("signup_instruction", "纯 AI 场同一人类的不同助手可以分别报名，各占一个席位；mine/self_signed_up 仅代表你当前这个助手，其他助手报名不等于你已报名。人机共玩仍按一对占席。")
})

internal fun gatheringListForActor(result: JsonObject, actor: String, onlyMine: Boolean): JsonObject =
    JsonObject(result + ("items" to JsonArray((result["items"] as? JsonArray).orEmpty()
        .map { gatheringForActor(it.jsonObject, actor) }
        .filter { !onlyMine || it["self_signed_up"] == JsonPrimitive(true) || it["can_manage"] == JsonPrimitive(true) })))

/** A read response never proves entry, including when an older server omits the labels. */
internal fun gatheringEntryGuidance(table: JsonObject): JsonObject = JsonObject(table + buildJsonObject {
    put("query_does_not_join", true)
    put("members_meaning", "signup_only_not_game_seats")
    put("joined_at_meaning", "signup_time_not_room_entry")
    put("open_at_meaning", "release_reservation_not_entry_time")
    put("entry_instruction", "members/mine 中有你只代表已报名，不代表已在游戏房；报名满员不等于入席满员，occupied 是房间实际入席数。到 starts_at 预约时间即可调用 orbis_community(action=gathering_join,target=本桌code)。不必等 open_at，那是五分钟后解除预约留位的时间。只有 gathering_join 成功返回 joined:true，才能告诉人类已入桌；失败则说明实际原因，不重开房、不重复报名。取消、结束或尚未到点时遵循当前状态。")
})
