package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.dokar.sonner.ToastType
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.normalizeConversationTitle
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisAppearancePanel
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    onClose: (() -> Unit)? = null,
    drawerOpen: Boolean? = null,
) = OrbisVisualTheme {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val personalSettingsStore: SettingsStore = koinInject()
    val colors = OrbisTheme.colors
    var manualVisible by rememberSharedPreferenceBoolean("orbis_chat_manual_visible", true)
    val configuration = LocalConfiguration.current
    val closeFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Modal drawer content stays composed while closed. Only an actual opening transition
    // should move focus; recomposition, typing, and permanent sidebars must not dismiss IME.
    LaunchedEffect(drawerOpen) {
        if (BuildConfig.ORBIS_ENABLED && drawerOpen == true && onClose != null) {
            withFrameNanos { }
            closeFocusRequester.requestFocus()
            keyboardController?.hide()
        }
    }
    val compactLayout = configuration.screenHeightDp < 560 || LocalDensity.current.fontScale > 1.5f
    val drawerWidth = (configuration.screenWidthDp.dp * if (BuildConfig.ORBIS_ENABLED) .88f else .92f)
        .coerceAtMost(if (BuildConfig.ORBIS_ENABLED) 360.dp else 380.dp)
    val selectedAssistant = settings.assistants.firstOrNull { it.id == current.assistantId }
    val assistantName = selectedAssistant?.name?.ifBlank { null }
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)
    LaunchedEffect(current.assistantId) { drawerVm.bindAssistant(current.assistantId) }
    val searchQuery by drawerVm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by drawerVm.searchResults.collectAsStateWithLifecycle()
    val searchBusy by drawerVm.searchBusy.collectAsStateWithLifecycle()
    val searchError by drawerVm.searchError.collectAsStateWithLifecycle()

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    val saveUserNickname: (String) -> Unit = { nickname ->
        scope.launch {
            try {
                personalSettingsStore.updateUserNickname(nickname)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: IllegalArgumentException) {
                toaster.show("昵称最多 80 个字符，且不能包含换行或控制字符。", type = ToastType.Error)
            } catch (_: Exception) {
                toaster.show("昵称未保存，请稍后重试。", type = ToastType.Error)
            }
        }
    }
    // Both the old nickname dialog and the explicit personal card use the narrow persisted update.
    val nicknameEditState = useEditState<String>(saveUserNickname)

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }

    var showSavedProfiles by remember { mutableStateOf(false) }
    var conversationToRename by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val beginRename: (Conversation) -> Unit = { item ->
        conversationToRename = item; renameText = item.title; renameError = null
    }


    ModalDrawerSheet(
        modifier = Modifier.width(drawerWidth),
        drawerShape = if (BuildConfig.ORBIS_ENABLED) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        drawerContainerColor = colors.page,
        drawerContentColor = colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.panel, colors.page)))
                .padding(horizontal = 12.dp, vertical = if (BuildConfig.ORBIS_ENABLED || compactLayout) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (BuildConfig.ORBIS_ENABLED || compactLayout) 6.dp else 10.dp),
        ) {
            // Fixed same-assistant header; history and all DIY controls share the list below it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (BuildConfig.ORBIS_ENABLED) {
                    UIAvatar(assistantName ?: "当前 AI", selectedAssistant?.avatar ?: Avatar.Emoji("✦"), Modifier.size(39.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${assistantName ?: "当前 AI"}的会话", fontSize = 13.sp, lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("同一个${assistantName ?: "AI"} · 不拆成多个助手", fontSize = 9.sp, lineHeight = 13.sp,
                            color = colors.mutedInk)
                    }
                } else {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(userAvatar = newAvatar)
                            )
                        )
                    },
                    modifier = Modifier.size(48.dp),
                )
                Column(
                    modifier = Modifier.weight(1f)
                        .clickable(role = Role.Button) { nicknameEditState.open(settings.displaySetting.userNickname) }
                        .heightIn(min = 48.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compactLayout) {
                        Text(
                            text = assistantName?.let { "与${it}的会话" } ?: "会话与历史",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.mutedInk,
                        )
                    }
                }
                }
                Box {
                    IconButton(onClick = { showMenuPopup = true }, modifier = Modifier.size(48.dp)) {
                        Icon(HugeIcons.MoreVertical, contentDescription = "会话与更多选项")
                    }
                    DropdownMenu(expanded = showMenuPopup, onDismissRequest = { showMenuPopup = false }) {
                        DropdownMenuItem(
                            text = { Text("当前会话配置") },
                            leadingIcon = { Icon(HugeIcons.Settings03, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.AssistantDetail(id = current.assistantId.toString()))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("已有配置与备份") },
                            leadingIcon = { Icon(HugeIcons.LookTop, null) },
                            onClick = { showMenuPopup = false; showSavedProfiles = true },
                        )
                        DropdownMenuItem(
                            text = { Text("修改昵称") },
                            leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                            onClick = {
                                showMenuPopup = false
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.favorite_page_title)) },
                            leadingIcon = { Icon(HugeIcons.InLove, null) },
                            onClick = { showMenuPopup = false; navController.navigate(Screen.Favorite) },
                        )
                        DropdownMenuItem(
                            text = { Text("使用统计") },
                            leadingIcon = { Icon(HugeIcons.ChartColumn, null) },
                            onClick = { showMenuPopup = false; navController.navigate(Screen.Stats) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                            onClick = { showMenuPopup = false; navController.navigate(Screen.Translator) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                            leadingIcon = { Icon(HugeIcons.Image02, null) },
                            onClick = { showMenuPopup = false; navController.navigate(Screen.ImageGen) },
                        )
                    }
                }
                if (BuildConfig.ORBIS_ENABLED && onClose != null) {
                    Surface(shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, colors.border), color = colors.panel) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)
                            .focusRequester(closeFocusRequester)
                            .focusProperties { canFocus = true }) {
                            Text("×", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = "关闭会话侧栏" })
                        }
                    }
                }
            }

            if (BuildConfig.ORBIS_ENABLED) {
                HorizontalDivider(color = colors.border)
                ConversationList(
                    current = current, conversations = conversations, conversationJobs = conversationJobs.keys,
                    listState = conversationListState, modifier = Modifier.fillMaxWidth().weight(1f),
                    orbis = true, assistant = selectedAssistant, onRename = beginRename,
                    onClick = { onClose?.invoke(); navigateToChatPage(navController, it.id) },
                    onSearchResult = { hit -> onClose?.invoke(); navigateToChatPage(navController, hit.conversation.id, nodeId = hit.nodeId) },
                    searchResults = if (searchQuery.isBlank()) null else searchResults.filter { it.conversation.assistantId == current.assistantId },
                    onRegenerateTitle = { vm.generateTitle(it, true) },
                    onDelete = { item -> scope.launch {
                        vm.deleteConversation(item).join(); conversations.refresh()
                        if (item.id == current.id) navigateToChatPage(navController)
                    } },
                    onPin = { vm.updatePinnedStatus(it) },
                    onMoveToAssistant = { conversationToMove = it; showMoveToAssistantSheet = true },
                    onMoveToFolder = { conversationToMoveFolder = it; showMoveToFolderSheet = true },
                    header = {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("同一身份下的聊天窗口；聊天与外观保存在本机。", fontSize = 9.sp,
                                lineHeight = 14.sp, color = colors.mutedInk)
                            Button(onClick = { selectedAssistant?.let { assistant ->
                                creating = true
                                scope.launch {
                                    try {
                                        val id = drawerVm.createConversation(assistant)
                                        onClose?.invoke(); navigateToChatPage(navController, id)
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                                    } catch (_: Exception) { toaster.show("新会话未创建，请重试。", type = ToastType.Error)
                                    } finally { creating = false }
                                }
                            } }, enabled = selectedAssistant != null && !creating, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(if (creating) "正在创建…" else "+  新建同一${assistantName ?: "AI"}会话", fontSize = 11.sp)
                            }
                            TextButton(onClick = { beginRename(current) }, modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.textButtonColors(containerColor = colors.sand, contentColor = colors.onSand)) {
                                Text("✎ 重命名当前窗口", fontSize = 11.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(searchQuery, drawerVm::search, Modifier.weight(1f).heightIn(min = 48.dp), singleLine = true,
                                    shape = RoundedCornerShape(13.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    placeholder = { Text("搜索标题、正文关键词…", fontSize = 11.sp) })
                                TextButton(onClick = { drawerVm.search("") }, modifier = Modifier.size(48.dp)) { Text("清", fontSize = 12.sp) }
                            }
                            if (searchQuery.isBlank()) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("本地历史 · 长按可整理", Modifier.weight(1f), fontSize = 9.sp,
                                        lineHeight = 13.sp, color = colors.mutedInk)
                                    OrbisFolderMenu(folders, selectedFolderId, drawerVm::selectFolder,
                                        onCreate = { showCreateFolderDialog = true },
                                        onRename = { folderToRename = it }, onDelete = { folderToDelete = it })
                                }
                            } else Text(when {
                                searchBusy -> "正在本机搜索…"
                                searchError != null -> searchError!!
                                searchQuery.isNotBlank() -> "本 AI 全部文件夹 · ${searchResults.size} 个匹配会话（最多 50 条）"
                                else -> "本 AI 的本地搜索结果"
                            }, fontSize = 9.sp, lineHeight = 13.sp, color = colors.mutedInk)
                            if (searchError != null) TextButton(onClick = { navController.navigate(Screen.MessageSearch) }) { Text("打开完整搜索") }
                        }
                    },
                    footer = {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbisAppearancePanel(
                    displaySetting = settings.displaySetting,
                    assistant = settings.assistants.firstOrNull { it.id == current.assistantId },
                    onUpdateDisplay = { transform ->
                        val latest = vm.settings.value
                        if (!latest.init) vm.updateSettings(latest.copy(displaySetting = transform(latest.displaySetting)))
                    },
                    onUpdateUserNickname = saveUserNickname,
                    onUpdateAssistantAvatar = { avatar ->
                        val latest = vm.settings.value
                        if (!latest.init) vm.updateSettings(latest.copy(assistants = latest.assistants.map { item ->
                            if (item.id == current.assistantId) item.copy(avatar = avatar, useAssistantAvatar = true) else item
                        }))
                    },
                    onNavigate = { navController.navigate(it) },
                    onBack = { onClose?.invoke() },
                    embedded = true,
                    showDoneButton = onClose != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                    if (!manualVisible) TextButton(onClick = { manualVisible = true; onClose?.invoke() },
                        modifier = Modifier.fillMaxWidth()) { Text("重新显示聊天说明书提示", fontSize = 11.sp) }
                }
                    },
                )
            } else {
            DrawerActions(navController = navController, compact = compactLayout)

            // Keep the existing assistant-scoped folder filter and its CRUD actions.
            FolderBar(
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelect = { drawerVm.selectFolder(it) },
                onCreate = { showCreateFolderDialog = true },
                onRename = { folderToRename = it },
                onDelete = { folderToDelete = it },
            )

            // Same paged data, date/pinned sections, saved scroll and conversation callbacks.
            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = { navigateToChatPage(navController, it.id) },
                onRegenerateTitle = { vm.generateTitle(it, true) },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) navigateToChatPage(navController)
                    }
                },
                onPin = { vm.updatePinnedStatus(it) },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                },
            )
            }

            if (!BuildConfig.ORBIS_ENABLED) Surface(
                color = colors.sand,
                contentColor = colors.onSand,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DrawerAction(
                        icon = HugeIcons.Sun01,
                        label = "外观",
                        compact = compactLayout,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            navController.navigate(Screen.SettingPreferencesTheme)
                        },
                    )
                    DrawerAction(
                        icon = HugeIcons.Bookshelf01,
                        label = "请求日志",
                        compact = compactLayout,
                        modifier = Modifier.weight(1f),
                        onClick = { navController.navigate(Screen.Log) },
                    )
                    DrawerAction(
                        icon = HugeIcons.Settings03,
                        label = stringResource(R.string.settings),
                        compact = compactLayout,
                        modifier = Modifier.weight(1f),
                        onClick = { navController.navigate(Screen.Setting) },
                    )
                }
            }
        }
    }

    conversationToRename?.let { target ->
        AlertDialog(onDismissRequest = { if (!renaming) conversationToRename = null }, title = { Text("重命名窗口") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(renameText, { renameText = it; renameError = null }, singleLine = true,
                    label = { Text("窗口名称 · 1–60 个字符") }, isError = renameError != null,
                    enabled = !renaming, modifier = Modifier.fillMaxWidth())
                renameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("只改名称，不改聊天内容、身份或附件。", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = !renaming, onClick = {
                val title = runCatching { normalizeConversationTitle(renameText) }.getOrElse {
                    renameError = "窗口名称需要 1–60 个可见字符"; return@TextButton
                }
                renaming = true
                scope.launch {
                    try {
                        if (drawerVm.renameConversation(target.id, title)) {
                            conversationToRename = null; conversations.refresh()
                            drawerVm.refreshSearch()
                        } else renameError = "此窗口尚未保存或已不存在，请先完成一条消息后重试。"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                    } catch (_: Exception) { renameError = "名称未保存，请重试；聊天内容未改动。"
                    } finally { renaming = false }
                }
            }) { Text(if (renaming) "保存中…" else "保存") } },
            dismissButton = { TextButton(enabled = !renaming, onClick = { conversationToRename = null }) { Text("取消") } },
        )
    }

    // Secondary access only: no assistant/configuration is deleted, merged or silently selected.
    if (showSavedProfiles) {
        ModalBottomSheet(
            onDismissRequest = { showSavedProfiles = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            containerColor = colors.panel,
            contentColor = colors.ink,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("已有配置与备份", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("日常在当前配置下开启多个会话；已有配置与记录仍然保留。",
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
            AssistantPicker(
                settings = settings,
                onUpdateSettings = {
                    val updateJob = vm.updateSettings(it)
                    scope.launch {
                        updateJob.join()
                        val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                            Uuid.random()
                        } else {
                            repo.getConversationsOfAssistant(it.assistantId)
                                .first()
                                .firstOrNull()
                                ?.id ?: Uuid.random()
                        }
                        navigateToChatPage(navigator = navController, chatId = id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    val currentAssistantId = settings.assistantId
                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                }
            )

                TextButton(
                    onClick = { showSavedProfiles = false; navController.navigate(Screen.Assistant) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("管理已有配置") }
                TextButton(
                    onClick = { showSavedProfiles = false; navController.navigate(Screen.Backup) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("备份、恢复与导入") }
                BackupReminderCard(
                    settings = settings,
                    onClick = { showSavedProfiles = false; navController.navigate(Screen.Backup) },
                )
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DrawerActions(navController: Navigator, compact: Boolean) {
    val colors = OrbisTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            Surface(
                onClick = { navController.navigate(Screen.MessageSearch) },
                shape = RoundedCornerShape(18.dp),
                color = colors.raisedPanel,
                contentColor = colors.mutedInk,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(HugeIcons.Search01, null, modifier = Modifier.size(20.dp))
                    Text("搜索聊天标题、正文关键词", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compact) {
                IconButton(
                    onClick = { navController.navigate(Screen.MessageSearch) },
                    modifier = Modifier.size(48.dp),
                ) { Icon(HugeIcons.Search01, "搜索聊天标题、正文关键词") }
            }
            Button(
                onClick = { navigateToChatPage(navController) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(HugeIcons.Add01, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建会话", style = MaterialTheme.typography.labelLarge)
            }
            if (compact) {
                IconButton(onClick = { navController.navigate(Screen.History) }, modifier = Modifier.size(48.dp)) {
                    Icon(HugeIcons.TransactionHistory, "聊天历史")
                }
            } else {
                TextButton(onClick = { navController.navigate(Screen.History) },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(HugeIcons.TransactionHistory, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("历史")
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = OrbisTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 48.dp else 64.dp)
            .semantics { if (compact) contentDescription = label },
        color = Color.Transparent,
        contentColor = colors.onSand,
        shape = RoundedCornerShape(18.dp),
    ) {
        Tooltip(tooltip = { Text(label) }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, modifier = Modifier.size(22.dp))
                if (!compact) Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OrbisFolderMenu(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = folders.firstOrNull { it.id == selectedFolderId }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 180.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            Text("分组：${selected?.name ?: "未归类"} ⌄", fontSize = 10.sp, lineHeight = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("未归类", fontSize = 12.sp) }, onClick = {
                expanded = false; onSelect(null)
            })
            folders.forEach { folder ->
                DropdownMenuItem(text = { Text(folder.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(HugeIcons.Folder01, null, Modifier.size(18.dp)) }, onClick = {
                        expanded = false; onSelect(folder.id)
                    })
            }
            HorizontalDivider(color = OrbisTheme.colors.border)
            DropdownMenuItem(text = { Text("新建分组", fontSize = 12.sp) },
                leadingIcon = { Icon(HugeIcons.FolderAdd, null, Modifier.size(18.dp)) }, onClick = {
                    expanded = false; onCreate()
                })
            if (selected != null) {
                DropdownMenuItem(text = { Text("重命名当前分组", fontSize = 12.sp) }, onClick = {
                    expanded = false; onRename(selected)
                })
                DropdownMenuItem(text = { Text("删除当前分组", fontSize = 12.sp) }, onClick = {
                    expanded = false; onDelete(selected)
                })
            }
        }
    }
}

@Composable
private fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = {},
            )
        }
        items(folders) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            onRename(folder)
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            onDelete(folder)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = {},
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
