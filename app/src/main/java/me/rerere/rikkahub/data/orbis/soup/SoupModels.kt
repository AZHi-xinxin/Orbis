package me.rerere.rikkahub.data.orbis.soup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal enum class SoupMode(val label: String, val questions: Int?, val hints: Int) {
    EASY("轻松", null, 3), NORMAL("正常", 6, 3), HARD("严格", 3, 0),
}
@Serializable internal enum class SoupPlayer(val label: String) { HUMAN("你"), TEAMMATE("伙伴") }
@Serializable internal enum class SoupAction { ASK, SUBMIT }
@Serializable internal enum class SoupAttemptState { RUNNING, UNKNOWN, ACKNOWLEDGED, COMPLETE }
@Serializable internal enum class SoupProposalState { PENDING, CONFIRMED, DECLINED }
@Serializable internal data class SoupProposal(
    val id: String, val action: SoupAction, val text: String, val at: Long,
    val state: SoupProposalState = SoupProposalState.PENDING,
)
@Serializable internal data class SoupQuestion(val player: SoupPlayer, val text: String, val answer: String, val at: Long)
@Serializable internal data class SoupScore(
    val player: SoupPlayer, val submitted: String, val keyPlot: Int, val logic: Int, val detail: Int,
    val comment: String, val at: Long,
) {
    val overall: Int get() = (keyPlot * 40 + logic * 30 + detail * 30 + 50) / 100
    val rank: String get() = when { overall >= 90 -> "完全还原"; overall >= 70 -> "基本还原"; overall >= 40 -> "部分还原"; else -> "方向错误" }
}
@Serializable internal data class SoupAttempt(
    val id: String, val action: SoupAction, val player: SoupPlayer, val text: String,
    val modelId: String, val modelLabel: String, val endpoint: String, val at: Long,
    val state: SoupAttemptState = SoupAttemptState.RUNNING,
)
@Serializable internal data class SoupSession(
    val id: String, val puzzleId: String, val mode: SoupMode, val startedAt: Long,
    val currentPlayer: SoupPlayer = SoupPlayer.HUMAN,
    val questions: List<SoupQuestion> = emptyList(), val hintsUsed: Int = 0,
    val submissions: List<SoupScore> = emptyList(), val attempts: List<SoupAttempt> = emptyList(),
    val revealed: Boolean = false, val abandoned: Boolean = false,
    val proposals: List<SoupProposal> = emptyList(),
) {
    val pending: SoupAttempt? get() = attempts.lastOrNull { it.state in setOf(SoupAttemptState.RUNNING, SoupAttemptState.UNKNOWN) }
    val playable: Boolean get() = !revealed && !abandoned
    val proposal: SoupProposal? get() = proposals.lastOrNull { it.state == SoupProposalState.PENDING }
    fun remaining(player: SoupPlayer): Int? = if (submissions.any { it.player == player }) 0 else
        mode.questions?.minus(questions.count { it.player == player })
}
@Serializable internal data class SoupState(
    val version: Int = 1, val revision: Long = 0, val hostModelId: String? = null,
    val activeId: String? = null, val sessions: List<SoupSession> = emptyList(),
) { val active: SoupSession? get() = sessions.firstOrNull { it.id == activeId } }

internal data class SoupPuzzle(
    val id: String, val title: String, val difficulty: String, val face: String, val solution: String,
    val elements: List<String>, val hints: List<String>,
)
internal val soupJson = Json { encodeDefaults = true }
internal const val SOUP_CALL_LIMIT = 60
internal val SOUP_ANSWERS = setOf("是", "否", "是也不是", "无关")

internal fun soupText(value: String, max: Int) {
    require(value.isNotBlank() && value.length <= max && '\u0000' !in value && Charsets.UTF_8.newEncoder().canEncode(value)) { "soup_invalid_text" }
}

/** Same local yes/no guard as the reference server; rejection is never a paid call. */
internal fun soupYesNoQuestion(value: String): Boolean {
    val q = value.trim().trimEnd('?', '？')
    if (q.endsWith("吗") || q.endsWith("么") || q.endsWith("嘛")) return true
    if (Regex("是不是|能不能|会不会|有没有|该不该|是否|能否|可不可以|应不应该|算不算|意味着|代表|导致|引起").containsMatchIn(q)) return true
    return !Regex("^(什么|为什么|哪里|谁|怎么|多少|何时|哪个|哪些|怎样|如何|请|描述|解释|告诉我|说说|到底|究竟)").containsMatchIn(q)
}

internal fun soupResponseObject(text: String): JsonObject {
    require(text.toByteArray(Charsets.UTF_8).size <= 16_384) { "soup_invalid_reply" }
    val trimmed = text.trim()
    val plain = if (trimmed.startsWith("```json\n") && trimmed.endsWith("```")) trimmed.removePrefix("```json\n").removeSuffix("```").trim() else trimmed
    return soupJson.parseToJsonElement(plain) as? JsonObject ?: error("soup_invalid_reply")
}

internal fun soupParseAnswer(text: String): String {
    val obj = soupResponseObject(text)
    require(obj.keys == setOf("answer")) { "soup_invalid_reply" }
    val answer = obj["answer"]?.jsonPrimitive?.takeIf { it.isString }?.content
    require(answer in SOUP_ANSWERS) { "soup_invalid_reply" }
    return requireNotNull(answer)
}

internal fun soupParseScore(text: String, player: SoupPlayer, submitted: String, at: Long): SoupScore {
    val obj = soupResponseObject(text)
    require(obj.keys.all { it in setOf("key_plot", "logic", "detail", "overall", "rank", "comment") }) { "soup_invalid_reply" }
    fun score(key: String): Int = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?.takeIf { it in 0..100 } ?: error("soup_invalid_reply")
    val comment = (obj["comment"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("soup_invalid_reply")
    soupText(comment, 1200)
    // Compute the published weights locally; a model cannot override the total or winning threshold.
    return SoupScore(player, submitted, score("key_plot"), score("logic"), score("detail"), comment, at)
}

/** The only player-facing projection. Never expose private fields or model grading prose before reveal. */
internal fun soupPublicSession(session: SoupSession): JsonObject {
    val puzzle = SoupCatalogue.get(session.puzzleId)
    return buildJsonObject {
        put("session_id", session.id); put("title", puzzle.title); put("soup_face", puzzle.face)
        put("difficulty", puzzle.difficulty); put("mode", session.mode.name); put("current_player", session.currentPlayer.name)
        put("revealed", session.revealed); put("abandoned", session.abandoned)
        put("status", when { session.abandoned -> "abandoned"; session.submissions.any { it.overall >= 90 } -> "won"; session.revealed -> "lost"; else -> "playing" })
        put("human_questions_left", session.remaining(SoupPlayer.HUMAN)?.let(::JsonPrimitive) ?: JsonNull)
        put("teammate_questions_left", session.remaining(SoupPlayer.TEAMMATE)?.let(::JsonPrimitive) ?: JsonNull)
        put("hints_total", minOf(session.mode.hints, puzzle.hints.size)); put("hints_used", session.hintsUsed)
        put("revealed_hints", JsonArray(puzzle.hints.take(session.hintsUsed).map(::JsonPrimitive)))
        put("questions", buildJsonArray { session.questions.forEach { q -> add(buildJsonObject {
            put("player", q.player.name); put("question", q.text); put("answer", q.answer); put("at", q.at)
        }) } })
        put("submissions", buildJsonArray { session.submissions.forEach { s -> add(buildJsonObject {
            put("player", s.player.name); put("submitted_answer", s.submitted); put("score", s.overall); put("rank", s.rank)
            put("key_plot", s.keyPlot); put("logic", s.logic); put("detail", s.detail)
            if (session.revealed) put("comment", s.comment)
        }) } })
        put("calls_used", session.attempts.size); put("call_limit", SOUP_CALL_LIMIT)
        session.proposal?.let { put("pending_proposal", buildJsonObject {
            put("proposal_id", it.id); put("action", it.action.name); put("player", SoupPlayer.TEAMMATE.name); put("text", it.text)
            put("state", "awaiting_human_confirmation")
            put("note", "只保存了伙伴的建议，尚未调用主持、未扣提问次数。请等人类在本机游戏页面逐次确认，再查询主持结果。")
        }) }
        session.pending?.let { put("pending", buildJsonObject {
            put("action", it.action.name); put("player", it.player.name); put("state", it.state.name)
            put("text", it.text); put("note", "调用结果未确认；不会自动重试。请在人类游戏页面核对后决定。")
        }) }
        if (session.revealed) put("soup_bottom", puzzle.solution)
    }
}
