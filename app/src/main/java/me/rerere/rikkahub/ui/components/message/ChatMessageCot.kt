package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.isOrbisVoiceNote
import me.rerere.rikkahub.data.model.successfulOrbisVoiceNotes

/** UI projection only: these rows must never be persisted or sent to a model as extra messages. */
sealed interface OrbisVoiceNoteDisplayItem {
    data class Content(val parts: List<UIMessagePart>, val startIndex: Int) : OrbisVoiceNoteDisplayItem
    data class Voice(
        val audio: UIMessagePart.Audio,
        val partIndex: Int,
        val outputIndex: Int?,
        val details: List<UIMessagePart>,
    ) : OrbisVoiceNoteDisplayItem {
        val occurrenceKey: String get() = "$partIndex:${outputIndex ?: "direct"}"
    }
}

/**
 * Split voice and prose into sibling bubbles. Only a voice-only reply's reasoning is moved to
 * its explicit details menu; unrelated reasoning/tools/errors always remain in the main timeline.
 * Blank provider text is not a visual placeholder and never creates an empty prose bubble.
 */
fun List<UIMessagePart>.orbisVoiceNoteDisplay(): List<OrbisVoiceNoteDisplayItem>? {
    fun UIMessagePart.isVoice() = when (this) {
        is UIMessagePart.Audio -> isOrbisVoiceNote()
        is UIMessagePart.Tool -> successfulOrbisVoiceNotes().isNotEmpty()
        else -> false
    }
    if (none { it.isVoice() }) return null
    val voiceOnly = all { it.isVoice() || it is UIMessagePart.Reasoning || (it is UIMessagePart.Text && it.text.isBlank()) }
    val hiddenReasoning = if (voiceOnly) filterIsInstance<UIMessagePart.Reasoning>() else emptyList()
    val result = mutableListOf<OrbisVoiceNoteDisplayItem>()
    val pending = mutableListOf<UIMessagePart>()
    var startIndex = 0
    fun flush() {
        if (pending.isNotEmpty()) result.add(OrbisVoiceNoteDisplayItem.Content(pending.toList(), startIndex))
        pending.clear()
    }
    forEachIndexed { index, part ->
        when {
            part is UIMessagePart.Text && part.text.isBlank() -> Unit
            voiceOnly && part is UIMessagePart.Reasoning -> Unit
            part.isVoice() -> {
                flush()
                if (part is UIMessagePart.Audio) result.add(OrbisVoiceNoteDisplayItem.Voice(part, index, null, hiddenReasoning))
                else if (part is UIMessagePart.Tool) part.successfulOrbisVoiceNotes().forEachIndexed { outputIndex, audio ->
                    result.add(OrbisVoiceNoteDisplayItem.Voice(audio, index, outputIndex, hiddenReasoning + part))
                }
            }
            else -> {
                if (pending.isEmpty()) startIndex = index
                pending.add(part)
            }
        }
    }
    flush()
    return result
}

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning、客户端 Tool 和 ServerTool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                val voices = part.successfulOrbisVoiceNotes()
                if (voices.isNotEmpty()) {
                    flushThinkingSteps()
                    voices.forEach { result.add(MessagePartBlock.ContentBlock(it, index)) }
                } else currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}
