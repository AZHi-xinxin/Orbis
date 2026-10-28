package me.rerere.rikkahub.data.orbis.body

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.isOrbisVoiceNote
import me.rerere.rikkahub.data.model.voiceNoteTranscript

data class BodyStatusOptions(val enabled: Boolean, val keywordsEnabled: Boolean)

/** Bridges accepted human input to model input without blocking acceptance or replaying events.
 * Only short reports live in RAM. Existing atomic body receipts recover the same wake on restart.
 */
class BodyStatusRuntime(
    private val scope: CoroutineScope,
    private val options: (String) -> BodyStatusOptions?,
    private val open: (String) -> BodyRepository,
    private val waitMillis: Long = 1500,
) {
    private data class Key(val owner: String, val conversation: String, val message: String)
    private val reports = LinkedHashMap<Key, CompletableDeferred<String?>>()
    private val ownerTail = mutableMapOf<String, CompletableDeferred<String?>>()

    private fun enabled(owner: String) = options(owner)?.enabled == true

    /** Register synchronously BEFORE launching generation. Disk work remains off the UI thread. */
    fun recordHuman(owner: String, conversation: String, message: UIMessage) {
        if (!message.isBodyHuman() || !enabled(owner)) return
        val key = Key(owner, conversation, message.id.toString())
        val result = CompletableDeferred<String?>()
        val previous: CompletableDeferred<String?>?
        synchronized(reports) {
            if (reports.containsKey(key)) return
            previous = ownerTail.put(owner, result)
            remember(key, result)
        }
        val job = scope.launch(Dispatchers.IO) {
            try {
                previous?.await() // Keep accepted human inputs in order for this owner.
                val current = options(owner)
                if (current?.enabled != true) return@launch
                val repo = open(owner)
                result.complete(synchronized(repo) {
                    repo.human(conversation, key.message, message.bodyHumanText(), current.keywordsEnabled)
                    // A duplicate from a fork must not borrow the original window's state.
                    if (repo.receiptSeq("human:${key.message}") != null) repo.statusForHuman(conversation, key.message)
                    else repo.viewSnapshot().let { state ->
                        // Paused manual definitions do not create human receipts. They may still
                        // report their frozen values now, but never backfill an old input later.
                        state.versions.lastOrNull()?.definition?.takeIf { !state.running && it.triggerMode == "manual" }
                            ?.let { formatBodyStatus(it, BodyRules.values(state.snapshot, conversation, it)) }
                    }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional simulator failures cannot fail a chat. */ }
            finally {
                result.complete(null)
                synchronized(reports) { if (ownerTail[owner] === result) ownerTail.remove(owner) }
            }
        }
        job.invokeOnCompletion { result.complete(null) }
    }

    /** Never scan old text here: retries, approvals and process recovery are read-only. */
    suspend fun begin(owner: String, conversation: String, messages: List<UIMessage>): BodyStatusTurn? {
        if (!enabled(owner)) return null
        val human = messages.lastOrNull { it.role == MessageRole.USER }?.takeIf { it.isBodyHuman() } ?: return null
        val key = Key(owner, conversation, human.id.toString())
        val result = synchronized(reports) {
            reports[key] ?: CompletableDeferred<String?>().also { deferred ->
                remember(key, deferred)
                val job = scope.launch(Dispatchers.IO) {
                    try {
                        if (enabled(owner)) deferred.complete(open(owner).statusForHuman(conversation, key.message))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Missing/corrupt state is not a fabricated status. */ }
                    finally { deferred.complete(null) }
                }
                job.invokeOnCompletion { deferred.complete(null) }
            }
        }
        val report = withTimeoutOrNull(waitMillis) { result.await() } ?: return null
        if (!enabled(owner)) return null
        return BodyStatusTurn(report) { enabled(owner) }
    }

    private fun remember(key: Key, result: CompletableDeferred<String?>) {
        reports[key] = result
        while (reports.size > 256) reports.remove(reports.keys.first())
    }
}

class BodyStatusTurn internal constructor(val report: String, private val enabled: () -> Boolean) {
    private var revoked = false
    internal fun project(messages: List<UIMessage>): List<UIMessage> {
        if (!enabled()) revoked = true
        return projectBodyStatus(messages, report.takeUnless { revoked })
    }
}

private fun UIMessage.isBodyHuman(): Boolean = role == MessageRole.USER && !isSynthetic &&
    // recordHuman is called only for newly accepted input; begin recovers an exact receipt.
    // The optional model-facing timestamp is a display preference, not proof of human input.
    orbisEvent == null && getTools().isEmpty() &&
    (orbisVoiceCallKind == null || orbisVoiceCallKind == "turn")

/** Read accepted content, not provider projections: their labels/acoustics are not human words.
 * A voice note keeps the confirmed transcript in its audio part. Never re-transcribe the file,
 * use the discarded original transcript, or scan assistant tool output as human input.
 */
private fun UIMessage.bodyHumanText(): String = parts.mapNotNull { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Audio -> part.takeIf { it.isOrbisVoiceNote() }?.voiceNoteTranscript()
        else -> null
    }
}.joinToString("\n").take(32000)

/** Exact message receipt, exact conversation and historical rule version. No stale window fallback. */
internal fun BodyRepository.statusForHuman(conversation: String, message: String): String? = synchronized(this) {
    val seq = receiptSeq("human:$message") ?: return@synchronized null
    val state = snapshot()
    val log = state.logs.find { it.seq == seq && it.conversation == conversation &&
        (it.event == "host_human" || it.event == "host_cooling") } ?: return@synchronized null
    val definition = state.versions.find { it.version == log.version }?.definition ?: return@synchronized null
    formatBodyStatus(definition, log.values)
}

internal fun formatBodyStatus(definition: BodyDefinition, values: Map<String, Double>): String? {
    if (definition.dimensions.isEmpty() || definition.dimensions.any { values[it.id]?.isFinite() != true }) return null
    fun number(value: Double) = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    // Labels are assistant-defined data, not prompt instructions. Escape delimiters and controls.
    fun label(value: String) = kotlinx.serialization.json.JsonPrimitive(value).toString()
    return "【当前身体状态：" + definition.dimensions.joinToString("；") {
        "${label(it.label)}：${number(values.getValue(it.id))}${if (it.isPrivate) "（私密）" else ""}"
    } + "】" + if (definition.dimensions.any { it.isPrivate }) "（供你读取自身状态；私密指标不代表已向人类公开。）" else ""
}
