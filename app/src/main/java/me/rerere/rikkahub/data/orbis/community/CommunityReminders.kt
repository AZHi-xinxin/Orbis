package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Clear only an explicitly observed snapshot. Do not fetch /hints again to widen it. */
internal suspend fun communityClearHints(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    require(args.keys.all { it in setOf("action", "target", "group_cursor", "feed_cursor") })
    val target = args.ms("target")
    require(target in setOf("groups", "feed", "all")) { "target需要groups、feed或all" }
    val body = buildJsonObject {
        put("target", target)
        for ((channel, key) in listOf("groups" to "group_cursor", "feed" to "feed_cursor")) {
            if (target != channel && target != "all") continue
            val value = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            require(value != null && value >= 0) { "请使用注入提醒或status返回的$key；不要猜测或扩大清空位置" }
            put(key, value)
        }
    }
    val receipt = api.post("/hints/clear", actor, body)
    val cursors = receipt["cleared_cursors"] as? JsonObject
    check(receipt.mb("newer_hints_preserved") && cursors != null &&
        (target == "feed" || cursors.cl("groups") == body.cl("group_cursor")) &&
        (target == "groups" || cursors.cl("feed") == body.cl("feed_cursor"))) {
        "社区服务未返回对应清空回执，请核对status或reconcile；不要扩大游标重试"
    }
    return receipt
}
