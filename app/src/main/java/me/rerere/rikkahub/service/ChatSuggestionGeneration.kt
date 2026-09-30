package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/**
 * A suggestion request is optional work. Turning the persistent preference off
 * cancels that request; turning it back on does not resurrect the old response.
 * Observe the switch before starting the provider so even a quick disable is
 * not missed while the request is being constructed.
 */
internal suspend fun <T> generateWhileChatSuggestionsEnabled(
    enabled: Flow<Boolean>,
    generate: suspend () -> T,
): T? = coroutineScope {
    if (!enabled.first()) return@coroutineScope null
    val disabled = async(start = CoroutineStart.UNDISPATCHED) { enabled.first { !it } }
    if (disabled.isCompleted) return@coroutineScope null
    val request = async {
        val result = generate()
        // A provider may finish non-cancellable cleanup after the switch changed.
        currentCoroutineContext().ensureActive()
        result
    }
    try {
        select {
            disabled.onAwait { null }
            request.onAwait { result -> if (enabled.first()) result else null }
        }
    } finally {
        disabled.cancel()
        request.cancel()
    }
}
