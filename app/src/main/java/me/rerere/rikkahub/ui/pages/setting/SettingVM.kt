package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.data.model.withAppearanceForStyle

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /** Only changes the archive selector, never saves a stale full settings page snapshot. */
    fun setVoiceArchiveModel(modelId: kotlin.uuid.Uuid?, fallback: Boolean, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    settingsStore.update { current ->
                        require(modelId == null || current.providers.any { provider ->
                            provider.models.any { it.id == modelId }
                        }) { "这个模型已不存在，请重新选择。" }
                        require(modelId == null || modelId != if (fallback) current.orbisVoiceArchiveModelId
                            else current.orbisVoiceArchiveFallbackModelId) { "备用归档模型不能与主归档模型相同。" }
                        if (fallback) current.copy(orbisVoiceArchiveFallbackModelId = modelId)
                        else current.copy(orbisVoiceArchiveModelId = modelId)
                    }
                }
                onResult(null)
            } catch (_: Exception) { onResult("归档模型未保存，请确认模型仍存在且与另一归档模型不同后重试。") }
        }
    }

    fun setMessageUsageVisible(visible: Boolean) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(displaySetting = current.displaySetting.copy(showTokenUsage = visible))
            }
        }
    }

    /** Merge only the changed color into the latest settings, not a page snapshot. */
    fun setChatTextColor(color: Int?, deepSeek: Boolean = false, onFailure: () -> Unit) {
        viewModelScope.launch {
            try {
                // A short local write must finish even when the user leaves this page.
                withContext(NonCancellable) {
                    settingsStore.update { current ->
                        current.copy(displaySetting = current.displaySetting.withAppearanceForStyle(deepSeek) {
                            it.copy(chatTextColor = color)
                        })
                    }
                }
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    /** Merge only this editor's local presentation values, preserving newer color/background writes. */
    fun setChatFlow(value: OrbisChatFlowSettings, onFailure: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    settingsStore.update { current ->
                        current.copy(displaySetting = current.displaySetting.copy(
                            orbisAppearance = current.displaySetting.orbisAppearance.copy(chatFlow = value),
                        ))
                    }
                }
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
}
