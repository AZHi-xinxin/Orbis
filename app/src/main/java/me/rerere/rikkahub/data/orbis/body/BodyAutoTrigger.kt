package me.rerere.rikkahub.data.orbis.body

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

@Serializable data class BodyAutoMark(val conversation: String, val event: String, val fingerprint: String, val at: Long)
@Serializable data class BodyAutoState(val marks: List<BodyAutoMark> = emptyList(),
    val cooldownUntil: Long = 0, val pausedByCooling: Boolean = false,
    val blockedEvents: Set<String> = emptySet())
data class BodyAutoEvent(val event: String, val concentration: Double)
data class BodyAutoDecision(val events: List<BodyAutoEvent>, val auto: BodyAutoState,
    val cooled: Boolean = false)

/** Local, deterministic phrase matching. No provider calls and no stored user text. */
object BodyAutoTrigger {
    fun windowMs(definition: BodyDefinition): Long = definition.autoPolicy.windowMinutes * 60_000L
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), "").trim('，', ',', '。', '.', '！', '!', '？', '?')
    private fun sentences(text: String) = text.take(32000).split(Regex("[。！？!?\\n]+"))
        .map(::normalized).filter { it.isNotBlank() }.take(256)
    private fun fingerprint(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    /** Longest matches claim their span so a keyword and its longer alias cannot double count. */
    fun portions(sentence: String, rule: BodyRule): Int {
        val taken = BooleanArray(sentence.length); var count = 0
        (rule.keywords + rule.synonyms).map(::normalized).filter { it.isNotEmpty() }.distinct()
            .sortedByDescending { it.length }.forEach { word ->
                var start = sentence.indexOf(word)
                while (start >= 0 && count < 30) {
                    if ((start until start + word.length).none { taken[it] }) {
                        (start until start + word.length).forEach { taken[it] = true }; count++
                    }
                    start = sentence.indexOf(word, start + word.length)
                }
            }
        return count
    }
    fun evaluate(definition: BodyDefinition, previous: BodyAutoState, conversation: String,
        text: String, now: Long, running: Boolean): BodyAutoDecision {
        val sentences = sentences(text)
        val recent = previous.marks.filter { now - it.at < windowMs(definition) }.toMutableList()
        if (!running) return BodyAutoDecision(emptyList(), previous.copy(marks = recent))
        val cooling = definition.cooling
        val coolingMatch = BodyRule("cooling_match", emptyMap(), cooling.keywords, cooling.synonyms)
        val coolingSentences = sentences.filter { portions(it, coolingMatch) > 0 }
        val cooled = coolingSentences.isNotEmpty()
        val current = if (cooled) previous.copy(marks = recent, pausedByCooling = cooling.pauseAuto,
            cooldownUntil = maxOf(previous.cooldownUntil, now + cooling.cooldownMinutes * 60_000L),
            blockedEvents = previous.blockedEvents.takeIf { now < previous.cooldownUntil }.orEmpty() + cooling.blockedEvents)
        else previous.copy(marks = recent)
        val events = mutableListOf<BodyAutoEvent>()
        fun admit(rule: BodyRule, sentence: String, concentration: Double) {
            val fingerprint = fingerprint(sentence)
            val sameEvent = recent.filter { it.conversation == conversation && it.event == rule.event }
            if (sameEvent.size >= definition.autoPolicy.maxEventsPerWindow ||
                sameEvent.any { it.fingerprint == fingerprint } || recent.size >= 4096) return
            recent += BodyAutoMark(conversation, rule.event, fingerprint, now)
            events += BodyAutoEvent(rule.event, concentration.coerceAtMost(definition.autoPolicy.maxConcentration))
        }
        if (cooled) definition.rules.find { it.event == cooling.event }?.let { rule ->
            // A single configured cooling response, not a forced decrease of all dimensions.
            admit(rule, coolingSentences.first(), 1.0)
        }
        for (sentence in sentences) for (rule in definition.rules) {
            val hits = portions(sentence, rule); if (hits == 0) continue
            if (cooled && (cooling.pauseAuto || rule.event == cooling.event)) continue
            if (now < current.cooldownUntil && rule.event in current.blockedEvents) continue
            admit(rule, sentence, hits * rule.weight)
        }
        return BodyAutoDecision(events, current.copy(marks = recent), cooled = cooled)
    }
    fun validate(state: BodyAutoState) {
        require(state.cooldownUntil >= 0 && state.blockedEvents.size <= 64 && state.blockedEvents.all(BodyRules::id) && state.marks.size <= 4096 && state.marks.all {
            BodyRules.id(it.conversation) && BodyRules.id(it.event) && it.at >= 0 && it.fingerprint.matches(Regex("[a-f0-9]{64}"))
        }) { "body_auto_state_invalid" }
    }
}
