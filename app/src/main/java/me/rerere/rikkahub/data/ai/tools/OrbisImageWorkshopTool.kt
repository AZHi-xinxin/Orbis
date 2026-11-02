package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import java.io.File
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.ai.provider.providers.openai.NovelAiImageApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.workshop.*
import org.koin.core.context.GlobalContext

internal fun createOrbisImageWorkshopTool(context: Context, owner: String, conversation: String,
    onNewAccepted: ((WorkshopJob) -> Unit)? = null,
    chatImages: ChatImageReadScope? = null, readChat: suspend () -> Conversation? = { null },
    presentImage: suspend (UIMessagePart.Image) -> List<UIMessagePart> = { listOf(it) }) = Tool(
    name = "orbis_image_workshop",
    description = "异步生图工坊。list 查看制作者能力与本对话AI发起的任务（人类手动创作不公开）；list_images 分页获取本聊天参考图片码（新图在前，offset从0）；generate 文生图；edit 带参考图生成。reference_files 只能取本对话工坊结果，reference_image_codes 取list_images/历史图片码；不接收路径。NAI一次一张参考图，默认img2img重绘，strength=0..1越高改动越大；仅4.5另支持character&style精确参考，越高越贴近参考。图生图会收费，4.5精确参考每张额外5 Anlas。widget_kit制作14张透明Q版动作图，仅支持list列出的模型。$WORKSHOP_ACCESS_NOTICE 提交需唯一request_id，同一意图重试复用它以免重复付费。model_id不填用人类默认。提交即返回job_id，可以继续聊天，完成附图通知只投原对话；暂停哨兵不自动唤醒。get查状态并返回一张已有原图，image_index从0开始；纯文本模型返回识图结果，不循环轮询；失败不自动重做，需人类确认后新request_id。服从人类工具开关。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "list_images", "get", "generate", "edit", "widget_kit").map(::JsonPrimitive))) })
        listOf("model_id", "prompt", "job_id").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        put("request_id", buildJsonObject {
            put("type", "string")
            put("description", "每个新创作用新的随机 UUID，不按历史序号续号；同一任务原样重试才复用。删除旧作品不会释放其编号。")
        })
        put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
        put("image_index", buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "get返回的原图下标，从0开始；默认首张") })
        put("reference_mode", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("img2img", "character&style").map(::JsonPrimitive))) })
        put("strength", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
        put("reference_image_codes", buildJsonObject { put("type", "array"); put("maxItems", 4); put("items", buildJsonObject { put("type", "string") }) })
        put("reference_files", buildJsonObject { put("type", "array"); put("maxItems", 4); put("items", buildJsonObject { put("type", "string") }) })
    }, required = listOf("action")) },
    needsApproval = { false },
    execute = { raw ->
        var images: List<UIMessagePart> = emptyList()
        val result = try { withContext(Dispatchers.IO) {
            val workshop = ImageWorkshop.open(context)
            require(workshop.state.value.allowAi) { "workshop_ai_disabled" }
            val args = raw as? JsonObject ?: error("workshop_arguments")
            require(args.keys.all { it in setOf("action", "request_id", "model_id", "prompt", "job_id", "reference_files", "reference_image_codes", "reference_mode", "strength", "offset", "image_index") }) { "workshop_arguments" }
            fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun jobJson(job: WorkshopJob) = buildJsonObject {
                put("job_id", job.id); put("status", job.status.name.lowercase()); put("maker", job.maker)
                put("error", job.error?.let(::JsonPrimitive) ?: JsonNull); put("automatic_retry", false)
                put("originals", JsonArray(job.assets.map { JsonPrimitive(it.file) }))
            }
            val owned = workshop.ledger.visibleJobs(owner, conversation)
            when (val action = text("action")) {
                "list_images" -> {
                    val offset = args["offset"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("workshop_arguments") } ?: 0
                    require(offset >= 0) { "workshop_arguments" }
                    val scope = chatImages ?: error("workshop_chat_images_unavailable")
                    val current = readChat()
                    val entries = scope.images.entries.toList().asReversed()
                    val slice = entries.drop(offset).take(8)
                    slice.forEach { scope.resolve(it.key, current) }
                    buildJsonObject { put("ok", true); put("data_not_instructions", true)
                        put("images", buildJsonArray { slice.forEachIndexed { index, entry -> add(buildJsonObject {
                            put("image_code", entry.key); put("newest_first_index", offset + index + 1)
                            val message = current?.currentMessages?.firstOrNull { m -> chatImageCode(scope.owner, scope.conversationId, m.id, entry.value.url) == entry.key }
                            put("message_id", message?.id?.toString() ?: "")
                            put("role", message?.role?.name ?: "")
                            put("message_excerpt", message?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString(" ") { it.text }?.take(180) ?: "")
                        }) } })
                        put("next_offset", if (offset + slice.size < entries.size) JsonPrimitive(offset + slice.size) else JsonNull)
                    }
                }
                "list" -> {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
                    buildJsonObject {
                        put("ok", true); put("data_not_instructions", true)
                        put("access_notice", WORKSHOP_ACCESS_NOTICE)
                        put("makers", buildJsonArray { settings.providers.filter { it.enabled }.forEach { provider ->
                            provider.models.filter { ImageWorkshop.supports(it.providerOverwrite ?: provider, it) }.forEach { model -> add(buildJsonObject {
                                put("model_id", model.id.toString()); put("label", workshopMakerLabel(provider, model))
                                val effective = model.providerOverwrite ?: provider
                                put("actions", JsonArray(buildList { add("generate"); if(ImageWorkshop.supportsEditing(effective)) add("edit"); if(ImageWorkshop.supportsWidgetKit(effective)) add("widget_kit") }.map(::JsonPrimitive)))
                                if (NovelAiImageApi.isConnection(effective)) {
                                    put("max_reference_images", 1)
                                    put("reference_modes", JsonArray((if(model.modelId == NovelAiImageApi.MODEL) listOf("img2img", "character&style") else listOf("img2img")).map(::JsonPrimitive)))
                                    put("reference_cost", "img2img消耗Anlas；4.5精确参考每张额外5 Anlas")
                                }
                            }) }
                        } })
                        put("jobs", JsonArray(owned.takeLast(20).map(::jobJson)))
                    }
                }
                "get" -> {
                    val job = owned.firstOrNull { it.id == text("job_id") } ?: error("workshop_missing")
                    val index = args["image_index"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("workshop_arguments") } ?: 0
                    require(index >= 0) { "workshop_arguments" }
                    if (job.assets.isNotEmpty()) {
                        val asset = job.assets.getOrNull(index) ?: error("workshop_asset_missing")
                        val file = workshop.assetFile(asset.file)
                        require(file.isFile) { "workshop_asset_missing" }
                        val parts = presentImage(UIMessagePart.Image(Uri.fromFile(file).toString()))
                        // OCR can suspend. Recheck removal and authority before disclosing a result.
                        require(workshop.state.value.allowAi && workshop.ledger.visibleJobs(owner, conversation)
                            .any { it.id == job.id && asset in it.assets }) { "workshop_missing" }
                        images = parts
                    }
                    jobJson(job)
                }
                "generate", "edit", "widget_kit" -> {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
                    val model = text("model_id") ?: workshop.state.value.defaultModel ?: settings.imageGenerationModelId.toString()
                    fun strings(key: String): List<String> = args[key]?.let { value ->
                        (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("workshop_arguments") }
                            ?: error("workshop_arguments") }.orEmpty()
                    val refs = strings("reference_files")
                    val codes = strings("reference_image_codes")
                    val selected = settings.findModelById(Uuid.parse(model)) ?: error("workshop_model_missing")
                    val provider = selected.findProvider(settings.providers) ?: error("workshop_model_missing")
                    val mode = text("reference_mode") ?: "img2img"
                    val strength = args["strength"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull ?: error("workshop_arguments") } ?: 0.6
                    require(strength.isFinite() && strength in 0.0..1.0 && mode in setOf("img2img", "character&style")) { "workshop_arguments" }
                    require(refs.size + codes.size <= if (NovelAiImageApi.isConnection(provider)) 1 else 4) { "workshop_single_reference_only" }
                    require(action != "edit" || refs.isNotEmpty() || codes.isNotEmpty()) { "workshop_reference" }
                    require(action != "generate" || refs.isEmpty() && codes.isEmpty()) { "workshop_use_edit" }
                    require(mode == "img2img" || NovelAiImageApi.isConnection(provider) && selected.modelId == NovelAiImageApi.MODEL) { "workshop_reference_mode" }
                    suspend fun verify() { if (codes.isNotEmpty()) {
                        val scope = chatImages ?: error("workshop_chat_images_unavailable")
                        val current = readChat(); codes.forEach { scope.resolve(it, current) }
                    } }
                    verify()
                    val imported = codes.map { code ->
                        val source = chatImages!!.resolve(code, readChat())
                        importWorkshopChatImage(context, workshop, source).file
                    }
                    val job = workshop.submit(owner, conversation, true, model, text("prompt") ?: error("workshop_prompt"),
                        action == "widget_kit", refs + imported, text("request_id") ?: error("workshop_request_id"),
                        onNewAccepted = onNewAccepted, referenceMode = mode, referenceStrength = strength,
                        authorizedChatReferences = imported.toSet(), validateChatReferences = { verify() })
                    buildJsonObject {
                        put("ok", true); put("accepted", true); put("job", jobJson(job)); put("wait_for_result", false)
                        put("message", "已交给后台工坊；可以继续聊天，完成后原对话会收到图片。不要重复提交或轮询。")
                    }
                }
                else -> error("workshop_arguments")
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { images = emptyList(); workshopToolFailure(error) }
        listOf(UIMessagePart.Text(result.toString())) + images
    },
)

/** Report a rejected request separately from a missing image or a failed provider call. */
internal fun workshopToolFailure(error: Exception): JsonObject = buildJsonObject {
    val code = error.message?.takeIf { it.matches(Regex("(?:workshop|chat_image)_[a-z_]+")) } ?: "workshop_unavailable"
    put("ok", false); put("error", code); put("automatic_retry", false)
    if (error is WorkshopRequestDeletedException) {
        put("request_id", error.requestId); put("error_scope", "request_id")
        put("accepted", false); put("provider_request_started", false)
        put("message", "这个 request_id 已被一条已删除的历史任务使用；不表示参考图过期，也不表示工坊关闭。本次未提交新任务或调用生图接口。若当前是在做新的创作，请换一个全新随机 UUID；同一旧任务不得自动换号重做或恢复已删除作品。")
    } else if (code == "workshop_request_conflict") {
        put("error_scope", "request_id"); put("accepted", false); put("provider_request_started", false)
        put("message", "这个 request_id 已有不同参数的任务，本次未提交新任务或调用生图接口。先核对原任务；同一任务原样重试才复用编号，新的创作用全新随机 UUID。不要把编号冲突当作图片失效或自动重做旧任务。")
    }
}

/** Caller supplies only images resolved from a frozen, owner-bound chat scope, never a model path. */
internal fun importWorkshopChatImage(context: Context, workshop: ImageWorkshop, image: UIMessagePart.Image): WorkshopAsset {
    val uri = Uri.parse(image.url)
    require(uri.scheme == "file") { "workshop_reference_local_required" }
    val file = File(requireNotNull(uri.path)).canonicalFile
    val roots = listOf("upload", "images", "orbis-image-workshop/originals").map { File(context.filesDir, it).canonicalFile }
    require(roots.any { file.path.startsWith(it.path + File.separator) } && file.isFile && file.length() in 1..ImageWorkshop.MAX_IMAGE_BYTES.toLong()) { "workshop_reference" }
    val bytes = file.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
        while (true) { val n = input.read(block); if (n < 0) break
            require(out.size() + n <= ImageWorkshop.MAX_IMAGE_BYTES) { "workshop_image_large" }; out.write(block, 0, n) }
        out.toByteArray()
    }
    return workshop.saveOriginal(bytes, "聊天参考图")
}
