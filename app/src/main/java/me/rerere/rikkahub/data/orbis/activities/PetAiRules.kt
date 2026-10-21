package me.rerere.rikkahub.data.orbis.activities

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class PetAiTicket(val id: String, val modelId: String, val trigger: String, val text: String, val at: Long)
@Serializable data class PetSaying(val id: String, val original: String, val animal: String, val at: Long, val translated: Boolean = false)
@Serializable data class PetAiReply(val event_id: String, val actions: List<String>, val utterance: String = "", val sleepHours: Int = 1)

object PetAiRules {
    val labels = linkedMapOf("observe" to "安静看着你", "mess_up" to "捣乱了", "sleep" to "蜷起来休息",
        "noisy" to "叫了几声", "hungry" to "想吃东西", "thirsty" to "想喝水", "lonely" to "想要陪伴",
        "eat" to "吃了一些", "drink" to "喝了一些", "mood_up" to "高兴起来", "ask_pet" to "求摸摸", "ask_play" to "求陪玩", "speak" to "说了一句宠物语")
    val triggers = PetRules.actions + setOf("idle", "talk")
    fun allowed(trigger: String): Set<String> = when(trigger) {
        "feed" -> setOf("eat", "mood_up", "sleep", "ask_pet", "speak", "observe")
        "water" -> setOf("drink", "mood_up", "sleep", "speak", "observe")
        "idle" -> setOf("mess_up", "sleep", "noisy", "hungry", "thirsty", "lonely", "observe")
        else -> setOf("mood_up", "ask_pet", "ask_play", "sleep", "noisy", "speak", "observe")
    }
    fun validate(reply: PetAiReply, ticket: PetAiTicket) {
        require(reply.event_id == ticket.id && reply.actions.size in 1..3 && reply.actions.distinct().size == reply.actions.size)
        require(reply.actions.all { it in allowed(ticket.trigger) } && reply.utterance.length <= 512 && reply.sleepHours in 1..5)
        require("sleep" !in reply.actions || reply.actions.none { it in setOf("noisy", "ask_play", "mess_up") })
        require(reply.utterance.none { it.isISOControl() && it != '\n' })
    }
    fun parse(text: String, ticket: PetAiTicket): PetAiReply {
        require(text.length <= 4096)
        val trimmed = text.trim()
        val raw = if (trimmed.startsWith("```") && trimmed.endsWith("```")) trimmed.substringAfter('\n').removeSuffix("```").trim() else trimmed
        return Json.decodeFromString<PetAiReply>(raw).also { validate(it, ticket) }
    }
    fun animalWords(appearance: String, text: String): String {
        val sound = when(PetCatalog.find(appearance).family) { "cat" -> "喵"; "dog" -> "汪"; "bird" -> "啾"; else -> "✧" }
        val length = text.codePointCount(0, text.length)
        return sound.repeat(length.coerceAtMost(80)) + if (length > 80) "…" else ""
    }
    /** Companion tools see only animal speech, except translations the human has redeemed. */
    fun visible(state: PetState): PetState = state.copy(aiPending = null, sayings = state.sayings.map {
        if(it.translated) it else it.copy(original = "")
    })
}
