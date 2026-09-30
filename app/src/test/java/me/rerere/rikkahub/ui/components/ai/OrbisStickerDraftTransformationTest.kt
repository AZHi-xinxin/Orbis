package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.toTextFieldBuffer
import me.rerere.rikkahub.data.model.stickerDraftText
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual Compose buffer adapters, not a substitute formatter. No UI/IME emulation. */
class OrbisStickerDraftTransformationTest {
    private val sticker = stickerDraftText("st000001", listOf("抱抱", "安慰"))

    @Test fun outputKeepsUnderlyingStateRawAndProducesFriendlyLabel() {
        val state = TextFieldState("before\n$sticker\nafter")
        val buffer = state.toTextFieldBuffer()
        with(OrbisStickerDraftOutputTransformation) { buffer.transformOutput() }
        assertEquals("before\n[表情：抱抱、安慰]\nafter", buffer.asCharSequence().toString())
        assertEquals("before\n$sticker\nafter", state.text.toString())
    }

    @Test fun actualInputBufferBackspaceDeletesCompleteToken() {
        val state = TextFieldState(sticker)
        val buffer = state.toTextFieldBuffer()
        buffer.replace(sticker.length - 1, sticker.length, "")
        with(OrbisStickerDraftInputTransformation) { buffer.transformInput() }
        assertEquals("", buffer.asCharSequence().toString())
        assertEquals(0, buffer.selection.start)
        assertEquals(sticker, state.text.toString())
    }

    @Test fun actualInputBufferSelectAllReplacementRetainsReplacement() {
        val buffer = TextFieldState(sticker).toTextFieldBuffer()
        buffer.replace(0, sticker.length, "new message")
        with(OrbisStickerDraftInputTransformation) { buffer.transformInput() }
        assertEquals("new message", buffer.asCharSequence().toString())
    }

    @Test fun actualInputBufferBoundaryPastePreservesNewTextAndSticker() {
        val buffer = TextFieldState(sticker).toTextFieldBuffer()
        buffer.replace(0, 0, "pasted\n")
        with(OrbisStickerDraftInputTransformation) { buffer.transformInput() }
        assertEquals("pasted\n$sticker", buffer.asCharSequence().toString())
    }
}
