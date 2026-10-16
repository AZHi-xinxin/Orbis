package me.rerere.ai.provider.chatgpt

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting

/** Capabilities of the SIWC transport, not every capability offered on the ChatGPT website. */
object ChatGptPlanCapabilities {
    // Exact documented slugs only. Unknown/future models retain their existing configuration.
    // Source: developers.openai.com/api/docs/models/<slug> (verified 2026-10-14).
    private val reasoningModels = setOf(
        "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
    )
    private val alwaysReasoningModels = setOf("gpt-6.1-sol", "gpt-6-astra")

    fun usesPlan(model: Model, parentProvider: ProviderSetting?): Boolean =
        ((model.providerOverwrite ?: parentProvider) as? ProviderSetting.OpenAI)?.chatGptAccountId != null

    fun supportsBuiltInTool(tool: BuiltInTools): Boolean = tool == BuiltInTools.Search

    /** AUTO is a local choice: omit effort so the service uses its model default. */
    fun reasoningLevels(modelId: String): List<ReasoningLevel>? = when (modelId) {
        in alwaysReasoningModels -> ReasoningLevel.entries.filter { it != ReasoningLevel.OFF }
        in reasoningModels -> ReasoningLevel.entries.toList()
        else -> null
    }

    fun reasoningLevels(model: Model, parentProvider: ProviderSetting?): List<ReasoningLevel>? =
        if (usesPlan(model, parentProvider)) reasoningLevels(model.modelId) else null

    fun supportsReasoning(model: Model, parentProvider: ProviderSetting?): Boolean =
        ModelAbility.REASONING in model.abilities || reasoningLevels(model, parentProvider) != null

    fun reasoningLevelForRequest(modelId: String, requested: ReasoningLevel): ReasoningLevel =
        if (modelId in alwaysReasoningModels && requested == ReasoningLevel.OFF) ReasoningLevel.LOW
        else requested

    /** Used on import and on a request copy, so legacy saved models do not need to be rebuilt. */
    fun withKnownModelAbilities(model: Model): Model =
        if (model.modelId in reasoningModels && ModelAbility.REASONING !in model.abilities) {
            model.copy(abilities = model.abilities + ModelAbility.REASONING)
        } else model

    /**
     * Project a request copy only. Legacy optional toggles must not block an unrelated text turn,
     * or erase settings that may still be used with another provider. Explicit tool schemas and
     * returned tool calls remain subject to the strict transport validation.
     */
    fun projectModelForRequest(model: Model): Model = withKnownModelAbilities(model).copy(
        tools = model.tools.filterTo(linkedSetOf()) { supportsBuiltInTool(it) },
    )
}
