package me.rerere.rikkahub.data.orbis.companiontools

/** Pure state machine: one reminder per outage, never an instruction to change a permission. */
internal class AccessibilityHealthPolicy(
    initialState: State = State(),
    private val reconnectGraceMillis: Long = 15_000L,
) {
    data class State(
        val everEnabled: Boolean = false,
        val outageActive: Boolean = false,
        val foregroundShown: Boolean = false,
        val notificationPosted: Boolean = false,
    )

    data class Sample(val enabledInSettings: Boolean, val connected: Boolean)
    enum class Issue { SETTING_DISABLED, CONNECTION_UNAVAILABLE }

    var state: State = initialState
        private set
    var issue: Issue? = null
        private set
    private var disconnectedSince: Long? = null

    /** Null/failed platform reads must not be submitted as a disabled sample. */
    fun observe(sample: Sample, elapsedMillis: Long) {
        val everEnabled = state.everEnabled || sample.enabledInSettings || sample.connected
        if (sample.enabledInSettings && sample.connected) {
            state = State(everEnabled = true)
            disconnectedSince = null
            issue = null
            return
        }
        state = state.copy(everEnabled = everEnabled)
        if (!everEnabled) {
            disconnectedSince = null
            issue = null
            return
        }
        val since = disconnectedSince?.takeIf { it <= elapsedMillis } ?: elapsedMillis.also {
            disconnectedSince = it
        }
        // A startup/rebind gap is not a new outage. Elapsed time is process-local,
        // so a restarted process gets the same grace even if an outage was saved.
        if (elapsedMillis - since < reconnectGraceMillis) {
            issue = null
            return
        }
        state = state.copy(outageActive = true)
        issue = if (sample.enabledInSettings) Issue.CONNECTION_UNAVAILABLE else Issue.SETTING_DISABLED
    }

    fun shouldShowForeground(): Boolean = issue != null && !state.foregroundShown

    fun shouldPostNotification(): Boolean =
        issue != null && !state.foregroundShown && !state.notificationPosted

    fun foregroundWasShown() {
        if (issue != null) state = state.copy(foregroundShown = true)
    }

    /** Call only after the OS accepts the notification; lack of permission is not delivery. */
    fun notificationWasPosted() {
        if (issue != null) state = state.copy(notificationPosted = true)
    }
}
