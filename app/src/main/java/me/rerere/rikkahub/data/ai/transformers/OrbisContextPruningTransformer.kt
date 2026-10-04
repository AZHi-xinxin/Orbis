package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningRepository
import me.rerere.rikkahub.data.ai.contextpruning.projectContextPruningForRequest

/** Must run before any input transformer that edits source parts. Bound to one conversation,
 * never a global current-tab pointer. GenerationInputSnapshot keeps this wake's tool chain fixed. */
class OrbisContextPruningTransformer(private val repository: ContextPruningRepository) : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> = withContext(Dispatchers.IO) {
        check(ctx.assistant.id.toString() == repository.assistantId) { "context_pruning_owner_changed" }
        projectContextPruningForRequest(messages, repository.snapshot())
    }
}
