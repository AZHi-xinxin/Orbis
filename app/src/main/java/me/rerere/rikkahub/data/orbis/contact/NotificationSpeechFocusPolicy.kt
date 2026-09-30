package me.rerere.rikkahub.data.orbis.contact

internal enum class NotificationSpeechFocusChange { GAIN, TRANSIENT_LOSS, DUCK, LOSS }
internal enum class NotificationSpeechFocusResult { GRANTED, DELAYED, DENIED }

/** One request only. Waiting time is cumulative, not renewed by GAIN or repeated losses. */
internal class NotificationSpeechFocusPolicy(private val waitBudgetMillis: Long = 10_000) {
    internal enum class Phase { PREPARING, REQUESTING, READY, WAITING, FINISHED }
    var phase = Phase.PREPARING
        private set
    var reasonCode: String? = null
        private set
    private var waitedMillis = 0L
    private var waitingSince = 0L
    val canPlay: Boolean get() = phase == Phase.READY
    val finished: Boolean get() = phase == Phase.FINISHED

    init { require(waitBudgetMillis > 0) }

    fun beginRequest() {
        check(phase == Phase.PREPARING) { "Notification focus is one shot" }
        phase = Phase.REQUESTING
    }

    fun requestReturned(result: NotificationSpeechFocusResult, now: Long) {
        tick(now)
        if (finished) return
        if (result == NotificationSpeechFocusResult.DENIED) cancel("audio_focus_denied")
        // A synchronous callback is newer than the request's return value; never overwrite it.
        else if (phase == Phase.REQUESTING) when (result) {
            NotificationSpeechFocusResult.GRANTED -> phase = Phase.READY
            NotificationSpeechFocusResult.DELAYED -> waitFrom(now)
            NotificationSpeechFocusResult.DENIED -> Unit
        }
    }

    fun change(change: NotificationSpeechFocusChange, now: Long) {
        tick(now)
        if (finished || phase == Phase.PREPARING) return
        when (change) {
            NotificationSpeechFocusChange.GAIN -> {
                if (phase == Phase.WAITING) waitedMillis += (now - waitingSince).coerceAtLeast(0)
                phase = Phase.READY
            }
            NotificationSpeechFocusChange.TRANSIENT_LOSS, NotificationSpeechFocusChange.DUCK -> {
                if (phase != Phase.WAITING) waitFrom(now)
            }
            NotificationSpeechFocusChange.LOSS -> cancel("audio_focus_lost")
        }
    }

    fun tick(now: Long) {
        if (phase == Phase.WAITING && waitedMillis + (now - waitingSince).coerceAtLeast(0) >= waitBudgetMillis)
            cancel("audio_focus_timeout")
    }

    fun cancel(reason: String) {
        if (finished) return
        reasonCode = reason
        phase = Phase.FINISHED
    }

    fun finish() { phase = Phase.FINISHED }

    private fun waitFrom(now: Long) {
        phase = Phase.WAITING
        waitingSince = now
    }
}
