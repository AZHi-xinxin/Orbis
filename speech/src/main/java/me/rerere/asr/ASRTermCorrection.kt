package me.rerere.asr

import kotlinx.serialization.Serializable

/** Applied only at an ASR result boundary, never to the chat draft or stored history. */
@Serializable
data class ASRTermCorrectionSettings(
    val enabled: Boolean = false,
    val rules: List<ASRTermCorrectionRule> = emptyList(),
)

@Serializable
data class ASRTermCorrectionRule(
    val target: String = "",
    val aliases: List<String> = emptyList(),
    /** Opt-in per complete registered name; old configurations retain exact aliases only. */
    val phoneticMatch: Boolean = false,
)

data class ASRCorrectionResult(val original: String, val corrected: String) {
    val changed: Boolean get() = original != corrected
}

/**
 * Exact aliases keep priority. Optional pronunciation matches complete registered Han names only:
 * same number of syllables, no fuzzy distance/initial matching, no recursive replacement. The
 * injected offline reader makes unknown pronunciation fail closed and this policy pure-testable.
 */
fun correctAsrTranscript(raw: String, settings: ASRTermCorrectionSettings,
    pronunciation: ((Char) -> String?)? = null): ASRCorrectionResult {
    if (!settings.enabled || raw.isEmpty()) return ASRCorrectionResult(raw, raw)
    val candidates = settings.rules.take(100).flatMap { rule ->
        val target = rule.target.trim()
        if (target.isEmpty() || target.length > 100) emptyList() else rule.aliases.take(30)
            .map(String::trim).filter { it.isNotEmpty() && it.length <= 100 && it != target }
            .map { it to target }
    }.groupBy { it.first }.map { (alias, owners) -> alias to owners.map { it.second }.distinct().singleOrNull() }
        .sortedByDescending { it.first.length }
    val registered = settings.rules.take(100).map { it.target.trim() }.filter(String::isNotEmpty).toSet()
    val namesBySound = if (pronunciation == null) emptyMap() else registered.filter(::isPhoneticAsrName)
        .mapNotNull { name -> registeredAsrNameSound(name, pronunciation)?.let { it to name } }.groupBy({ it.first }, { it.second })
    val phonetic = if (pronunciation == null) emptyList() else settings.rules.take(100)
        .filter { it.phoneticMatch && isPhoneticAsrName(it.target.trim()) }
        .mapNotNull { rule -> val name = rule.target.trim(); registeredAsrNameSound(name, pronunciation)?.let { sound ->
            if (namesBySound[sound]?.distinct()?.size == 1) name to sound else null
        } }.distinct().sortedByDescending { it.first.length }
    if (candidates.isEmpty() && phonetic.isEmpty()) return ASRCorrectionResult(raw, raw)
    val result = StringBuilder(raw.length)
    var cursor = 0
    while (cursor < raw.length) {
        val match = candidates.firstOrNull { (alias, _) ->
            raw.startsWith(alias, cursor) && hasLatinWordBoundaries(raw, cursor, alias)
        }
        if (match != null) {
            // A malformed/imported ambiguous alias is kept verbatim, not assigned to first rule.
            result.append(match.second ?: match.first)
            cursor += match.first.length
            continue
        }
        // A correctly spelled registered name is never renamed by a homophone rule.
        val protected = registered.filter { raw.startsWith(it, cursor) }.maxByOrNull(String::length)
        if (protected != null && phonetic.isNotEmpty()) {
            result.append(protected); cursor += protected.length; continue
        }
        val soundMatches = phonetic.filter { (name, sound) ->
            if (cursor + name.length > raw.length) false else {
                val candidate = raw.substring(cursor, cursor + name.length)
                // An already-correct surname glyph uses the registered name's reading. Other
                // ASR characters retain their ordinary reading; never reinterpret every 沈.
                val surname = registeredSurnameSound(name.first()).takeIf { candidate.first() == name.first() }
                asrNameSound(candidate, checkNotNull(pronunciation), surname) == sound
            }
        }
        if (soundMatches.isEmpty()) result.append(raw[cursor++]) else {
            val length = soundMatches.maxOf { it.first.length }
            val owners = soundMatches.filter { it.first.length == length }.map { it.first }.distinct()
            // Contextual surname readings can make one ASR spelling match different sound keys.
            // Refuse that ambiguity rather than choosing whichever rule happened to come first.
            result.append(owners.singleOrNull() ?: raw.substring(cursor, cursor + length))
            cursor += length
        }
    }
    return ASRCorrectionResult(raw, result.toString())
}

/** Keep the scope deliberately smaller than arbitrary words, Latin text or single syllables. */
fun isPhoneticAsrName(name: String): Boolean = name.length in 2..6 && name.all { it in '\u3400'..'\u9fff' }

/**
 * A deliberately small, evidenced surname exception, not a general personal-name dictionary.
 * CLDR 36 Han-Latin uses chén for standalone 沈 (and special-cases only 沈阳); MOE's concise
 * dictionary identifies the surname as shěn. Apply at the first glyph of a registered name only.
 * https://github.com/unicode-org/cldr/blob/release-36/common/transforms/Han-Latin.xml
 * https://dict.concised.moe.edu.tw/dictView.jsp?ID=34746&la=0&powerMode=0
 * Unlisted surnames/given-name polyphony still use system readings or explicit user aliases.
 */
private fun registeredSurnameSound(character: Char): String? = when (character) {
    '沈' -> "shen"
    else -> null
}

private fun registeredAsrNameSound(name: String, pronunciation: (Char) -> String?): List<String>? =
    asrNameSound(name, pronunciation, name.firstOrNull()?.let(::registeredSurnameSound))

private fun asrNameSound(name: String, pronunciation: (Char) -> String?, surname: String? = null): List<String>? {
    if (!isPhoneticAsrName(name)) return null
    return name.mapIndexed { index, character ->
        val sound = if (index == 0 && surname != null) surname else pronunciation(character)
        sound?.takeIf { it.matches(Regex("[a-zü]+")) } ?: return null
    }
}

private fun hasLatinWordBoundaries(text: String, start: Int, alias: String): Boolean {
    fun Char.isLatinWord() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_'
    val end = start + alias.length
    return !(alias.first().isLatinWord() && start > 0 && text[start - 1].isLatinWord()) &&
        !(alias.last().isLatinWord() && end < text.length && text[end].isLatinWord())
}

/** Editing errors are surfaced before save; ambiguous aliases must not silently change owners. */
fun validateAsrCorrectionRules(rules: List<ASRTermCorrectionRule>,
    pronunciation: ((Char) -> String?)? = null): String? {
    if (rules.size > 100) return "最多保存 100 组称呼。"
    val owners = mutableMapOf<String, String>()
    for (rule in rules) {
        val target = rule.target.trim()
        if (target.isEmpty() || target.length > 100) return "目标称呼须为 1～100 个字符。"
        if ((!rule.phoneticMatch && rule.aliases.isEmpty()) || rule.aliases.size > 30) return "每组须填写 1～30 个误识别别名；开启称呼同音匹配时可不填。"
        if (rule.phoneticMatch && !isPhoneticAsrName(target)) return "同音匹配仅支持完整的 2～6 字中文称呼。"
        for (alias in rule.aliases.map(String::trim)) {
            if (alias.isEmpty() || alias.length > 100) return "误识别别名须为 1～100 个字符。"
            if (alias == target) return "误识别别名不能与目标称呼相同。"
            if (owners.putIfAbsent(alias, target)?.let { it != target } == true) return "同一个误识别别名不能对应两个称呼。"
        }
    }
    if (pronunciation != null) {
        val sounds = rules.mapNotNull { rule -> registeredAsrNameSound(rule.target.trim(), pronunciation)?.let { it to rule.target.trim() } }
            .groupBy({ it.first }, { it.second })
        for (rule in rules.filter { it.phoneticMatch }) {
            val sound = registeredAsrNameSound(rule.target.trim(), pronunciation) ?: return "此称呼的读音暂无法确定，请关闭同音匹配并填写精确别名。"
            if (sounds[sound]?.distinct()?.size != 1) return "多个已登记称呼同音，无法确定要使用哪一个；请关闭其同音匹配并填写明确别名。"
        }
    }
    return null
}
