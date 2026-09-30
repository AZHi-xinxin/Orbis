package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    orbis: Boolean = false,
    assistant: Assistant? = null,
    onRename: ((Conversation) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    searchResults: List<OrbisDrawerSearchResult>? = null,
    onSearchResult: (OrbisDrawerSearchResult) -> Unit = {},
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        // The Orbis drawer opens at its saved position, not automatically past its search/new controls.
        if (hasScrolledToCurrent || orbis) return@LaunchedEffect
        val currentIndex = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == current.id
        }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(key = "orbis_drawer_controls") { header() }
        if (searchResults != null) {
            items(count = searchResults.size, key = { "search_${searchResults[it].conversation.id}" }) { index ->
                val hit = searchResults[index]
                ConversationItem(
                    conversation = hit.conversation, selected = hit.conversation.id == current.id,
                    loading = hit.conversation.id in conversationJobs, onClick = { onSearchResult(hit) },
                    onDelete = onDelete, onRegenerateTitle = onRegenerateTitle, onPin = onPin,
                    onMoveToAssistant = onMoveToAssistant, onMoveToFolder = onMoveToFolder,
                    orbis = orbis, assistant = assistant, onRename = onRename, snippet = hit.snippet,
                )
            }
        } else {
        if (conversations.itemCount == 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    is ConversationListItem.DateHeader -> "date_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            }
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem(), orbis = orbis,
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem(), orbis = orbis,
                    )
                }

                is ConversationListItem.Item -> {
                    if (orbis && item.conversation.assistantId != current.assistantId) return@items
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        modifier = Modifier.animateItem(), orbis = orbis, assistant = assistant, onRename = onRename,
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
        }
        if (footer != null) item(key = "orbis_drawer_appearance") { footer() }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier,
    orbis: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (orbis) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = if (orbis) 3.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (orbis) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp) else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier,
    orbis: Boolean = false,
) {
    if (orbis) {
        DateHeaderItem("固定主窗 · 已置顶", modifier, orbis = true)
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    onClick: (Conversation) -> Unit,
    orbis: Boolean = false,
    assistant: Assistant? = null,
    onRename: ((Conversation) -> Unit)? = null,
    snippet: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(if (orbis) 14.dp else 50.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onClick(conversation) },
                onLongClick = {
                    // Also clear chat input focus when the drawer is permanently visible.
                    focusManager.clearFocus(force = true)
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = if (orbis) 9.dp else 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (orbis) {
                val colors = OrbisTheme.colors
                val name = assistant?.name?.ifBlank { "AI" } ?: "AI"
                UIAvatar(name, assistant?.avatar ?: Avatar.Emoji(name.take(1)), Modifier.size(32.dp))
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(conversation.title.ifBlank { "新会话" }, fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snippet ?: if (selected) "当前窗口 · 聊天记录保存在本机" else "本地会话 · 长按可置顶与整理",
                        fontSize = 9.sp, lineHeight = 12.sp, color = colors.mutedInk,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(conversation.updateAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                        fontSize = 8.sp, color = colors.mutedInk)
                    Text(if (loading) "生成中" else if (conversation.isPinned) "主窗" else "会话",
                        fontSize = 8.sp, color = colors.onSand,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.sand).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (onRename != null) IconButton(onClick = { onRename(conversation) }, modifier = Modifier.size(48.dp)) {
                    Icon(HugeIcons.PencilEdit01, "重命名 ${conversation.title.ifBlank { "此窗口" }}", Modifier.size(17.dp), tint = colors.onSand)
                }
            } else {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = HugeIcons.Pin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics {
                            contentDescription = "Loading"
                        }
                )
            }
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                if (onRename != null) DropdownMenuItem(
                    text = { Text("重命名窗口") }, leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                    onClick = { onRename(conversation); showDropdownMenu = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                        )
                    },
                    onClick = {
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            null
                        )
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Refresh01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_assistant))
                    },
                    onClick = {
                        onMoveToAssistant(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Forward02, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_folder))
                    },
                    onClick = {
                        onMoveToFolder(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, null)
                    }
                )
            }
        }
    }
}
