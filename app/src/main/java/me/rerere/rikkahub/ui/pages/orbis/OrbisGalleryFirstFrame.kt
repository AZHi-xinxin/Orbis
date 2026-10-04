package me.rerere.rikkahub.ui.pages.orbis

/**
 * Loading is not a drawing guarantee. Keep the host-coloured cover until the renderer acknowledges
 * the owned document's visual-state request. No timeout, JavaScript message, or progress percentage
 * can uncover it. One instance belongs to one artwork WebView, not to its changing viewport.
 */
internal class GalleryFirstFrame(private val documentUrl: String) {
    var covered: Boolean = true
        private set
    private var pendingRequest: Long? = null
    private var nextRequest = 0L
    private var stopped = false

    fun requestAfterPageFinished(url: String?): Long? {
        if (stopped || !covered || pendingRequest != null || url != documentUrl) return null
        return (++nextRequest).also { pendingRequest = it }
    }

    fun acknowledgeVisualState(requestId: Long): Boolean {
        if (stopped || !covered || requestId != pendingRequest) return false
        pendingRequest = null
        covered = false
        return true
    }

    /** A detached/zero-sized view must ask again when attached with a drawable viewport. */
    fun deferVisualState(requestId: Long) {
        if (!stopped && covered && pendingRequest == requestId) pendingRequest = null
    }

    /** Renderer failure/release is terminal; a queued callback cannot reveal a failed/old view. */
    fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        pendingRequest = null
        covered = true
        return true
    }
}
