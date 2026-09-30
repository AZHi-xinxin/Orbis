package me.rerere.rikkahub.service

/** All checks and the following history write must share the session edit mutex.
 * Check journal presence even after a job has ended: failed commits retain that record.
 * Storage errors deliberately propagate rather than interpreting them as an empty journal.
 */
internal suspend fun requireHistoryMutationReady(
    busy: Boolean,
    recoveryBlocked: Boolean,
    hasCheckpoint: suspend () -> Boolean,
) {
    check(!busy) { "正在回复，请等本次回复结束后再修改消息或已读／折叠状态。" }
    check(!recoveryBlocked && !hasCheckpoint()) {
        "回复恢复记录待核对，暂不修改消息或已读／折叠状态；原记录均保留。"
    }
}
