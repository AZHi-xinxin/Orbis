package me.rerere.rikkahub.data.ai.checkpoint

/** Closed local failure stages: no message bodies, record IDs or filesystem paths. */
internal enum class HistoryPersistenceStage(val code: String, val notice: String) {
    SNAPSHOT_MISMATCH("history_save_not_aligned", "当前窗口与本机已保存记录尚未对齐，暂未改写历史；原消息与恢复副本保留。"),
    RECOVERY_COPY_PENDING("history_continuation_pending", "聊天已保存，但续聊恢复副本尚未完成核对，暂未改写历史；原副本保留。"),
    CHECK_TIMEOUT("history_check_timeout", "本机历史保存核对等待超时，暂未改写历史；请稍后重试并保留错误卡。"),
}

internal class HistoryPersistenceException(val stage: HistoryPersistenceStage) : IllegalStateException(stage.notice)
