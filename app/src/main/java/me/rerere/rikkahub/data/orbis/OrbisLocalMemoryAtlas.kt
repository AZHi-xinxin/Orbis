package me.rerere.rikkahub.data.orbis

import java.time.Instant
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMetadata

/** A bounded UI projection, not a memory record or an ST response. No body/summary can enter it. */
internal data class OrbisLocalMemoryAtlas(
    override val stars: List<OrbisMemoryAtlas.Star>,
    override val edges: List<OrbisMemoryAtlas.Edge>,
    val metadata: List<OrbisMemoryMetadata>,
    val truncated: Boolean,
) : OrbisMemoryAtlas.Graph {
    override val seed: Int = 731
    override val isDemo: Boolean = false
    val source: String = "local_assistant_metadata"
}

internal val localMemoryAtlasStates = listOf("pinned", "conditional", "static", "paused")

internal fun localMemoryStateLabel(state: String): String = when (state) {
    "pinned" -> "常驻"
    "conditional" -> "条件浮现"
    "static" -> "仅存"
    "paused" -> "已暂停浮现"
    else -> "未知状态"
}

/** Equal stored tags only. A sparse chain avoids quadratic cliques; edges imply no semantics. */
internal fun localMemoryAtlas(
    rows: List<OrbisMemoryMetadata>,
    availableCount: Int,
    maxStars: Int = 500,
    maxEdges: Int = 2000,
): OrbisLocalMemoryAtlas {
    require(maxStars in 1..2000 && maxEdges in 0..5000 && availableCount >= 0)
    val active = rows.filterNot { it.deleted }
    require(active.map { it.id }.distinct().size == active.size) { "invalid_local_memory_metadata" }
    val shown = active.take(maxStars).map { it.copy(tags = it.tags.toList()) }.sortedBy { it.id }
    val stars = shown.map { row ->
        require(row.id.isNotBlank() && row.state in localMemoryAtlasStates &&
            row.createdAt in 0L..253402300799000L && row.updatedAt in 0L..253402300799000L && row.revision >= 0) {
            "invalid_local_memory_metadata"
        }
        OrbisMemoryAtlas.Star(row.id, row.state, Instant.ofEpochMilli(row.createdAt).toString())
    }
    val previousByTag = mutableMapOf<String, Int>()
    val pairs = linkedSetOf<Pair<Int, Int>>()
    shown.forEachIndexed { index, row ->
        row.tags.filter { it.isNotBlank() }.distinct().sorted().forEach { tag ->
            val previous = previousByTag.put(tag, index)
            if (previous != null && pairs.size < maxEdges) pairs += previous to index
        }
    }
    return OrbisLocalMemoryAtlas(stars, pairs.map { (a, b) -> OrbisMemoryAtlas.Edge(a, b) }, shown,
        availableCount > shown.size || active.size > shown.size)
}
