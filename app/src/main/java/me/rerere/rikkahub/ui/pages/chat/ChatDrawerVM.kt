package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val boundAssistantId = MutableStateFlow<Uuid?>(null)
    private val assistantIdFlow = combine(settingsStore.settingsFlow, boundAssistantId) { settings, bound ->
        bound ?: settings.assistantId
    }
        .distinctUntilChanged()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<OrbisDrawerSearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private val _searchBusy = MutableStateFlow(false)
    val searchBusy = _searchBusy.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()
    private val searchRevision = MutableStateFlow(0)

    fun bindAssistant(id: Uuid) {
        if (boundAssistantId.value != id) {
            _selectedFolderId.value = null
            _searchQuery.value = ""
            _searchResults.value = emptyList()
            boundAssistantId.value = id
        }
    }
    fun search(value: String) { _searchQuery.value = value.take(200) }
    fun refreshSearch() { searchRevision.value += 1 }

    suspend fun renameConversation(id: Uuid, title: String): Boolean = chatService.renameConversation(id, title)

    suspend fun createConversation(assistant: Assistant): Uuid {
        // Explicit identity binding; navigation may then select that existing identity as usual.
        val conversation = Conversation.ofId(Uuid.random(), assistant.id, newConversation = true)
            .updateCurrentMessages(assistant.presetMessages)
        conversationRepo.insertConversation(conversation)
        return conversation.id
    }

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId) { assistantId, folderId ->
            assistantId to folderId
        }
            .flatMapLatest { (assistantId, folderId) ->
                if (folderId == null || folderRepo.getFoldersOfAssistant(assistantId).first().none { it.id == folderId }) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId)
                }
            }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
                _searchQuery.value = ""
            }
        }
        viewModelScope.launch {
            combine(assistantIdFlow, _searchQuery, searchRevision) { id, query, _ -> id to query.trim() }
                .collectLatest { (assistantId, query) ->
                    _searchResults.value = emptyList()
                    _searchError.value = null
                    _searchBusy.value = query.isNotEmpty()
                    if (query.isEmpty()) return@collectLatest
                    try {
                        delay(250)
                        val titles = conversationRepo.searchConversationsOfAssistant(assistantId, query).first()
                            .map { OrbisDrawerSearchResult(it) }
                        val messages = conversationRepo.searchMessages(query, assistantId = assistantId).mapNotNull { hit ->
                            val summary = conversationRepo.getConversationSummaryOfAssistant(Uuid.parse(hit.conversationId), assistantId)
                                ?: return@mapNotNull null
                            OrbisDrawerSearchResult(
                                summary,
                                snippet = hit.snippet, nodeId = Uuid.parse(hit.nodeId),
                            )
                        }
                        _searchResults.value = mergeOrbisDrawerSearch(assistantId, titles, messages)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _searchError.value = "本地搜索暂不可用，请重试或打开完整搜索。"
                    } finally {
                        _searchBusy.value = false
                    }
                }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
