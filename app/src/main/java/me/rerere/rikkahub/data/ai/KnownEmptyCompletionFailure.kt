package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.util.HttpException
import me.rerere.ai.core.MessageRole

/**
 * Compatibility with older ST gateways that mislabeled a completed silent response as an error.
 * GenerationLoop consumes this as normal completion, never as a reason to replay or show a warning.
 * Earlier tool results belong to the conversation and are not replaced by this provider segment.
 */
internal class KnownEmptyCompletionFailure private constructor(cause: HttpException) :
    RuntimeException(cause.message, cause) {
    companion object {
        fun classify(error: Throwable, response: UIMessage?): Throwable {
            if (error !is HttpException || error.code !in setOf("upstream_empty_completion", "upstream_output_limit_reached") ||
                error.errorType != "stiller_gateway_error") return error
            // An unfinished tool fragment is not a completed tool instruction. All other
            // received output is preserved without judging whether silence was intentional.
            return if (response?.parts.orEmpty().any { it is UIMessagePart.Tool && !it.isExecuted }) error
                else KnownEmptyCompletionFailure(error)
        }

        fun completionOrNull(error: Throwable, response: UIMessage?): UIMessage? {
            if (classify(error, response) !is KnownEmptyCompletionFailure) return null
            return (response ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())).finishReasoning()
        }
    }
}
