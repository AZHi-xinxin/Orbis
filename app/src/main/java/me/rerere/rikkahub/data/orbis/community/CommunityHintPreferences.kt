package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*

internal data class CommunityHintPreferences(val groups: Boolean = true, val feed: Boolean = true) {
    fun enabled(channel: String) = when(channel) { "groups"->groups; "feed"->feed; "all"->groups||feed; else->error("提醒范围需要groups、feed或all") }
    fun update(channel:String, value:Boolean)=when(channel) {
        "groups"->copy(groups=value); "feed"->copy(feed=value); "all"->copy(groups=value,feed=value)
        else->error("提醒范围需要groups、feed或all")
    }
    fun json()=buildJsonObject { put("enabled",groups||feed);put("groups",groups);put("feed",feed) }
    companion object {
        fun parse(value:JsonObject):CommunityHintPreferences {
            val legacy=(value["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            return CommunityHintPreferences((value["groups"] as? JsonPrimitive)?.booleanOrNull ?: legacy,
                (value["feed"] as? JsonPrimitive)?.booleanOrNull ?: legacy)
        }
    }
}
