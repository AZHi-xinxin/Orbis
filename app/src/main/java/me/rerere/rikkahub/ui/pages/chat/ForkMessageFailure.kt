package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException

/** A failed copy/save is an operation error, not an uncaught UI coroutine crash. */
internal suspend fun <T> forkOrReportFailure(
    fork: suspend () -> T,
    report: (Exception) -> Unit,
): T? = try {
    fork()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    report(failure)
    null
}
