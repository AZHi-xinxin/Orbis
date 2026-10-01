package me.rerere.asr

/** Presentation-only acknowledgement. Never clears or edits the original ASR audit. */
data class ASRCorrectionNotice(
    val eventId: Long = 0,
    val review: ASRCorrectionResult? = null,
    val dismissed: Boolean = false,
) {
    val visible: Boolean get() = review?.changed == true && !dismissed

    fun begin(nextEventId: Long): ASRCorrectionNotice = ASRCorrectionNotice(eventId = nextEventId)

    /** Partial/final updates belong to one capture, including after its notice was dismissed. */
    fun accept(captureId: Long, result: ASRCorrectionResult): ASRCorrectionNotice =
        if (captureId == eventId) copy(review = result) else this

    /** A late dialog close must not consume the next utterance's notice. */
    fun dismiss(captureId: Long): ASRCorrectionNotice =
        if (captureId == eventId) copy(dismissed = true) else this
}
