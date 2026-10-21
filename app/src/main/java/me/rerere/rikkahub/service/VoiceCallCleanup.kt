package me.rerere.rikkahub.service

/**
 * Finite, synchronous cleanup of resources already owned by one call. Each release gets
 * its own exception boundary: a failed recorder or listener must not skip audio focus,
 * routing, foreground service or the terminal UI state. Never includes exception text
 * (which can contain provider URLs/content) in the diagnostic returned to the caller.
 * This does not claim to time out a blocking Android/native release operation.
 */
internal class VoiceCallCleanup {
    private val failed = linkedSetOf<String>()
    val failures: List<String> get() = failed.toList()

    fun attempt(step: String, release: () -> Unit): Boolean = try {
        release()
        true
    } catch (_: Exception) {
        failed.add(step)
        false
    }

    fun include(steps: List<String>) { failed.addAll(steps) }

    suspend fun attemptSuspending(step: String, release: suspend () -> Unit): Boolean = try {
        release()
        true
    } catch (_: Exception) {
        failed.add(step)
        false
    }
}

/** Join failures must not skip archiving/final UI completion or release a live owner. */
internal suspend fun completeVoiceCallShutdown(
    cleanup: VoiceCallCleanup,
    joinStartup: suspend () -> Unit,
    joinVoice: suspend () -> Unit,
    releaseConfirmed: () -> Boolean,
    releaseOwner: () -> Unit,
    persistEnd: suspend (List<String>) -> Unit,
    finished: (List<String>) -> Unit,
) {
    try {
        cleanup.attemptSuspending("VOICE_STARTUP_JOIN", joinStartup)
        cleanup.attemptSuspending("VOICE_SESSION_JOIN", joinVoice)
        var confirmed = false
        cleanup.attempt("VOICE_SHUTDOWN_CONFIRM") { confirmed = releaseConfirmed() }
        if (confirmed) cleanup.attempt("VOICE_OWNER_RELEASE", releaseOwner)
        else cleanup.include(listOf("VOICE_SHUTDOWN_UNCONFIRMED"))
        cleanup.attemptSuspending("CALL_END_PERSIST_FAILED") { persistEnd(cleanup.failures) }
    } finally {
        finished(cleanup.failures)
    }
}

internal fun voiceCallCleanupError(error: String?, failures: List<String>): String? {
    if (failures.isEmpty()) return error
    val diagnostic = "通话已结束，部分资源释放未确认。[CALL_CLEANUP:${failures.distinct().joinToString(",")}]"
    return listOfNotNull(error?.takeIf { it.isNotBlank() }, diagnostic).joinToString("\n")
}
