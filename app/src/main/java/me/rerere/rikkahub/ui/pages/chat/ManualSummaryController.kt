package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.compaction.*
import me.rerere.rikkahub.data.model.OrbisManualContextPreview
import me.rerere.rikkahub.data.model.manualContextFingerprint
import me.rerere.rikkahub.service.ChatService
import java.io.File
import kotlin.uuid.Uuid

/** VM-owned work survives sheet dismissal/configuration changes. Durable checkpoints survive restart. */
internal class ManualSummaryController(context: Context, private val scope: CoroutineScope,
    private val id: Uuid,
    private val source: () -> me.rerere.rikkahub.data.model.Conversation,
    private val previewSource: suspend (Int) -> OrbisManualContextPreview,
    private val generatorFactory: suspend () -> ManualSummaryGenerator,
    private val previewDraft: suspend (ManualSummaryDraft) -> OrbisManualContextPreview,
    private val commit: suspend (OrbisManualContextPreview) -> Uuid) {
    constructor(context: Context, scope: CoroutineScope, service: ChatService, id: Uuid) : this(context, scope, id,
        { service.getDisplayedConversationFlow(id).value }, { service.previewManualContext(id, it, "") },
        { service.manualSummaryGenerator(id) }, { service.previewGeneratedManualContext(id, it) },
        { service.applyManualContext(id, it) })
    private val store = ManualSummaryStore(File(context.noBackupFilesDir, "manual-summary-drafts"))
    var draft by mutableStateOf<ManualSummaryDraft?>(null); private set
    var preview by mutableStateOf<OrbisManualContextPreview?>(null); private set
    var busy by mutableStateOf(false); private set
    var generating by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var archiveId by mutableStateOf<Uuid?>(null); private set
    private var job: Job? = null
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { error = "已暂停，已保存的摘要保留；在途请求可能已计费。"; throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "未完成，已保存摘要及原文保留。" }
            finally { busy = false; generating = false; job = null }
        }
    }
    fun open() = action {
        preview = null; archiveId = null
        draft = withContext(Dispatchers.IO) { store.read(id.toString()) }
    }
    fun plan(archiveThrough: Int, requestedParts: Int?, prompt: String) = action {
        check(draft == null) { "请先处理或明确放弃现有草稿。" }
        require(prompt.length <= 8_000) { "摘要要求最多 8000 字符。" }
        val baseline = previewSource(archiveThrough)
        val source = source()
        withContext(Dispatchers.Default) {
            require(baseline.expectedFingerprint == manualContextFingerprint(source)) { "窗口已变化，请重新规划。" }
        }
        val generator = generatorFactory()
        val next = withContext(Dispatchers.Default) {
            val text = manualSummarySource(source, archiveThrough)
            ManualSummaryDraft(id.toString(), source.assistantId.toString(), baseline.expectedFingerprint,
                archiveThrough, prompt.trim(), splitManualSummary(text, requestedParts ?: recommendedManualSummaryParts(text)),
                modelId = generator.modelId, modelName = generator.modelName).validate()
        }
        withContext(Dispatchers.IO) { store.write(next) }
        draft = next; preview = null
    }
    fun generate(concurrency: Int, outputTokens: Int) = action {
        val initial = checkNotNull(draft)
        val snapshot = source()
        withContext(Dispatchers.Default) { initial.requireSource(snapshot) }
        require(outputTokens in listOf(4096, 8192, 16384))
        val generator = generatorFactory()
        require(initial.modelId == generator.modelId) { "当前模型已切换，请换回 ${initial.modelName} 后继续。" }
        generating = true; preview = null
        withContext(Dispatchers.IO) {
            runManualSummary(initial, concurrency, { snapshot, index -> generator.request(snapshot, index, outputTokens) }) { saved ->
                store.write(saved)
                withContext(Dispatchers.Main.immediate) { draft = saved }
            }
        }
    }
    fun pause() { if (generating) job?.cancel() }
    fun edit(index: Int, text: String) = action {
        val current = checkNotNull(draft)
        require(index in current.summaries.indices && current.summaries[index] != null)
        val next = current.copy(summaries = current.summaries.toMutableList().also { it[index] = text.trim() }).validate()
        withContext(Dispatchers.IO) { store.write(next) }
        draft = next; preview = null
    }
    fun preview() = action { preview = previewDraft(checkNotNull(draft)) }
    fun apply() = action {
        val approved = checkNotNull(preview)
        archiveId = commit(approved)
        preview = null
        // A cleanup failure must not turn an already committed archive into an apparent failed commit.
        try { withContext(Dispatchers.IO) { store.clear(id.toString()) } }
        catch (_: Exception) { error = "整理已完成；旧草稿清理失败，勿重复应用。" }
    }
    fun discard() = action {
        withContext(Dispatchers.IO) { store.clear(id.toString()) }
        draft = null; preview = null; archiveId = null
    }
}
