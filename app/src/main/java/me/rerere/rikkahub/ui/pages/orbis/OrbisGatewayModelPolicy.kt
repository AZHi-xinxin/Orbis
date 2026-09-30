package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.json.*
import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal const val GATEWAY_MODELS_MAX_BYTES = 65_536
internal class OrbisGatewayModelException(val code: String) : IllegalStateException(when (code) {
    "changed" -> "当前 AI 或连接设置已变化，请重新读取目录后选择；没有替换其他配置。"
    "not_gateway" -> "此连接不是已公布主线路的 ST 网关。DeepSeek、硅基等普通模型服务是上游，请返回选择承载 ST 的连接，再添加上游模型。"
    "invalid_directory" -> "模型目录格式不正确或超过限制，未更改设置。"
    "overridden" -> "此 AI 或型号有覆盖请求型号/连接的高级设置，请先人工核对；没有绕过它切换。"
    "http" -> "网关未成功返回模型目录，请核对连接权限与服务状态。"
    "connection" -> "暂时无法读取模型目录，原绑定未变；没有自动重试。"
    else -> "无法完成这次绑定，请重新核对选择；没有自动更换。"
})
private fun gatewayRequire(condition: Boolean, code: String) { if (!condition) throw OrbisGatewayModelException(code) }

/** owned_by is an informational ST protocol declaration, never authentication or an authority grant. */
internal fun parseOrbisGatewayDirectory(body: String): List<String> {
    gatewayRequire(body.toByteArray(Charsets.UTF_8).size <= GATEWAY_MODELS_MAX_BYTES, "invalid_directory")
    return try {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: throw OrbisGatewayModelException("invalid_directory")
        gatewayRequire(data.size <= 64, "invalid_directory")
        val aliases = data.mapNotNull { row ->
            val item = row.jsonObject
            val owner = item["owned_by"]?.jsonPrimitive?.contentOrNull
            if (owner != "stiller-gateway") null else {
                val alias = item["id"]?.jsonPrimitive?.takeIf { it.isString }?.content
                    ?: throw OrbisGatewayModelException("invalid_directory")
                gatewayRequire(alias.length in 1..128 && alias.isNotBlank() && alias.none { it.isISOControl() }, "invalid_directory")
                alias.takeUnless { it.endsWith("--auxiliary-no-memory") }
            }
        }
        gatewayRequire(aliases.isNotEmpty(), "not_gateway")
        gatewayRequire(aliases.size == aliases.distinct().size, "invalid_directory")
        aliases.sorted()
    } catch (error: OrbisGatewayModelException) { throw error }
    catch (_: Exception) { throw OrbisGatewayModelException("invalid_directory") }
}

/** Runtime-only revision snapshot; never serialized or logged (the provider contains credentials). */
internal class OrbisGatewayBinding(
    private val assistant: Assistant,
    private val provider: ProviderSetting.OpenAI,
    val aliases: List<String>,
) {
    private val assistantRevision = JsonInstant.encodeToString(assistant)
    private val providerRevision = JsonInstant.encodeToString<ProviderSetting>(provider)
    val assistantId: Uuid get() = assistant.id
    val assistantName: String get() = orbisSettingsLabel(assistant.name, "当前 AI")
    val providerName: String get() = orbisSettingsLabel(provider.name, "所选网关")
    fun checkCurrent(current: Settings) {
        val freshAssistant = current.assistants.filter { it.id == assistant.id }.singleOrNull()
        val freshProvider = current.providers.filter { it.id == provider.id }.singleOrNull()
        gatewayRequire(!current.init && current.assistantId == assistant.id && freshAssistant != null && freshProvider != null &&
            JsonInstant.encodeToString(freshAssistant) == assistantRevision &&
            JsonInstant.encodeToString<ProviderSetting>(freshProvider) == providerRevision && provider.enabled, "changed")
    }
    fun apply(current: Settings, alias: String): Settings {
        checkCurrent(current)
        gatewayRequire(alias in aliases && !alias.endsWith("--auxiliary-no-memory"), "changed")
        val existing = provider.models.filter { it.modelId == alias }
        gatewayRequire(existing.size <= 1, "changed")
        val chosen = existing.singleOrNull() ?: Model(modelId = alias, displayName = alias)
        gatewayRequire(chosen.type == ModelType.CHAT && chosen.providerOverwrite == null &&
            chosen.customBodies.none { it.key == "model" } && assistant.customBodies.none { it.key == "model" }, "overridden")
        gatewayRequire(current.providers.flatMap { it.models }.count { it.id == chosen.id } == if (existing.isEmpty()) 0 else 1, "changed")
        return current.copy(
            assistants = current.assistants.map { if (it.id == assistant.id) it.copy(chatModelId = chosen.id) else it },
            providers = if (existing.isEmpty()) current.providers.map {
                if (it.id == provider.id) provider.copy(models = provider.models + chosen) else it
            } else current.providers,
        )
    }
}

/** Called only inside DataStore.edit: merge two fields against persisted values, not stale UI state. */
internal fun applyOrbisGatewayBinding(preferences: MutablePreferences, binding: OrbisGatewayBinding, alias: String) {
    val assistantId = preferences[SettingsStore.SELECT_ASSISTANT]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw OrbisGatewayModelException("changed")
    val current = Settings(
        assistantId = assistantId,
        assistants = JsonInstant.decodeFromString(preferences[SettingsStore.ASSISTANTS] ?: "[]"),
        providers = JsonInstant.decodeFromString(preferences[SettingsStore.PROVIDERS] ?: "[]"),
    )
    val next = binding.apply(current, alias)
    preferences[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(next.assistants)
    if (next.providers != current.providers) preferences[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(next.providers)
}

internal fun orbisGatewayRevision(settings: Settings, assistantId: Uuid, providerId: Uuid): Pair<Assistant, ProviderSetting.OpenAI> {
    gatewayRequire(!settings.init && settings.assistantId == assistantId, "changed")
    val assistant = settings.assistants.filter { it.id == assistantId }.singleOrNull()
        ?: throw OrbisGatewayModelException("changed")
    val provider = settings.providers.filter { it.id == providerId }.singleOrNull() as? ProviderSetting.OpenAI
        ?: throw OrbisGatewayModelException("changed")
    gatewayRequire(provider.enabled && !provider.useResponseApi && provider.chatCompletionsPath == "/chat/completions", "overridden")
    return assistant.copy() to provider.copy(models = provider.models.toList())
}
