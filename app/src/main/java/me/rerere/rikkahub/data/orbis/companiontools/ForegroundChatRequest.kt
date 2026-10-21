package me.rerere.rikkahub.data.orbis.companiontools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal data class ForegroundChatReceipt(val confirmed: Boolean, val status: String)

/**
 * A startActivity return value is not a foreground acknowledgement. Only the
 * resumed, focused composition for the requested conversation can confirm it.
 * No history, assistant messages, or persistent observation is kept here.
 */
internal class ForegroundChatRequest {
    private val visible = MutableStateFlow<Map<Any, String>>(emptyMap())
    private val requestLock = Mutex()

    @Synchronized fun observe(token: Any, conversationId: String, focused: Boolean) {
        visible.value = if (focused) visible.value + (token to conversationId) else visible.value - token
    }

    fun isVisible(conversationId: String) = conversationId in visible.value.values

    suspend fun request(
        conversationId: String,
        timeoutMillis: Long = 1800,
        launch: suspend () -> Unit,
    ): ForegroundChatReceipt {
        if (!requestLock.tryLock()) return ForegroundChatReceipt(false, "another_request_running")
        try {
            if (isVisible(conversationId)) return ForegroundChatReceipt(true, "already_visible")
            try {
                launch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return ForegroundChatReceipt(false, "system_permission_required")
            } catch (_: Exception) {
                return ForegroundChatReceipt(false, "launch_failed")
            }
            val observed = withTimeoutOrNull(timeoutMillis) {
                visible.first { conversationId in it.values }
            } != null
            return ForegroundChatReceipt(observed, if (observed) "visible" else "foreground_not_observed")
        } finally {
            requestLock.unlock()
        }
    }
}

internal val orbisForegroundChat = ForegroundChatRequest()
