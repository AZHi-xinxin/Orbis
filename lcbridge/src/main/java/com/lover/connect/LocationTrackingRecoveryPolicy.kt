package com.lover.connect

/** Recovery honours the saved user choice; only a configuration edit reloads a live machine. */
internal object LocationTrackingRecoveryPolicy {
    fun shouldStart(
        enabled: Boolean,
        paused: Boolean,
        loopStarted: Boolean,
        reloadConfiguration: Boolean = false,
    ): Boolean = enabled && !paused && (!loopStarted || reloadConfiguration)
}
