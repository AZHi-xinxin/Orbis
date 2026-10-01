package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar

/** Explicit display-only projection. Never serialize Settings or nested provider/MCP configs to Web. */
@Serializable
data class WebSettingsDto(
    val dynamicColor: Boolean,
    val themeId: String,
    val developerMode: Boolean,
    val displaySetting: WebDisplayDto,
    val favoriteModels: List<String>,
    val chatModelId: String,
    val assistantId: String,
    val providers: List<WebProviderDto>,
    val assistants: List<WebAssistantDto>,
    val assistantTags: List<WebNamedDto>,
    val modeInjections: List<WebNamedDto>,
    val lorebooks: List<WebNamedDto>,
    val quickMessages: List<WebQuickMessageDto>,
    val mcpServers: List<WebMcpDto>,
    val searchServices: List<WebSearchDto>,
    val searchServiceSelected: Int,
    val webServerJwtEnabled: Boolean,
    val webImportEnabled: Boolean,
)

@Serializable
data class WebDisplayDto(
    val userNickname: String,
    val userAvatar: WebAvatarDto,
    val showUserAvatar: Boolean,
    val showModelIcon: Boolean,
    val showModelName: Boolean,
    val showTokenUsage: Boolean,
    val showThinkingContent: Boolean,
    val autoCloseThinking: Boolean,
    val codeBlockAutoWrap: Boolean,
    val codeBlockAutoCollapse: Boolean,
    val showLineNumbers: Boolean,
    val sendOnEnter: Boolean,
    val enableAutoScroll: Boolean,
    val fontSizeRatio: Float,
    val pasteLongTextAsFile: Boolean,
    val pasteLongTextThreshold: Int,
)

@Serializable data class WebAvatarDto(val type: String, val content: String? = null, val url: String? = null)
@Serializable data class WebNamedDto(val id: String, val name: String, val enabled: Boolean = true)
@Serializable data class WebQuickMessageDto(val id: String, val title: String, val content: String)
@Serializable data class WebSearchDto(val id: String, val type: String)
@Serializable data class WebProviderDto(val id: String, val enabled: Boolean, val name: String, val models: List<WebModelDto>)
@Serializable data class WebModelDto(
    val id: String, val modelId: String, val displayName: String, val type: String,
    val inputModalities: List<String>, val outputModalities: List<String>,
    val abilities: List<String>, val tools: List<JsonObject>,
)
@Serializable data class WebAssistantDto(
    val id: String, val name: String, val avatar: WebAvatarDto, val useAssistantAvatar: Boolean,
    val chatModelId: String?, val reasoningLevel: String, val enableWebSearch: Boolean,
    val mcpServers: List<String>, val modeInjectionIds: List<String>, val lorebookIds: List<String>,
    val allowConversationPromptInjection: Boolean, val allowConversationSystemPrompt: Boolean,
    val tags: List<String>, val quickMessageIds: List<String>,
)
@Serializable data class WebMcpDto(val id: String, val commonOptions: WebMcpOptionsDto)
@Serializable data class WebMcpOptionsDto(val enable: Boolean, val name: String, val tools: List<WebMcpToolDto>)
@Serializable data class WebMcpToolDto(val enable: Boolean, val name: String, val description: String?, val needsApproval: Boolean)

private fun Avatar.toWebAvatar(): WebAvatarDto = when (this) {
    Avatar.Dummy -> WebAvatarDto("me.rerere.rikkahub.data.model.Avatar.Dummy")
    is Avatar.Emoji -> WebAvatarDto("me.rerere.rikkahub.data.model.Avatar.Emoji", content = content)
    is Avatar.Image -> WebAvatarDto("me.rerere.rikkahub.data.model.Avatar.Image", url = url)
}

fun Settings.toWebSettingsDto(): WebSettingsDto = WebSettingsDto(
    dynamicColor = dynamicColor,
    themeId = themeId,
    developerMode = developerMode,
    displaySetting = displaySetting.let { d -> WebDisplayDto(
        d.userNickname, d.userAvatar.toWebAvatar(), d.showUserAvatar, d.showModelIcon, d.showModelName,
        d.showTokenUsage, d.showThinkingContent, d.autoCloseThinking, d.codeBlockAutoWrap,
        d.codeBlockAutoCollapse, d.showLineNumbers, d.sendOnEnter, d.enableAutoScroll,
        d.fontSizeRatio, d.pasteLongTextAsFile, d.pasteLongTextThreshold,
    ) },
    favoriteModels = favoriteModels.map { it.toString() },
    chatModelId = chatModelId.toString(),
    assistantId = assistantId.toString(),
    providers = providers.map { p -> WebProviderDto(p.id.toString(), p.enabled, p.name,
        p.models.map { m -> WebModelDto(m.id.toString(), m.modelId, m.displayName, m.type.name,
            m.inputModalities.map { it.name }, m.outputModalities.map { it.name }, m.abilities.map { it.name },
            m.tools.map { tool -> buildJsonObject { put("type", when (tool) {
                BuiltInTools.Search -> "search"
                BuiltInTools.UrlContext -> "url_context"
                BuiltInTools.ImageGeneration -> "image_generation"
            }) } },
        ) },
    ) },
    assistants = assistants.map { a -> WebAssistantDto(
        a.id.toString(), a.name, a.avatar.toWebAvatar(), a.useAssistantAvatar,
        a.chatModelId?.toString(), a.reasoningLevel.name.lowercase(java.util.Locale.ROOT), a.enableWebSearch,
        a.mcpServers.map { it.toString() }, a.modeInjectionIds.map { it.toString() }, a.lorebookIds.map { it.toString() },
        a.allowConversationPromptInjection, a.allowConversationSystemPrompt,
        a.tags.map { it.toString() }, a.quickMessageIds.map { it.toString() },
    ) },
    assistantTags = assistantTags.map { WebNamedDto(it.id.toString(), it.name) },
    modeInjections = modeInjections.map { WebNamedDto(it.id.toString(), it.name, it.enabled) },
    lorebooks = lorebooks.map { WebNamedDto(it.id.toString(), it.name, it.enabled) },
    quickMessages = quickMessages.map { WebQuickMessageDto(it.id.toString(), it.title, it.content) },
    mcpServers = mcpServers.map { s -> WebMcpDto(s.id.toString(), s.commonOptions.let { c ->
        WebMcpOptionsDto(c.enable, c.name, c.tools.map { WebMcpToolDto(it.enable, it.name, it.description, it.needsApproval) })
    }) },
    // The display name is sufficient for the picker; endpoint/auth/custom-script fields stay native.
    searchServices = searchServices.map { WebSearchDto(it.id.toString(), it.displayName) },
    searchServiceSelected = searchServiceSelected,
    webServerJwtEnabled = webServerJwtEnabled,
    webImportEnabled = !webServerJwtEnabled || webServerAccessPassword.isNotBlank(),
)
