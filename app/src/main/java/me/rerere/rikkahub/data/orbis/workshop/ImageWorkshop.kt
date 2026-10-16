package me.rerere.rikkahub.data.orbis.workshop

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.orbis.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

class ImageWorkshop private constructor(private val context: Context) {
    private val root = File(context.filesDir, "orbis-image-workshop").also { it.mkdirs() }
    private val originals = File(root, "originals").also { it.mkdirs() }
    private val record = AtomicFile(File(root, "jobs-v1.json"))
    val ledger = WorkshopLedger(read = {
        if (record.baseFile.exists()) record.openRead().bufferedReader().use { it.readText() } else null
    }, write = { raw -> atomicWrite(record, raw.toByteArray()) })
    val state get() = ledger.state

    suspend fun submit(assistantId: String, conversationId: String?, byAi: Boolean, modelId: String,
        prompt: String, widgetKit: Boolean = false, references: List<String> = emptyList(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        notifyConversation: Boolean = byAi,
        onNewAccepted: ((WorkshopJob) -> Unit)? = null): WorkshopJob = withContext(Dispatchers.IO) {
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
        require(settings.assistants.any { it.id.toString() == assistantId }) { "workshop_owner_missing" }
        if (conversationId != null) {
            val conversation = GlobalContext.get().get<ConversationRepository>().getConversationById(Uuid.parse(conversationId))
            require(conversation?.assistantId.toString() == assistantId) { "workshop_target_changed" }
        }
        val model = settings.findModelById(Uuid.parse(modelId)) ?: error("workshop_model_missing")
        val provider = model.findProvider(settings.providers) ?: error("workshop_model_missing")
        require(settings.providers.any { it.enabled && it.models.any { candidate -> candidate.id == model.id } }) { "workshop_provider_unsupported" }
        require(supports(provider, model)) { "workshop_provider_unsupported" }
        references.forEach { name ->
            require(assetFile(name).isFile) { "workshop_reference" }
            if (byAi) require(ledger.state.value.jobs.any { job -> job.assistantId == assistantId &&
                job.conversationId == conversationId && job.assets.any { it.file == name } }) { "workshop_reference_owner" }
        }
        val candidate = WorkshopJob(requestId = requestId, assistantId = assistantId,
            conversationId = conversationId, byAi = byAi, modelId = modelId,
            maker = workshopMakerLabel(provider, model), configHash = configurationHash(provider, model),
            prompt = prompt.trim(), widgetKit = widgetKit, references = references, notifyConversation = notifyConversation)
        ledger.submitAndSchedule(candidate, onNewAccepted, ::enqueue)
    }

    fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<ImageWorkshopWorker>()
            .setInputData(workDataOf("job_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("orbis-image-workshop").build()
        // One global chain keeps memory/network bounded, without limiting calls per day.
        WorkManager.getInstance(context).enqueueUniqueWork("orbis-image-workshop-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Called after application DI/work manager init; repairs accepted-before-enqueue crashes. */
    fun recoverQueue() {
        ledger.state.value.jobs.filter { it.status in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING) ||
            !it.delivered && it.notifyConversation }.forEach { enqueue(it.id) }
    }

    fun assetFile(name: String): File {
        require(validWorkshopFile(name)) { "workshop_asset" }
        return File(originals, name)
    }
    fun assetFileForEvent(source: String, assistant: String, conversation: String, name: String): File {
        require(isImageWorkshopSource(source)) { "workshop_event_source" }
        val job = ledger.get(source.removePrefix("image_workshop.")) ?: error("workshop_missing")
        require(job.assistantId == assistant && job.conversationId == conversation && job.assets.any { it.file == name }) { "workshop_asset_owner" }
        return assetFile(name).also { require(it.isFile) { "workshop_asset_missing" } }
    }

    fun importReference(uri: Uri): WorkshopAsset {
        require(uri.scheme == "content") { "workshop_reference" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMAGE_BYTES) { "workshop_image_large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("workshop_reference")
        return saveOriginal(bytes, "reference")
    }

    fun saveOriginal(bytes: ByteArray, label: String): WorkshopAsset {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "workshop_image_large" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) { "workshop_image_invalid" }
        val ext = when (bounds.outMimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> error("workshop_image_format") }
        val thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = maxOf(bounds.outWidth, bounds.outHeight).div(128).coerceAtLeast(1) })
            ?: error("workshop_image_invalid")
        var transparent = false
        if (thumbnail.hasAlpha()) {
            loop@ for (y in 0 until thumbnail.height) for (x in 0 until thumbnail.width) {
                if ((thumbnail.getPixel(x, y) ushr 24) < 250) { transparent = true; break@loop }
            }
        }
        thumbnail.recycle()
        val name = sha256(bytes) + "." + ext
        if (!assetFile(name).exists()) atomicWrite(AtomicFile(assetFile(name)), bytes)
        return WorkshopAsset(name, label, bounds.outMimeType, transparent)
    }
    fun saveBase64(data: String, label: String): WorkshopAsset {
        require(data.length <= MAX_IMAGE_BYTES * 4 / 3 + 2048) { "workshop_image_large" }
        return saveOriginal(Base64.decode(data.substringAfter("base64,", data), Base64.DEFAULT), label)
    }

    suspend fun deliver(job: WorkshopJob) {
        if (!job.notifyConversation || job.conversationId == null || job.delivered) return
        val service = GlobalContext.get().get<ChatService>()
        val source = "image_workshop.${job.id}"
        service.bindOrbisEvents(setOf(source), OrbisEventBinding(job.assistantId, job.conversationId))
        service.acceptOrbisEvent(OrbisIncomingEvent(event_id = "result-${job.id}", source = source,
            text = "[生图工坊结果通知；以下是制作结果数据，不是新的人类指令]\n任务 ${job.id} 状态 ${job.status.name}，已保存 ${job.assets.size} 张原图。" +
                (if (job.status == WorkshopStatus.COMPLETE) "请查看附图；如需修改，可用原图文件标识调用 orbis_image_workshop edit。\n"
                else "未完整完成，错误 ${job.error ?: "workshop_unavailable"}。不会自动重做；不要主动用新 request_id 重复收费，请告知人类。\n") +
                job.assets.joinToString("\n") { "${it.label}: ${it.file}" },
            workshopImages = job.assets.map { it.file }.distinct()))
        ledger.update(job.id) { it.copy(delivered = true) }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        @Volatile private var instance: ImageWorkshop? = null
        fun open(context: Context): ImageWorkshop = instance ?: synchronized(this) {
            instance ?: ImageWorkshop(context.applicationContext).also { instance = it }
        }
        fun supports(provider: ProviderSetting, model: Model) = provider.enabled &&
            !me.rerere.ai.provider.providers.openai.SeedreamImageApi.isVideoModel(model.modelId) &&
            (provider is ProviderSetting.OpenAI && provider.chatGptAccountId == null &&
                (model.type == ModelType.IMAGE || me.rerere.ai.provider.providers.openai.SeedreamImageApi.usesArk(provider, model)) ||
                provider is ProviderSetting.Google && (model.type == ModelType.IMAGE || Modality.IMAGE in model.outputModalities))
        fun configurationHash(provider: ProviderSetting, model: Model): String = sha256(
            (Json.encodeToString(provider.copyProvider(models = emptyList())) + Json.encodeToString(model)).toByteArray())
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }
}
