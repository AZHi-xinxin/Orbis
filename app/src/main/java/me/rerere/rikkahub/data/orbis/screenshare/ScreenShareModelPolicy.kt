package me.rerere.rikkahub.data.orbis.screenshare

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.orbis.selectGomokuModel
import java.net.URI

internal class ScreenShareHelperUnavailable : IllegalStateException("当前服务没有独立辅助模型；画面可供正常聊天查看，自动观察和总结暂未调用。")

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
