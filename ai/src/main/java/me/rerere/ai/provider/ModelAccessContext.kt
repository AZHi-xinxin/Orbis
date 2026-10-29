package me.rerere.ai.provider

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Application-owned policy follows coroutine children, including auxiliary/fallback requests. */
class ModelAccessContext(val check: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelAccessContext>
}

internal suspend fun requireModelAccess(model: Model) {
    coroutineContext[ModelAccessContext]?.check?.invoke(model.id.toString())
}

internal suspend fun <T> withModelAccess(access: ((String) -> Unit)?, block: suspend () -> T): T {
    if (access == null) return block()
    val inherited = coroutineContext[ModelAccessContext]
    return kotlinx.coroutines.withContext(ModelAccessContext { id -> inherited?.check?.invoke(id); access(id) }) { block() }
}

internal suspend fun capturedModelAccess(access: ((String) -> Unit)?): CoroutineContext {
    val inherited = coroutineContext[ModelAccessContext]
    return if (access == null) inherited ?: kotlin.coroutines.EmptyCoroutineContext
    else ModelAccessContext { id -> inherited?.check?.invoke(id); access(id) }
}
