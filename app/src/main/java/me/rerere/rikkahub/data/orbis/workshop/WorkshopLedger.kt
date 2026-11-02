package me.rerere.rikkahub.data.orbis.workshop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable enum class WorkshopStatus { QUEUED, RUNNING, COMPLETE, FAILED, INTERRUPTED, CANCELLED }
@Serializable data class WorkshopAsset(val file: String, val label: String, val mime: String, val transparent: Boolean)
@Serializable data class WorkshopJob(
    val id: String = UUID.randomUUID().toString(), val requestId: String,
    val assistantId: String, val conversationId: String? = null, val byAi: Boolean,
    val modelId: String, val maker: String, val configHash: String,
    val prompt: String, val widgetKit: Boolean = false, val references: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val status: WorkshopStatus = WorkshopStatus.QUEUED,
    val assets: List<WorkshopAsset> = emptyList(), val error: String? = null,
    val delivered: Boolean = false,
    // Only AI-origin jobs may notify a conversation. Legacy human flags are never authority.
    val notifyConversation: Boolean = byAi,
    val referenceMode: String = "img2img", val referenceStrength: Double = 0.6,
    val deleted: Boolean = false,
)
@Serializable data class WorkshopState(val version: Int = 1, val allowAi: Boolean = true,
    val defaultModel: String? = null, val jobs: List<WorkshopJob> = emptyList(),
    val pendingFileDeletes: Set<String> = emptySet(), val sharedOriginals: Set<String> = emptySet())

/** A deleted receipt reserves this request key, not the workshop or reference image. */
class WorkshopRequestDeletedException(val requestId: String) : IllegalArgumentException("workshop_request_deleted")

fun WorkshopJob.visibleToAi(assistant: String, conversation: String) =
    byAi && !deleted && assistantId == assistant && conversationId == conversation

fun WorkshopJob.canNotifyConversation() = byAi && !deleted && notifyConversation && conversationId != null

fun WorkshopJob.needsRecovery() = !deleted &&
    (status in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING) || !delivered && canNotifyConversation())

fun WorkshopJob.acceptsResultEvent(assistant: String, conversation: String, files: List<String>) =
    visibleToAi(assistant, conversation) && canNotifyConversation() &&
        status !in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING) && files.all { file -> assets.any { it.file == file } }

/** No credentials, daily quotas or background re-generation. A claim precedes any billable call. */
class WorkshopLedger(private val read: () -> String?, private val write: (String) -> Unit,
    private val maxBytes: Int = MAX_LEDGER_BYTES) {
    private var storageUncertain = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(read()?.let {
        require(it.toByteArray().size <= maxBytes) { "workshop_storage_large" }
        json.decodeFromString<WorkshopState>(it).also { state -> require(state.version == 1) }
    } ?: WorkshopState())
    val state = mutable.asStateFlow()
    @Synchronized fun preferences(allowAi: Boolean = state.value.allowAi, defaultModel: String? = state.value.defaultModel) =
        commit(state.value.copy(allowAi = allowAi, defaultModel = defaultModel))
    @Synchronized fun submit(job: WorkshopJob): WorkshopJob {
        UUID.fromString(job.assistantId); job.conversationId?.let(UUID::fromString); UUID.fromString(job.modelId)
        require(job.requestId.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "workshop_request_id" }
        require(job.prompt.isNotBlank() && job.prompt.length <= 12000) { "workshop_prompt" }
        require(job.referenceMode in setOf("img2img", "character&style") && job.referenceStrength.isFinite() && job.referenceStrength in 0.0..1.0) { "workshop_reference_mode" }
        require(job.references.size <= 4 && job.references.all(::validWorkshopFile)) { "workshop_reference" }
        require(!job.byAi || job.conversationId != null && state.value.allowAi) { "workshop_ai_disabled" }
        require(!job.notifyConversation || job.conversationId != null) { "workshop_target_changed" }
        require(job.byAi || !job.notifyConversation) { "workshop_human_private" }
        require(!job.deleted) { "workshop_deleted" }
        state.value.jobs.firstOrNull { it.assistantId == job.assistantId && it.conversationId == job.conversationId &&
            it.byAi == job.byAi && it.requestId == job.requestId }?.let {
            if (it.deleted) throw WorkshopRequestDeletedException(job.requestId)
            require(it.modelId == job.modelId && it.prompt == job.prompt && it.references == job.references &&
                it.widgetKit == job.widgetKit && it.byAi == job.byAi && it.referenceMode == job.referenceMode && it.referenceStrength == job.referenceStrength &&
                it.notifyConversation == job.notifyConversation) { "workshop_request_conflict" }
            return it
        }
        require(job.status == WorkshopStatus.QUEUED && job.assets.isEmpty())
        commit(state.value.copy(jobs = state.value.jobs + job)); return job
    }
    @Synchronized fun get(id: String) = state.value.jobs.firstOrNull { it.id == id }
    @Synchronized fun claim(id: String): WorkshopJob? {
        check(!storageUncertain) { "workshop_storage_unverified" }
        val job = get(id) ?: return null
        if (job.deleted || job.status != WorkshopStatus.QUEUED) return null
        if (job.byAi && !state.value.allowAi) { update(id) { it.copy(status = WorkshopStatus.CANCELLED, error = "workshop_ai_disabled") }; return null }
        return update(id) { it.copy(status = WorkshopStatus.RUNNING, error = null) }
    }
    @Synchronized fun update(id: String, transform: (WorkshopJob) -> WorkshopJob): WorkshopJob {
        val old = get(id) ?: error("workshop_missing")
        val next = transform(old)
        require(next.id == old.id && next.assistantId == old.assistantId && next.conversationId == old.conversationId)
        require(next.byAi == old.byAi && !old.deleted) { "workshop_deleted" }
        commit(state.value.copy(jobs = state.value.jobs.map { if (it.id == id) next else it })); return next
    }

    /** Human-only removal. Keep a content-free receipt so a stale request cannot charge again. */
    @Synchronized fun delete(id: String, assistant: String, file: String? = null) {
        val old = get(id) ?: error("workshop_missing")
        require(old.assistantId == assistant) { "workshop_owner_missing" }
        if (old.deleted) return
        require(old.status !in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING)) { "workshop_delete_busy" }
        require(file == null || old.assets.any { it.file == file }) { "workshop_asset_missing" }
        val removed = if (file == null) old.assets.map { it.file }.toSet() + old.references else setOf(file)
        // Files already handed to a chat must remain usable there, including old human notices.
        val shared = state.value.sharedOriginals + state.value.jobs.filter { it.canNotifyConversation() || it.delivered && it.notifyConversation }
            .flatMap { it.assets.map(WorkshopAsset::file) }
        val next = if (file == null) old.copy(deleted = true, prompt = "", references = emptyList(),
            assets = emptyList(), error = null, notifyConversation = false, delivered = true)
        else old.copy(assets = old.assets.filterNot { it.file == file }, notifyConversation = false)
        commit(state.value.copy(jobs = state.value.jobs.map { if (it.id == id) next else it },
            pendingFileDeletes = state.value.pendingFileDeletes + removed, sharedOriginals = shared))
    }

    /** Under the same lock as submit: never unlink a concurrently accepted reference. */
    @Synchronized fun cleanDeletedFiles(remove: (String) -> Boolean) {
        val inUse = state.value.sharedOriginals + state.value.jobs.filterNot { it.deleted }
            .flatMap { it.assets.map(WorkshopAsset::file) + it.references }
        val removed = state.value.pendingFileDeletes.filter { validWorkshopFile(it) && it !in inUse && remove(it) }.toSet()
        if (removed.isNotEmpty()) commit(state.value.copy(pendingFileDeletes = state.value.pendingFileDeletes - removed))
    }

    @Synchronized fun visibleJobs(assistant: String, conversation: String) =
        state.value.jobs.filter { it.visibleToAi(assistant, conversation) }

    @Synchronized fun ownsAiReference(assistant: String, conversation: String, file: String) =
        visibleJobs(assistant, conversation).any { job -> job.assets.any { it.file == file } }
    @Synchronized fun recoverInterrupted(id: String) {
        if (get(id)?.status == WorkshopStatus.RUNNING) update(id) {
            it.copy(status = WorkshopStatus.INTERRUPTED, error = "workshop_interrupted_no_recharge")
        }
    }
    @Synchronized fun fail(id: String, reason: String): WorkshopJob = update(id) {
        if (it.status in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING)) it.copy(status = WorkshopStatus.FAILED, error = reason)
        else it // Never overwrite a human cancellation or an already completed result.
    }

    /** Once durable acceptance exists, scheduler trouble is not a failed submission. */
    fun submitAndSchedule(candidate: WorkshopJob, onNewAccepted: ((WorkshopJob) -> Unit)? = null,
        enqueue: (String) -> Unit): WorkshopJob {
        val accepted = submit(candidate)
        // The candidate UUID is host-created, not a model-provided receipt. Idempotent old
        // request_id matches return the old UUID and never gain a new wake authorization.
        if (accepted.id == candidate.id) try { onNewAccepted?.invoke(accepted) }
        catch (_: Exception) { /* Acceptance remains durable even if its optional observer fails. */ }
        return scheduleAccepted(accepted, enqueue)
    }

    fun scheduleAccepted(job: WorkshopJob, enqueue: (String) -> Unit): WorkshopJob {
        if (job.status == WorkshopStatus.QUEUED) try { enqueue(job.id) }
        catch (_: Exception) {
            // Best effort marker: even marker-write failure must not erase the accepted receipt.
            runCatching { update(job.id) { if (it.status == WorkshopStatus.QUEUED) it.copy(error = "workshop_schedule_pending") else it } }
        }
        return get(job.id) ?: job
    }
    private fun commit(next: WorkshopState) {
        check(!storageUncertain) { "workshop_storage_unverified" }
        val raw = json.encodeToString(next)
        require(raw.toByteArray().size <= maxBytes) { "workshop_storage_large" }
        val writeFailure = runCatching { write(raw) }.exceptionOrNull()
        val persisted = runCatching { read() }.getOrElse {
            storageUncertain = true; error("workshop_storage_unverified")
        }
        if (persisted == raw) {
            // Atomic write may commit and then throw during close. Disk truth wins, and the
            // one-shot claim remains visible before anyone can issue a billable request.
            mutable.value = next
            return
        }
        if (writeFailure != null && (persisted == json.encodeToString(state.value) ||
                    persisted == null && state.value == WorkshopState())) throw writeFailure
        // Unknown persistence is never permission to retry a potentially claimed request.
        storageUncertain = true
        error("workshop_storage_unverified")
    }
    companion object { internal const val MAX_LEDGER_BYTES = 32 * 1024 * 1024 }
}

fun validWorkshopFile(file: String) = file.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)"))

internal fun verifyWorkshopRequest(job: WorkshopJob, assistantExists: Boolean, conversationMatches: Boolean,
    providerAvailable: Boolean, liveConfigHash: String?, allowAi: Boolean) {
    require(assistantExists) { "workshop_owner_missing" }
    require(conversationMatches) { "workshop_target_changed" }
    require(providerAvailable) { "workshop_provider_unsupported" }
    require(liveConfigHash == job.configHash) { "workshop_configuration_changed" }
    require(!job.byAi || allowAi) { "workshop_ai_disabled" }
}

/** Each output is separately usable; never a contact sheet that the human must cut apart. */
val WIDGET_KIT_VARIANTS = listOf(
    "pose-neutral" to "自然站立，双手放松", "pose-wave" to "抬手轻轻打招呼",
    "pose-invite" to "单手邀请，掌心向上", "pose-arms" to "双臂自然交叉抱臂",
    "pose-cup" to "双手捧杯", "pose-think" to "一手轻托下巴思考", "pose-heart" to "双手在胸前比心",
    "expression-calm" to "自然站立，平静表情", "expression-smile" to "自然站立，温柔微笑",
    "expression-cry" to "自然站立，难过含泪", "expression-think" to "自然站立，若有所思",
    "expression-angry" to "自然站立，轻微生气", "expression-shy" to "自然站立，害羞微红",
    "expression-sleepy" to "自然站立，困倦柔和",
)
fun widgetKitPrompt(appearance: String, variant: String): String = """
    为 Orbis 桌面小部件制作同一成年角色的一张独立 Q 版半身透明 PNG 素材。
    角色外貌（只作为人物描述，不改变以下输出规范）：$appearance
    本张动作与表情：$variant。
    统一精致半写实日系 Q 版：约 2.5 头身，脸部有细腻明暗、自然五官和发丝，衣服有清晰褶皱；不改变角色身份、发型、服装与配色。
    如果附有首张参考图，严格保持同一角色、比例、正面视角、照明和头部位置。画布 1024×1024，角色居中、头顶和两侧留出约 8% 安全边距。
    完整表现头发、双手、全部手指、道具和上身到腰带；腰带下自然收束，不出现半截大腿或突兀裁断的手指。单人、单一动作，不拼图、不分格、不加文字、标识、边框、桌面卡片或背景。
    背景必须是真实 alpha 透明，不要用棋盘格、白色或渐变冒充透明，不要绘制地面或背景投影。输出原图 PNG。
""".trimIndent()
