package me.rerere.rikkahub.data.recovery

private const val ROUTER = "me.rerere.rikkahub.ui.activity.OrbisLauncherActivity"
private val RESCUE_ACTIVITIES = setOf(
    "me.rerere.rikkahub.ui.activity.EmergencyBackupActivity",
    "me.rerere.rikkahub.ui.activity.SafeModeActivity",
    "me.rerere.rikkahub.ui.activity.ConversationRescueActivity",
)

/** Never remove chat tasks, foreign tasks, the current task, or an active external picker. */
internal fun isRetiredOrbisTask(
    owner: String, currentTaskId: Int, candidateTaskId: Int,
    basePackage: String?, baseClass: String?, topPackage: String?, topClass: String?,
    rescueEnabled: Boolean,
): Boolean {
    if (candidateTaskId < 0 || candidateTaskId == currentTaskId || basePackage != owner) return false
    fun retired(name: String?) = name == ROUTER || (!rescueEnabled && name in RESCUE_ACTIVITIES)
    if (!retired(baseClass)) return false
    return (topPackage == null && topClass == null) || (topPackage == owner && retired(topClass))
}
