package me.rerere.rikkahub.data.orbis.screenshare

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.orbis.selectGomokuModel
import java.net.URI

internal class ScreenShareHelperUnavailable : IllegalStateException("当前服务没有独立辅助模型；画面可供正常聊天查看，自动观察和总结暂未调用。")

open class ScreenShareInputUnavailableException(message: String) : IllegalStateException(message)
internal class ScreenShareSetupException(message: String) : ScreenShareInputUnavailableException(message)
internal class ScreenShareRecognitionException : ScreenShareInputUnavailableException(
    "共享画面识别失败；请检查 OCR 模型的图片能力、连接和额度，再重试。本轮没有把画面交给聊天模型。")

internal data class ScreenShareHelperRoute(val model: Model, val provider: ProviderSetting, val ocrPrompt: String? = null)

/** An explicitly selected OCR connection is also the user's choice for screen descriptions. */
internal fun screenShareOcrRoute(settings: Settings): ScreenShareHelperRoute {
    val selected = settings.findModelById(settings.ocrModelId)
        ?: throw ScreenShareSetupException("当前聊天模型不支持图片，请在设置 → 默认模型中选择支持图片的 OCR 模型，再开始共享。")
    val container = selected.findProvider(settings.providers, checkOverwrite = false)
        ?: throw ScreenShareSetupException("OCR 模型的服务已不存在，请重新选择图片识别模型。")
    val model = if (container.models.any { it.modelId == selected.modelId + "--auxiliary-no-memory" }) {
        try { selectGomokuModel(selected, settings.providers) }
        catch (_: Exception) { throw ScreenShareSetupException("OCR 辅助模型与原模型的连接不一致，请检查连接设置。") }
    } else selected
    val provider = model.findProvider(settings.providers)
        ?: throw ScreenShareSetupException("OCR 模型的服务已不存在，请重新选择图片识别模型。")
    if (!container.enabled || !provider.enabled) throw ScreenShareSetupException("OCR 模型的服务已停用，请先在连接设置中启用。")
    if (Modality.IMAGE !in model.inputModalities) throw ScreenShareSetupException("OCR 模型未开启图片输入能力，请选择支持图片的模型并核对能力设置。")
    return ScreenShareHelperRoute(model, provider, settings.ocrPrompt)
}

internal fun validateScreenShareModel(selected: Model?, settings: Settings) {
    if (selected == null) throw ScreenShareSetupException("请先为当前 AI 选择聊天模型。")
    if (selected.findProvider(settings.providers)?.enabled != true ||
        selected.findProvider(settings.providers, checkOverwrite = false)?.enabled != true)
        throw ScreenShareSetupException("聊天模型的服务未启用，请检查连接设置。")
    if (Modality.IMAGE !in selected.inputModalities) screenShareOcrRoute(settings)
}

internal fun screenShareHelperRoute(selected: Model, settings: Settings): ScreenShareHelperRoute {
    if (settings.findModelById(settings.ocrModelId) != null) return screenShareOcrRoute(settings)
    val model = selectScreenShareHelperModel(selected, settings.providers)
    return ScreenShareHelperRoute(model, model.findProvider(settings.providers) ?: throw ScreenShareHelperUnavailable())
}

internal fun selectScreenShareHelperModel(selected: Model, providers: List<ProviderSetting>): Model {
    val candidate = try { selectGomokuModel(selected, providers) } catch (_: Exception) { throw ScreenShareHelperUnavailable() }
    if (candidate.modelId.endsWith("--auxiliary-no-memory")) return candidate
    val provider = candidate.findProvider(providers) ?: throw ScreenShareHelperUnavailable()
    val endpoint = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }
    val uri = runCatching { URI(endpoint) }.getOrNull()
    if (uri?.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
        uri.host?.lowercase() !in setOf("api.openai.com", "api.deepseek.com", "api.anthropic.com", "generativelanguage.googleapis.com"))
        throw ScreenShareHelperUnavailable()
    return candidate
}
