package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable

/** Acknowledges only the revision captured BEFORE snapshotting, never later writes. */
@Serializable
data class BackupTargetProgress(
    val revision: Long = 0,
    val completedAt: Long = 0,
    val message: String = "等待首次自动存档",
    val needsReview: Boolean = false,
)

@Serializable
data class BackupChangeLedger(
    val revision: Long = 1,
    val targets: Map<String, BackupTargetProgress> = emptyMap(),
) {
    fun changed() = copy(revision = Math.addExact(revision, 1))
    fun pending(key: String) = (targets[key]?.revision ?: 0) < revision
    fun completed(key: String, captured: Long, at: Long): BackupChangeLedger {
        require(captured in 1..revision)
        val old = targets[key] ?: BackupTargetProgress()
        return copy(targets = targets + (key to old.copy(
            revision = maxOf(old.revision, captured), completedAt = at,
            message = "已写入并读回校验", needsReview = false)))
    }
    fun failed(key: String, message: String, review: Boolean = false): BackupChangeLedger =
        copy(targets = targets + (key to (targets[key] ?: BackupTargetProgress()).copy(
            message = message, needsReview = review)))
    // Permission to continue is tied to this exact destination, not all future servers.
    fun allowExisting(key: String) = failed(key, "已确认继续保存当前资料", false)
}
