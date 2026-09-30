package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** Pure mappings are shared by database access and persistence regression tests. */
internal fun encodeConversationEntity(conversation: Conversation): ConversationEntity {
    require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
    return ConversationEntity(
        id = conversation.id.toString(),
        title = conversation.title,
        nodes = "[]", // Message nodes live in their own table.
        createAt = conversation.createAt.toEpochMilli(),
        updateAt = conversation.updateAt.toEpochMilli(),
        assistantId = conversation.assistantId.toString(),
        chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
        isPinned = conversation.isPinned,
        customSystemPrompt = conversation.customSystemPrompt ?: "",
        orbisPrompt = JsonInstant.encodeToString(conversation.orbisPrompt),
        modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
        lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
        workspaceCwd = conversation.workspaceCwd ?: "",
        folderId = conversation.folderId?.toString() ?: "",
        compactionEpoch = conversation.compactionEpoch,
        consultationBinding = conversation.consultation?.let { JsonInstant.encodeToString(it) }.orEmpty(),
    )
}

internal fun decodeConversationEntity(
    entity: ConversationEntity,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.parse(entity.id),
    title = entity.title,
    messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
    createAt = Instant.ofEpochMilli(entity.createAt),
    updateAt = Instant.ofEpochMilli(entity.updateAt),
    assistantId = Uuid.parse(entity.assistantId),
    chatSuggestions = JsonInstant.decodeFromString(entity.chatSuggestions),
    isPinned = entity.isPinned,
    customSystemPrompt = entity.customSystemPrompt.ifEmpty { null },
    orbisPrompt = JsonInstant.decodeFromString(entity.orbisPrompt),
    modeInjectionIds = JsonInstant.decodeFromString(entity.modeInjectionIds),
    lorebookIds = JsonInstant.decodeFromString(entity.lorebookIds),
    workspaceCwd = entity.workspaceCwd.ifEmpty { null },
    folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
    compactionEpoch = entity.compactionEpoch,
    consultation = entity.consultationBinding.takeIf { it.isNotEmpty() }?.let { JsonInstant.decodeFromString(it) },
)
