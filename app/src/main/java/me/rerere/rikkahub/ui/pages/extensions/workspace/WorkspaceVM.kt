package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
    val error = MutableStateFlow<String?>(null)

    fun bindToAssistant(workspace: WorkspaceEntity?, assistantId: kotlin.uuid.Uuid) {
        viewModelScope.launch {
            try {
                if (workspace != null) check(repository.getById(workspace.id) != null) { "工作区已不存在" }
                settingsStore.update { current ->
                    check(current.assistantId == assistantId) { "当前 AI 已切换，请重新选择" }
                    current.copy(assistants = current.assistants.map { assistant ->
                        if (assistant.id == assistantId) assistant.copy(workspaceId = workspace?.id?.let { kotlin.uuid.Uuid.parse(it) }) else assistant
                    })
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error.value = e.message ?: "未能更新工作区绑定" }
        }
    }
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String) {
        viewModelScope.launch {
            try { repository.create(name) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "创建失败" }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            repository.delete(workspace.id)
        }
    }
}
