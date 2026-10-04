package me.rerere.rikkahub.data.recovery

/** Refusing a large automatic repair is not a limit on ordinary full backup/export. */
class RescueCapacityException internal constructor(val publicMessage: String) : IllegalStateException(publicMessage)

internal object RescueAdmission {
    const val MAX_RAW_BYTES = 8L * 1024 * 1024
    const val MAX_ROW_BYTES = 1024L * 1024
    const val MAX_HEADER_BYTES = 1024L * 1024
    const val MAX_NODES = 4096L
    const val MAX_CHECKPOINT_BYTES = 8L * 1024 * 1024
    private const val HEAP_RESERVE = 64L * 1024 * 1024
    private const val EXPANSION_FACTOR = 16L

    /** Includes raw UTF-16, decoded objects, JSON/fingerprint temporaries and repair readback.
     * This is a conservative admission estimate, not a guarantee against unrelated heap use. */
    fun requireSafe(nodeCount: Long, rawBytes: Long, largestRowBytes: Long, headerBytes: Long,
        checkpointBytes: Long, heapHeadroom: Long) {
        if (nodeCount !in 0..MAX_NODES || rawBytes !in 0..MAX_RAW_BYTES ||
            largestRowBytes !in 0..MAX_ROW_BYTES || headerBytes !in 0..MAX_HEADER_BYTES ||
            checkpointBytes !in 0..MAX_CHECKPOINT_BYTES || largestRowBytes > rawBytes) {
            throw RescueCapacityException("这个窗口的数据量超过本机自助修复的安全范围，已停止检查，原记录未改动。请先使用「数据与本地备份」保存完整备份，再寻求协助；不要清除数据或卸载。")
        }
        // All inputs are capped above before arithmetic; no overflow can turn a huge size negative.
        val estimate = HEAP_RESERVE + (rawBytes + headerBytes + checkpointBytes) * EXPANSION_FACTOR + nodeCount * 2048L
        if (heapHeadroom < estimate) {
            throw RescueCapacityException("手机当前可用运行内存不足以安全检查这个窗口，已停止，原记录未改动。可关闭其他应用后重试；仍失败请先保存完整本地备份并寻求协助，不要清除数据或卸载。")
        }
    }

    fun currentHeapHeadroom(): Long = Runtime.getRuntime().let { runtime ->
        (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0)
    }
}
