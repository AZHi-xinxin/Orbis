package com.lover.connect

/** Orders negative window hints against authoritative usage events, without reading UI contents. */
class AppRestEventGate {
    private var blockedThroughMs = Long.MIN_VALUE
    private var hintPackage: String? = null
    private var hintAtMs = Long.MIN_VALUE

    fun reset(wallMs: Long) {
        blockedThroughMs = wallMs
        hintPackage = null
        hintAtMs = Long.MIN_VALUE
    }

    fun blockThrough(wallMs: Long) {
        blockedThroughMs = maxOf(blockedThroughMs, wallMs)
        hintPackage = null
        hintAtMs = Long.MIN_VALUE
    }

    fun expect(packageName: String, wallMs: Long) {
        if (wallMs < hintAtMs) return
        hintPackage = packageName
        hintAtMs = wallMs
    }

    fun allowsResume(packageName: String?, eventAtMs: Long, observedAtMs: Long): Boolean {
        if (packageName.isNullOrBlank() || eventAtMs <= blockedThroughMs ||
            eventAtMs > observedAtMs || observedAtMs - eventAtMs > 15_000L) return false
        // A normal window callback can arrive just AFTER its resume event. A matching,
        // bounded-late usage event is evidence; the window callback by itself is not.
        return hintPackage == null || packageName == hintPackage || eventAtMs > hintAtMs
    }
}
