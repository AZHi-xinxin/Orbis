package me.rerere.ai.provider

import android.content.Context
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        @Suppress("UNCHECKED_CAST")
        val delegate = provider as Provider<ProviderSetting>
        providers[name] = guardedProvider(delegate, null)
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T, access: ((String) -> Unit)? = null): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        val provider = when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
        return if (access == null) provider else guardedProvider(provider, access)
    }
}

private fun <T : ProviderSetting> guardedProvider(delegate: Provider<T>, access: ((String) -> Unit)?): Provider<T> {
    return object : Provider<T> by delegate {
            override suspend fun generateText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
                requireModelAccess(params.model)
                access?.invoke(params.model.id.toString())
                return withModelAccess(access) { delegate.generateText(providerSetting, messages, params) }
            }
            override suspend fun streamText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams) = flow {
                requireModelAccess(params.model)
                access?.invoke(params.model.id.toString())
                emitAll(delegate.streamText(providerSetting, messages, params).flowOn(capturedModelAccess(access)))
            }
            override suspend fun generateEmbedding(providerSetting: T, params: EmbeddingGenerationParams): EmbeddingGenerationResult {
                requireModelAccess(params.model)
                access?.invoke(params.model.id.toString())
                return withModelAccess(access) { delegate.generateEmbedding(providerSetting, params) }
            }
            override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams) = flow {
                requireModelAccess(params.model)
                access?.invoke(params.model.id.toString())
                emitAll(delegate.generateImage(providerSetting, params).flowOn(capturedModelAccess(access)))
            }
            override suspend fun editImage(providerSetting: ProviderSetting, params: ImageEditParams) = flow {
                requireModelAccess(params.model)
                access?.invoke(params.model.id.toString())
                emitAll(delegate.editImage(providerSetting, params).flowOn(capturedModelAccess(access)))
            }
        }
}
