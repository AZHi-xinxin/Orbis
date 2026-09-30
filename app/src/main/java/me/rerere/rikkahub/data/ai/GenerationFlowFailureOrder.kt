package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

/**
 * A producer failure normally cancels flowOn's buffered updates. Tool receipts already represent
 * real side effects, so deliver those updates before reporting an ordinary provider failure.
 * Cancellation deliberately keeps its original immediate-stop behavior.
 */
internal fun <T> Flow<T>.orderedGenerationFlow(context: CoroutineContext): Flow<T> =
    map { Result.success(it) }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(Result.failure(failure))
        }
        .flowOn(context)
        .map { it.getOrThrow() }
