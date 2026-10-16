package me.rerere.rikkahub.data.orbis.workshop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class ImageWorkshopWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = mutex.withLock { withContext(Dispatchers.IO) {
        val id = inputData.getString("job_id") ?: return@withContext Result.failure()
        val workshop = ImageWorkshop.open(applicationContext)
        val existing = workshop.ledger.get(id) ?: return@withContext Result.success()
        if (existing.status !in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING)) {
            // A lost completion receipt can be re-delivered, but never re-generated.
            runCatching { workshop.deliver(existing) }
            return@withContext Result.success()
        }
        if (existing.status == WorkshopStatus.RUNNING) {
            workshop.ledger.recoverInterrupted(id)
            runCatching { workshop.ledger.get(id)?.let { workshop.deliver(it) } }
            return@withContext Result.success()
        }
        if (existing.status != WorkshopStatus.QUEUED) return@withContext Result.success()
        try {
            val koin = GlobalContext.get()
            val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
            require(settings.assistants.any { it.id.toString() == existing.assistantId }) { "workshop_owner_missing" }
            existing.conversationId?.let { conversation ->
                require(koin.get<ConversationRepository>().getConversationById(Uuid.parse(conversation))?.assistantId.toString() == existing.assistantId) { "workshop_target_changed" }
            }
            val model = settings.findModelById(Uuid.parse(existing.modelId)) ?: error("workshop_model_missing")
            val config = model.findProvider(settings.providers) ?: error("workshop_model_missing")
            require(ImageWorkshop.supports(config, model)) { "workshop_provider_unsupported" }
            require(settings.providers.any { it.enabled && it.models.any { candidate -> candidate.id == model.id } }) { "workshop_provider_unsupported" }
            require(ImageWorkshop.configurationHash(config, model) == existing.configHash) { "workshop_configuration_changed" }
            setForeground(foregroundInfo())
            val job = workshop.ledger.claim(id) ?: return@withContext Result.success()
            // A transport failure may occur after the provider accepted a paid request.
            // Keep the user's proxy/TLS configuration, but disable connection-failure replay.
            val imageClient = koin.get<okhttp3.OkHttpClient>().newBuilder()
                .retryOnConnectionFailure(false)
                .build()
            val provider = ProviderManager(imageClient, applicationContext).getProviderByType(config)
            val variants = if (job.widgetKit) WIDGET_KIT_VARIANTS else listOf("image" to job.prompt)
            for ((label, variant) in variants) {
                // Every image is a new billable call. Re-check changes made while earlier
                // images were rendering, not only the configuration captured at job start.
                val live = koin.get<SettingsStore>().settingsFlow.first { !it.init }
                val liveModel = live.findModelById(Uuid.parse(job.modelId))
                val liveProvider = liveModel?.findProvider(live.providers)
                val targetMatches = job.conversationId?.let { target ->
                    koin.get<ConversationRepository>().getConversationById(Uuid.parse(target))?.assistantId.toString() == job.assistantId
                } ?: true
                verifyWorkshopRequest(job,
                    assistantExists = live.assistants.any { it.id.toString() == job.assistantId },
                    conversationMatches = targetMatches,
                    providerAvailable = liveModel != null && liveProvider != null && ImageWorkshop.supports(liveProvider, liveModel) &&
                        live.providers.any { it.enabled && it.models.any { candidate -> candidate.id == liveModel.id } },
                    liveConfigHash = if (liveModel != null && liveProvider != null) ImageWorkshop.configurationHash(liveProvider, liveModel) else null,
                    allowAi = workshop.state.value.allowAi)
                val prompt = if (job.widgetKit) widgetKitPrompt(job.prompt, variant) else job.prompt
                val first = workshop.ledger.get(id)?.assets?.firstOrNull()?.file
                val refs = (listOfNotNull(first.takeIf { job.widgetKit }) + job.references).distinct().take(4)
                var received = false
                fun save(data: String) {
                    val asset = workshop.saveBase64(data, label)
                    workshop.ledger.update(id) {
                        require(it.assets.size < 16) { "workshop_result_limit" }
                        require((it.assets.map { image -> workshop.assetFile(image.file).length() }.sum() + workshop.assetFile(asset.file).length()) <= 64L * 1024 * 1024) { "workshop_image_large" }
                        it.copy(assets = it.assets + asset)
                    }
                    received = true
                }
                withTimeout(5 * 60_000L) {
                if (config is ProviderSetting.Google) {
                    val messages = listOf(UIMessage(role = MessageRole.USER, parts = buildList {
                        add(UIMessagePart.Text(prompt))
                        refs.forEach { add(UIMessagePart.Image(android.net.Uri.fromFile(workshop.assetFile(it)).toString())) }
                    }))
                    val result = provider.generateText(config, messages, TextGenerationParams(
                        model = model.copy(outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
                        customHeaders = model.customHeaders, customBody = model.customBodies,
                        maxAutomaticContinuations = 0))
                    result.message.parts.filterIsInstance<UIMessagePart.Image>().forEach { part ->
                        require(part.url.startsWith("data:image/")) { "workshop_image_format" }; save(part.url)
                    }
                } else {
                    // Supported GPT image models have a real alpha-output flag, not only prose.
                    val body = if (job.widgetKit && model.modelId.startsWith("gpt-image", ignoreCase = true))
                        model.customBodies.filterNot { it.key == "background" || it.key == "output_format" } +
                            listOf(CustomBody("background", JsonPrimitive("transparent")), CustomBody("output_format", JsonPrimitive("png")))
                    else model.customBodies
                    val flow = if (refs.isEmpty()) provider.generateImage(config, ImageGenerationParams(
                        model = model, prompt = prompt, numOfImages = 1, partialImages = 0,
                        customHeaders = model.customHeaders, customBody = body))
                    else provider.editImage(config, ImageEditParams(model = model, prompt = prompt,
                        images = refs.map { workshop.assetFile(it).absolutePath }, numOfImages = 1, partialImages = 0,
                        customHeaders = model.customHeaders, customBody = body))
                    flow.collect { if (!it.partial) save(it.data) }
                }
                }
                require(received) { "workshop_empty_result" }
            }
            val complete = workshop.ledger.update(id) { it.copy(status = WorkshopStatus.COMPLETE) }
            // A delivery problem must not change the finished image job into a retryable generation.
            runCatching { workshop.deliver(complete) }
            Result.success()
        } catch (timeout: TimeoutCancellationException) {
            val failed = workshop.ledger.fail(id, "workshop_timeout")
            runCatching { workshop.deliver(failed) }
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { workshop.ledger.recoverInterrupted(id) }
            throw cancelled
        } catch (error: Exception) {
            val code = error.message?.takeIf { it.matches(Regex("workshop_[a-z_]+")) } ?: "workshop_provider_failed"
            val failed = workshop.ledger.fail(id, code)
            runCatching { workshop.deliver(failed) }
            // No network retry: provider may already have billed for an uncertain response.
            Result.success()
        }
    } }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("orbis-image-workshop", "生图工坊", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "orbis-image-workshop")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("生图工坊正在制作")
            .setContentText("可以继续聊天，成图会保存在工坊。")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(8741, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(8741, notification)
    }
    companion object { private val mutex = Mutex() }
}
