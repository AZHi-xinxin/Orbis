package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part, ctx))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    private suspend fun performOcr(part: UIMessagePart.Image, ctx: TransformerContext): String {
        return try {
            // Use the request's settings snapshot, not a different live selection for every image.
            val settings = ctx.settings
            val model = settings.findModelById(settings.ocrModelId)
                ?: return "[图片未识别：请先在设置中选择图片识别模型。]"
            if (Modality.IMAGE !in model.inputModalities) {
                return "[图片未识别：所选图片识别模型未声明支持图片输入，请检查模型能力设置。]"
            }
            val providerSetting = model.findProvider(settings.providers)
                ?: return "[图片未识别：图片识别服务未配置。]"
            if (!providerSetting.enabled) return "[图片未识别：图片识别服务已停用。]"
            // Match the provider's Android URI decoding, including encoded names and spaces.
            val file = File(requireNotNull(Uri.parse(part.url).path) { "ocr_image_unavailable" })
            val coroutineContext = currentCoroutineContext()
            fun cacheKey() = OcrCacheIdentity.key(
                file = file,
                assistantScope = ctx.assistant.id.toString(),
                prompt = settings.ocrPrompt,
                modelConfiguration = Json.encodeToString(model),
                providerConfiguration = Json.encodeToString<ProviderSetting>(providerSetting.copyProvider(models = emptyList())),
                checkActive = { coroutineContext.ensureActive() },
            )
            val key = cacheKey()
            cache.get(key)?.let { cachedResult ->
                Log.i(TAG, "performOcr: Using matching content/configuration cache")
                return cachedResult
            }
            val provider = get<ProviderManager>().getProviderByType(providerSetting)
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            currentCoroutineContext().ensureActive()
            val content = OcrCacheIdentity.cacheableText(result.message.toText())
                ?: return "[图片未识别：识别服务未返回有效内容；本次未缓存，可重试。]"
            // Do not associate a response with bytes that changed while the provider was reading.
            if (cacheKey() != key) return "[图片在识别过程中发生变化，本次未缓存，请重新发送。]"
            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()

            // Cache the result
            cache.put(key, ocrResult)
            ocrResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // Provider errors can contain credentials, private image text and local paths.
            Log.w(TAG, "performOcr: Recognition failed (${error.javaClass.simpleName}); result not cached")
            "[图片识别失败，本次未缓存。请检查图片文件、识别模型配置及服务状态后重试；不要假定已看到图片。]"
        }
    }
}
