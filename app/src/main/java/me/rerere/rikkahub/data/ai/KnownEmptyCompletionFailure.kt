package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException

/**
 * This classification applies to ONE provider response, never to a complete tool chain.
 * Earlier tool side effects remain real, so the failed turn must not be resubmitted automatically.
 * It only permits subsequent independent queued messages after the current snapshot is durable.
 */
internal class KnownEmptyCompletionFailure private constructor(cause: HttpException) :
    RuntimeException(cause.message, cause) {
    companion object {
        fun classify(error: Throwable, response: UIMessage?): Throwable {
            if (error !is HttpException || error.code != "upstream_empty_completion" ||
                error.errorType != "stiller_gateway_error") return error
            val onlyReasoning = response?.parts.orEmpty().all { part ->
                part is UIMessagePart.Reasoning || (part is UIMessagePart.Text && part.text.isBlank())
            }
            // Even an incomplete tool-call fragment, image or other output makes replay/safety
            // uncertain. Do not infer anything from human-readable error message substrings.
            return if (onlyReasoning) KnownEmptyCompletionFailure(error) else error
        }
    }
}
