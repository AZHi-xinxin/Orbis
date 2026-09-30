package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import me.rerere.rikkahub.data.model.orbisStickerAtomicDeletion
import me.rerere.rikkahub.data.model.orbisStickerDraftSpans

/** Display-only replacement. Compose calculates cursor/selection offset mapping; raw draft stays. */
object OrbisStickerDraftOutputTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for (span in orbisStickerDraftSpans(asCharSequence().toString()).asReversed()) {
            replace(span.start, span.end, span.label)
        }
    }
}

/** Backspace/delete removes a complete friendly token, never leaves half its hidden JSON behind. */
@OptIn(ExperimentalFoundationApi::class)
object OrbisStickerDraftInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val before = changes.getOriginalRange(0)
        val after = changes.getRange(0)
        val deletion = orbisStickerAtomicDeletion(
            original = originalText.toString(),
            originalStart = before.min,
            originalEnd = before.max,
            replacementLength = after.length,
            changeCount = changes.changeCount,
        ) ?: return
        // There is exactly one pure deletion, so reverting cannot discard an unrelated edit/paste.
        revertAllChanges()
        replace(deletion.start, deletion.end, "")
        selection = TextRange(deletion.start)
    }
}
