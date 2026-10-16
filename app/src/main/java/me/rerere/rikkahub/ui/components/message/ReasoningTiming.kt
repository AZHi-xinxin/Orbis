package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Duration
import kotlin.time.Instant

/** A missing end timestamp in history is not evidence of a still-running generation. */
internal fun reasoningDuration(reasoning: UIMessagePart.Reasoning, generating: Boolean, now: Instant): Duration? =
    (reasoning.finishedAt ?: now.takeIf { generating })?.let {
        (it - reasoning.createdAt).coerceAtLeast(Duration.ZERO)
    }
