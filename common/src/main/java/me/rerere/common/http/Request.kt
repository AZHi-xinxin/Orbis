package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        // Ending the coroutine must also end the transport, not leave its remote turn running.
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

/**
 * Keep cancellation attached until body parsing completes, not just until headers arrive.
 * Parsing happens on OkHttp's callback thread; cancellation closes the socket even while the
 * parser is blocked reading a stalled body. The response is always closed before returning.
 */
suspend fun <T> Call.awaitAndUse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.closeQuietly()
                return
            }
            val result = try { response.use(read) }
            catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
                return
            }
            continuation.resume(result)
        }
    })
}
