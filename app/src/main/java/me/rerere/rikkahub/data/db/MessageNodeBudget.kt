package me.rerere.rikkahub.data.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** A capacity refusal is not evidence that an already-started tool failed. */
internal class MessageNodeCapacityException(val code: String) : IllegalStateException(
    "本轮记录达到安全容量，已停止继续，超出的片段没有确认为已保存。此前已确认的记录和格子草稿保留；请核对工具状态后，在新一轮继续草稿，不要重做结果未知的工具。",
)

/** The exact same UTF-8 JSON codec as SQLite persistence, including every branch and escaping. */
@OptIn(ExperimentalSerializationApi::class)
internal object MessageNodeBudget {
    const val MAX_NODE_BYTES = 768 * 1024
    const val SOFT_NODE_BYTES = 512 * 1024
    const val MAX_GENERATION_NODE_BYTES = 704 * 1024
    const val MAX_TAIL_BYTES = 6 * 1024 * 1024
    const val SOFT_TAIL_BYTES = 4 * 1024 * 1024

    fun measureNode(messages: List<UIMessage>, limit: Int = MAX_NODE_BYTES): Int {
        val output = CountingOutput(limit)
        JsonInstant.encodeToStream(messages, output)
        return output.count
    }

    fun encodeNode(messages: List<UIMessage>, limit: Int = MAX_NODE_BYTES): String {
        val output = BoundedBytes(limit)
        JsonInstant.encodeToStream(messages, output)
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    /** Caller passes ONLY the mutable tail: historical prefix/suffix are not a generation budget. */
    fun admitTail(nodes: List<MessageNode>): Boolean {
        var total = 0L
        var recoveryTotal = 0L
        var softReached = false
        nodes.forEach { node ->
            val bytes = measureNode(node.messages, MAX_GENERATION_NODE_BYTES)
            total += bytes
            if (total > MAX_TAIL_BYTES) throw MessageNodeCapacityException("generation_tail_capacity")
            // Mirror Journal.recoveryTail without altering the actual tool/approval/receipt.
            // A provider can issue many pending tools; a fixed reserve alone is not proof.
            val recovery = node.messages.mapIndexed { index, message ->
                if (index != node.selectIndex) message else message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) part.withHostToolFailure(HostToolFailure.INTERRUPTED) else part
                })
            }
            recoveryTotal += measureNode(recovery)
            if (recoveryTotal > MAX_TAIL_BYTES) throw MessageNodeCapacityException("generation_recovery_capacity")
            softReached = softReached || bytes >= SOFT_NODE_BYTES
        }
        return softReached || total >= SOFT_TAIL_BYTES
    }

    private class CountingOutput(private val limit: Int) : OutputStream() {
        var count = 0
            private set
        init { require(limit >= 0) }
        override fun write(value: Int) { reserve(1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length) }
        private fun reserve(length: Int) {
            if (length > limit - count) throw MessageNodeCapacityException("message_node_capacity")
            count += length
        }
    }

    private class BoundedBytes(private val limit: Int) : ByteArrayOutputStream(minOf(limit, 65536)) {
        init { require(limit >= 0) }
        override fun write(value: Int) {
            if (count >= limit) throw MessageNodeCapacityException("message_node_capacity")
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > limit - count) throw MessageNodeCapacityException("message_node_capacity")
            super.write(bytes, offset, length)
        }
    }
}
