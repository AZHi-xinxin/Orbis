package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/** Explicit local opt-in only. No private filenames/IDs/titles/content enter logs or fixtures. */
class DeepSeekLocalExportProbeTest {
    @Test fun localReadOnlySizeAndMemoryProbe() {
        val source = System.getenv("ORBIS_DEEPSEEK_LOCAL_PROBE_ZIP")
        assumeTrue("Private archive probe is disabled by default", !source.isNullOrBlank())
        val file = File(checkNotNull(source))
        val started = System.nanoTime()
        val runtime = Runtime.getRuntime()
        var maxHeap = 0L
        fun sampleHeap() { maxHeap = maxOf(maxHeap, runtime.totalMemory() - runtime.freeMemory()) }
        val preview = DeepSeekArchive.inspect(file) { sampleHeap() }
        var messages = 0
        var serializedBytes = 0L
        var largestNodeBytes = 0
        var largestPath = 0
        var maxSourceNodes = 0
        DeepSeekArchive.open(file) { sampleHeap() }.use { archive ->
            for (conversation in archive.conversations()) {
                maxSourceNodes = maxOf(maxSourceNodes, conversation.nodes.size)
                val projected = DeepSeekChatImporter.convert(conversation, conversation.selectedLeafId,
                    Uuid.parse("11111111-1111-4111-8111-111111111111")) { sampleHeap() }.conversation
                largestPath = maxOf(largestPath, projected.messageNodes.size)
                messages += projected.messageNodes.size
                projected.messageNodes.forEach { node ->
                    val size = JsonInstant.encodeToString(node.messages).toByteArray(Charsets.UTF_8).size
                    serializedBytes += size
                    largestNodeBytes = maxOf(largestNodeBytes, size)
                }
                sampleHeap()
            }
        }
        assertTrue(preview.conversations.isNotEmpty())
        assertTrue(largestNodeBytes <= DeepSeekChatImporter.MAX_NODE_JSON_BYTES)
        println("DEEPSEEK_PRIVATE_PROBE conversations=${preview.conversations.size} max_source_nodes=$maxSourceNodes " +
            "selected_messages=$messages max_selected_path=$largestPath max_node_json_bytes=$largestNodeBytes " +
            "total_selected_json_bytes=$serializedBytes elapsed_ms=${(System.nanoTime() - started) / 1_000_000} " +
            "sampled_used_heap_bytes=$maxHeap; contents_and_identifiers_not_logged=true")
    }
}
