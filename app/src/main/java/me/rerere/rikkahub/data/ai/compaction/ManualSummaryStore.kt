package me.rerere.rikkahub.data.ai.compaction

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/** App-private draft; never saved in an Activity Bundle or exported to a provider as a whole. */
class ManualSummaryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class Stamp(val time: FileTime, val bytes: Long, val key: String?)
    private fun stamp(file: File): Stamp = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let {
        Stamp(it.lastModifiedTime(), it.size(), it.fileKey()?.toString())
    }
    private val plans = mutableMapOf<String, Pair<Stamp, ManualSummaryDraft>>()
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }
    private fun parts(id: String) = File(directory, "${file(id).nameWithoutExtension}.parts")
    private fun readPlan(id: String): ManualSummaryDraft? {
        val f = file(id)
        if (!f.exists()) { plans.remove(id); return null }
        val stamp = stamp(f)
        plans[id]?.takeIf { it.first == stamp }?.let { return it.second }
        require(f.length() <= 96L * 1024 * 1024) { "摘要草稿过大，未加载。" }
        return json.decodeFromString<ManualSummaryDraft>(f.readText()).validate().also {
            require(it.conversationId == id && it.summaries.all { summary -> summary == null })
            plans[id] = stamp to it
        }
    }
    @Synchronized fun read(id: String): ManualSummaryDraft? {
        val plan = readPlan(id) ?: return null
        return plan.copy(summaries = plan.chunks.indices.map { index ->
            val part = File(parts(id), "$index.txt")
            if (part.exists()) {
                require(part.length() <= 400_000) { "摘要片段过大，未加载。" }
                part.readText()
            } else null
        }).validate()
    }
    @Synchronized fun write(draft: ManualSummaryDraft) {
        draft.validate()
        check(directory.isDirectory || directory.mkdirs())
        val plan = draft.copy(summaries = List(draft.chunks.size) { null })
        val stored = readPlan(draft.conversationId)
        require(stored == null || stored == plan) { "已有另一份摘要计划，未覆盖草稿。" }
        if (stored == null) {
            atomic(file(draft.conversationId), json.encodeToString(ManualSummaryDraft.serializer(), plan))
            plans[draft.conversationId] = stamp(file(draft.conversationId)) to plan
        }
        val parts = parts(draft.conversationId)
        check(parts.isDirectory || parts.mkdirs())
        // Original source is written once. Checkpoints only replace changed completed segments,
        // avoiding N rewrites of a potentially multi-megabyte conversation on a phone.
        draft.summaries.forEachIndexed { index, text ->
            if (text != null) {
                val target = File(parts, "$index.txt")
                if (!target.exists() || target.readText() != text) atomic(target, text)
            }
        }
    }
    @Synchronized fun clear(id: String) {
        val f = file(id)
        val parts = parts(id)
        check(!parts.exists() || parts.deleteRecursively()) { "摘要片段未能清除。" }
        check(!f.exists() || f.delete()) { "摘要草稿未能清除。" }
        plans.remove(id)
    }
    private fun atomic(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
