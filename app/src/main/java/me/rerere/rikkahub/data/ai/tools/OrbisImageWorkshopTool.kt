package me.rerere.rikkahub.data.ai.tools

import android.content.Context
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
    onNewAccepted: ((WorkshopJob) -> Unit)? = null) = Tool(
    name = "orbis_image_workshop",
    description = "异步生图工坊：list 查看可用制作者及本对话任务；generate 生成，edit 修改本对话成图，widget_kit 根据 prompt 中的外貌制作14张独立透明Q版动作/表情图。$WORKSHOP_ACCESS_NOTICE 提交需唯一 request_id；同一次意图重试务必复用它，不能换ID重复付费。model_id 可选择 list 中的制作者，不填用人类默认。edit 的 reference_files 只能使用本对话工坊结果的文件标识，不能传任意路径。提交成功即返回 job_id，不要等待或循环轮询，可以继续聊天，工坊完成会向此原对话投递附图通知；人类暂停哨兵时不会自动唤醒。get 用 job_id 查看状态；失败/中断不自动重做，需人类确认后才用新request_id再次收费。每日不限次数，但服从人类工具开关。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "generate", "edit", "widget_kit").map(::JsonPrimitive))) })
        listOf("request_id", "model_id", "prompt", "job_id").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        put("reference_files", buildJsonObject { put("type", "array"); put("maxItems", 4); put("items", buildJsonObject { put("type", "string") }) })
    }, required = listOf("action")) },
    needsApproval = { false },
    execute = { raw ->
        val result = try { withContext(Dispatchers.IO) {
            val workshop = ImageWorkshop.open(context)
            require(workshop.state.value.allowAi) { "workshop_ai_disabled" }
            val args = raw as? JsonObject ?: error("workshop_arguments")
            require(args.keys.all { it in setOf("action", "request_id", "model_id", "prompt", "job_id", "reference_files") }) { "workshop_arguments" }
            fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun jobJson(job: WorkshopJob) = buildJsonObject {
                put("job_id", job.id); put("status", job.status.name.lowercase()); put("maker", job.maker)
                put("error", job.error?.let(::JsonPrimitive) ?: JsonNull); put("automatic_retry", false)
                put("originals", JsonArray(job.assets.map { JsonPrimitive(it.file) }))
            }
            val owned = workshop.state.value.jobs.filter { it.assistantId == owner && it.conversationId == conversation }
            when (val action = text("action")) {
                "list" -> {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
                    buildJsonObject {
                        put("ok", true); put("data_not_instructions", true)
                        put("access_notice", WORKSHOP_ACCESS_NOTICE)
                        put("makers", buildJsonArray { settings.providers.filter { it.enabled }.forEach { provider ->
                            provider.models.filter { ImageWorkshop.supports(it.providerOverwrite ?: provider, it) }.forEach { model -> add(buildJsonObject {
                                put("model_id", model.id.toString()); put("label", workshopMakerLabel(provider, model))
                            }) }
                        } })
                        put("jobs", JsonArray(owned.takeLast(20).map(::jobJson)))
                    }
                }
                "get" -> jobJson(owned.firstOrNull { it.id == text("job_id") } ?: error("workshop_missing"))
                "generate", "edit", "widget_kit" -> {
                    val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
                    val model = text("model_id") ?: workshop.state.value.defaultModel ?: settings.imageGenerationModelId.toString()
                    val refs = (args["reference_files"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("workshop_arguments") }.orEmpty()
                    require(action != "edit" || refs.isNotEmpty()) { "workshop_reference" }
                    val job = workshop.submit(owner, conversation, true, model, text("prompt") ?: error("workshop_prompt"),
                        action == "widget_kit", refs, text("request_id") ?: error("workshop_request_id"),
                        onNewAccepted = onNewAccepted)
                    buildJsonObject {
                        put("ok", true); put("accepted", true); put("job", jobJson(job)); put("wait_for_result", false)
                        put("message", "已交给后台工坊；可以继续聊天，完成后原对话会收到图片。不要重复提交或轮询。")
                    }
                }
                else -> error("workshop_arguments")
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { buildJsonObject { put("ok", false); put("error", error.message?.takeIf { it.matches(Regex("workshop_[a-z_]+")) } ?: "workshop_unavailable"); put("automatic_retry", false) } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
