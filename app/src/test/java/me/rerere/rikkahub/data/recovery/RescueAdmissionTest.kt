package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class RescueAdmissionTest {
    private val ampleHeap = 1024L * 1024 * 1024

    @Test fun fixedLimitsAreInclusiveAndDoNotDecodeAnyMessage() {
        RescueAdmission.requireSafe(RescueAdmission.MAX_NODES, RescueAdmission.MAX_RAW_BYTES,
            RescueAdmission.MAX_ROW_BYTES, RescueAdmission.MAX_HEADER_BYTES,
            RescueAdmission.MAX_CHECKPOINT_BYTES, ampleHeap)
    }

    @Test fun eachOversizedInputIsRejectedBeforeAllocation() {
        val safe = longArrayOf(1, 128, 128, 128, 128)
        val maximums = longArrayOf(RescueAdmission.MAX_NODES, RescueAdmission.MAX_RAW_BYTES,
            RescueAdmission.MAX_ROW_BYTES, RescueAdmission.MAX_HEADER_BYTES, RescueAdmission.MAX_CHECKPOINT_BYTES)
        maximums.indices.forEach { field ->
            val input = safe.copyOf(); input[field] = maximums[field] + 1
            assertThrows(RescueCapacityException::class.java) {
                RescueAdmission.requireSafe(input[0], input[1], input[2], input[3], input[4], ampleHeap)
            }
        }
    }

    @Test fun hostileOrInconsistentSizesCannotOverflowBudget() {
        listOf(-1L, Long.MAX_VALUE).forEach { hostile ->
            assertThrows(RescueCapacityException::class.java) {
                RescueAdmission.requireSafe(1, hostile, 1, 1, 1, ampleHeap)
            }
        }
        assertThrows(RescueCapacityException::class.java) {
            RescueAdmission.requireSafe(1, 10, 11, 1, 1, ampleHeap)
        }
    }

    @Test fun heapBudgetIncludesFixedReserveAndDecodedCopies() {
        val minimum = 64L * 1024 * 1024 + 16 + 2048
        RescueAdmission.requireSafe(1, 1, 1, 0, 0, minimum)
        assertThrows(RescueCapacityException::class.java) {
            RescueAdmission.requireSafe(1, 1, 1, 0, 0, minimum - 1)
        }
    }

    @Test fun checkpointAndHeaderAreNotOmittedFromHeapBudget() {
        val oneMiB = 1024L * 1024
        val heap = 96L * oneMiB
        RescueAdmission.requireSafe(1, oneMiB, oneMiB, 0, 0, heap)
        assertThrows(RescueCapacityException::class.java) {
            RescueAdmission.requireSafe(1, oneMiB, oneMiB, oneMiB, oneMiB, heap)
        }
    }

    @Test fun refusalExplainsUnchangedRecordsWithoutClaimingACopyExists() {
        val failure = assertThrows(RescueCapacityException::class.java) {
            RescueAdmission.requireSafe(1, 1, 1, 1, 1, 0)
        }
        assertTrue(failure.publicMessage.contains("原记录未改动"))
        assertTrue(failure.publicMessage.contains("完整本地备份"))
        assertFalse(failure.publicMessage.contains("副本已保存"))
    }
}
