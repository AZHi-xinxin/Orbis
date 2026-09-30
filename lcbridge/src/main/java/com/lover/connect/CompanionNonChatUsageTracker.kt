package com.lover.connect

/** Consecutive non-chat use spans app switches; the old single-app rest tracker stays separate. */
internal class CompanionNonChatUsageTracker {
    private val continuity = AppRestTracker()
    private var currentPackage: String? = null

    fun observeForeground(packageName: String?, elapsedMs: Long) {
        val pkg = packageName?.trim()?.takeIf { it.isNotBlank() }
        when {
            pkg == null -> pause(elapsedMs)
            AppRestPolicy.isChatPackage(pkg) || AppRestPolicy.isLauncherPackage(pkg) || pkg == "android" -> reset()
            AppRestPolicy.isTransientSystemPackage(pkg) -> pause(elapsedMs)
            else -> {
                currentPackage = pkg
                continuity.observeForeground(AGGREGATE_PACKAGE, elapsedMs)
            }
        }
    }

    fun pause(elapsedMs: Long) {
        continuity.pause(elapsedMs)
        currentPackage = null
    }

    fun reset() {
        continuity.reset()
        currentPackage = null
    }

    fun observation(elapsedMs: Long, wallTimeMs: Long): CompanionAppUsageObservation? {
        val pkg = currentPackage ?: return null
        return continuity.observation(elapsedMs, wallTimeMs)?.copy(packageName = pkg)
    }

    private companion object {
        const val AGGREGATE_PACKAGE = "native.sentinel.nonchat.aggregate"
    }
}
