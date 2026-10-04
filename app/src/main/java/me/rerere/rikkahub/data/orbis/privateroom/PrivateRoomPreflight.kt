package me.rerere.rikkahub.data.orbis.privateroom

import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAvailability
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/** Only fixed, non-content-bearing diagnostics may cross the private-room boundary. */
internal enum class PrivateRoomReason(val code: String, val message: String) {
    LOCAL_READY("local_ready", "本地检查通过；尚未向模型发送请求，不代表远端连接已验证。"),
    NETWORK_CHECK_REQUIRED("network_check_required", "本地检查通过；进入时仍须确认网关支持独立隐私连接，失败不会改走普通聊天。"),
    SETTINGS_LOADING("settings_loading", "设置仍在读取，请稍后重试。"),
    ASSISTANT_UNAVAILABLE("assistant_unavailable", "当前助手已不可用，请返回后重新选择。"),
    MODEL_NOT_CONFIGURED("model_not_configured", "尚未配置可用的聊天模型，请检查隐私室的模型选择。"),
    PROVIDER_UNAVAILABLE("provider_unavailable", "所选模型的提供商已不可用，请重新选择模型。"),
    PROVIDER_DISABLED("provider_disabled", "所选模型的提供商已暂停，请先在模型设置中启用。"),
    CHOICE_UNREADABLE("choice_unreadable", "隐私室模型选择无法读取；请重新选择并确认，不会自动换用其他连接。"),
    CONFIGURATION_CHANGED("configuration_changed", "模型或连接配置已变化，请重新确认隐私室的模型选择。"),
    UNSAFE_ADDRESS("unsafe_address", "连接地址不符合隐私室安全要求，请检查 HTTPS、地址凭证和查询参数。"),
    ROUTE_UNSUPPORTED("route_unsupported", "当前连接模式不支持隐私室，请明确选择兼容的安全连接。"),
    ROOM_NOT_CREATED("room_not_created", "尚未创建当前助手的隐私室。"),
    RECOVERY_UNCONFIRMED("recovery_unconfirmed", "请先确认恢复码已离线保管，再开启隐私室。"),
    ROOM_PAUSED("room_paused", "隐私室当前已暂停，请先开启。"),
    RECOVERY_REQUIRED("recovery_required", "隐私室需要使用恢复码在本机恢复；不会创建空库替代原内容。"),
    STORAGE_UNAVAILABLE("storage_unavailable", "隐私室本地存储暂不可读，请先保留加密备份，不要清除数据。"),
    CONNECTION_UNVERIFIED("connection_unverified", "未能确认独立隐私连接；可能是网络或服务端不支持，不会自动换用普通聊天。"),
    BUSY("busy", "当前已有隐私室访问在进行，不会重复启动。"),
    COMPLETED("completed", "本次隐私室访问已完成，未向普通聊天返回私密内容。"),
    INCOMPLETE("incomplete", "本次未完整结束，部分更改可能已保存；不会自动重试或公开正文。"),
}

internal data class PrivateRoomPreflight(val reason: PrivateRoomReason) {
    val reasonCode get() = reason.code
    val message get() = reason.message
    val canVisit get() = reason == PrivateRoomReason.LOCAL_READY || reason == PrivateRoomReason.NETWORK_CHECK_REQUIRED
    val networkCheckRequired get() = reason == PrivateRoomReason.NETWORK_CHECK_REQUIRED
}

internal data class PrivateRoomVisitResult(
    val outcome: PrivateRoomOutcome,
    val reason: PrivateRoomReason,
    val mayHaveSavedChanges: Boolean = false,
) {
    val reasonCode get() = reason.code
    val message get() = reason.message
}

/** Pure local inspection: no client, repository, model request, DNS or capability probe. */
internal fun privateRoomPreflight(
    settings: Settings,
    assistantId: String,
    choice: PrivateRoomModelChoice?,
    vault: PrivateVaultStatus,
): PrivateRoomPreflight {
    fun result(reason: PrivateRoomReason) = PrivateRoomPreflight(reason)
    if (settings.init) return result(PrivateRoomReason.SETTINGS_LOADING)
    val id = runCatching { Uuid.parse(assistantId) }.getOrNull()
        ?: return result(PrivateRoomReason.ASSISTANT_UNAVAILABLE)
    val owner = settings.getAssistantById(id) ?: return result(PrivateRoomReason.ASSISTANT_UNAVAILABLE)
    val selected = if (choice != null) runCatching { Uuid.parse(choice.modelId) }.getOrNull()
        ?: return result(PrivateRoomReason.CHOICE_UNREADABLE) else owner.chatModelId ?: settings.chatModelId
    val model = settings.findModelById(selected)?.takeIf { it.type == ModelType.CHAT && it.modelId.isNotBlank() }
        ?: return result(PrivateRoomReason.MODEL_NOT_CONFIGURED)
    val parent = model.findProvider(settings.providers, checkOverwrite = false)
        ?: return result(PrivateRoomReason.PROVIDER_UNAVAILABLE)
    val provider = model.findProvider(settings.providers) ?: return result(PrivateRoomReason.PROVIDER_UNAVAILABLE)
    if (!parent.enabled || !provider.enabled) return result(PrivateRoomReason.PROVIDER_DISABLED)
    if (choice != null && !choice.matches(provider, model)) return result(PrivateRoomReason.CONFIGURATION_CHANGED)
    val connection = privateRoomLocalRouteReason(provider, choice?.directApi == true)
    if (connection != PrivateRoomReason.LOCAL_READY && connection != PrivateRoomReason.NETWORK_CHECK_REQUIRED) return result(connection)
    return result(when (vault.availability) {
        PrivateVaultAvailability.ABSENT -> PrivateRoomReason.ROOM_NOT_CREATED
        PrivateVaultAvailability.RECOVERY_REQUIRED -> PrivateRoomReason.RECOVERY_REQUIRED
        PrivateVaultAvailability.UNREADABLE -> PrivateRoomReason.STORAGE_UNAVAILABLE
        PrivateVaultAvailability.READY -> when {
            !vault.recoveryConfirmed -> PrivateRoomReason.RECOVERY_UNCONFIRMED
            !vault.enabled -> PrivateRoomReason.ROOM_PAUSED
            else -> connection
        }
    })
}

/** Mirrors only deterministic route constraints; the runtime still calls the enforcing route. */
internal fun privateRoomLocalRouteReason(provider: ProviderSetting, confirmedDirectApi: Boolean): PrivateRoomReason {
    val base = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }.toHttpUrlOrNull() ?: return PrivateRoomReason.UNSAFE_ADDRESS
    if (!privateRoomTransportAllowed(provider) || base.query != null || base.fragment != null)
        return PrivateRoomReason.UNSAFE_ADDRESS
    if (provider is ProviderSetting.Google && (provider.vertexAI || provider.useServiceAccount))
        return PrivateRoomReason.ROUTE_UNSUPPORTED
    val official = base.isHttps && base.port == 443 && base.host in when (provider) {
        is ProviderSetting.OpenAI -> setOf("api.openai.com", "api.deepseek.com")
        is ProviderSetting.Google -> setOf("generativelanguage.googleapis.com")
        is ProviderSetting.Claude -> setOf("api.anthropic.com")
    }
    if (confirmedDirectApi || official) return if (provider is ProviderSetting.OpenAI &&
        provider.chatCompletionsPath != "/chat/completions") PrivateRoomReason.ROUTE_UNSUPPORTED else PrivateRoomReason.LOCAL_READY
    return if (provider is ProviderSetting.OpenAI && base.encodedPath.trimEnd('/') == "/v1")
        PrivateRoomReason.NETWORK_CHECK_REQUIRED else PrivateRoomReason.ROUTE_UNSUPPORTED
}
