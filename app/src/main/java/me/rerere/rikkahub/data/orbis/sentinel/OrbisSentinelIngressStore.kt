package me.rerere.rikkahub.data.orbis.sentinel

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SentinelIngressReceipt(
    val source: String,
    val eventId: String,
    val payloadHash: String,
    val occurredAtMs: Long,
    val receivedAtMs: Long,
    /** A crash-left processing record is uncertain, not permission to dispatch again. */
    val status: String = "processing",
    val delivered: Int = 0,
)

@Serializable
private data class SentinelIngressState(val version: Int = 1, val receipts: List<SentinelIngressReceipt> = emptyList())

/** Event metadata only: no coordinates, screenshot, authored prompt or credentials. */
class OrbisSentinelIngressStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var existed = false

    @Synchronized fun receipt(source: String, id: String): SentinelIngressReceipt? =
        load().receipts.firstOrNull { it.source == source && it.eventId == id }

    /** Returns existing receipts unchanged. Only duplicate=false authorizes first dispatch. */
    @Synchronized fun begin(source: String, id: String, payload: String, occurredAtMs: Long, nowMs: Long): Pair<SentinelIngressReceipt, Boolean> {
        require(source in setOf("touch", "lc_location", "lc_visual", "lc_rest", "lc_manual_test")) { "invalid_sentinel_ingress_source" }
        require(id.matches(Regex("[A-Za-z0-9:._-]{1,180}"))) { "invalid_event_id" }
        require(occurredAtMs in 1..(nowMs + 300_000)) { "invalid_event_time" }
        val before = load()
        val hash = sentinelIdentityHash(payload)
        before.receipts.firstOrNull { it.source == source && it.eventId == id }?.let {
            require(it.payloadHash == hash && it.occurredAtMs == occurredAtMs) { "event_id_conflict" }
            return it to true
        }
        val entry = SentinelIngressReceipt(source, id, hash, occurredAtMs, nowMs)
        save(before.copy(receipts = before.receipts + entry))
        return entry to false
    }

    @Synchronized fun finish(source: String, id: String, status: String, delivered: Int = 0): SentinelIngressReceipt {
        require(status in setOf("accepted", "suppressed", "no_rules", "unknown", "rejected") && delivered >= 0)
        val before = load()
        val entry = checkNotNull(before.receipts.firstOrNull { it.source == source && it.eventId == id })
        if (entry.status != "processing") return entry
        val result = entry.copy(status = status, delivered = delivered)
        save(before.copy(receipts = before.receipts.map { if (it.source == source && it.eventId == id) result else it }))
        return result
    }

    /** A durable downstream execution receipt can resolve an ambiguous result, never re-send it. */
    @Synchronized fun reconcile(source: String, id: String, delivered: Int): SentinelIngressReceipt {
        require(delivered >= 0)
        val before = load()
        val old = checkNotNull(before.receipts.firstOrNull { it.source == source && it.eventId == id })
        if (old.status !in setOf("processing", "unknown")) return old
        val result = old.copy(status = if (delivered > 0) "accepted" else "suppressed", delivered = delivered)
        save(before.copy(receipts = before.receipts.map { if (it.source == source && it.eventId == id) result else it }))
        return result
    }

    private fun load(): SentinelIngressState {
        val raw = read() ?: run { check(!existed) { "sentinel_ingress_disappeared" }; return SentinelIngressState() }
        val value = json.decodeFromString<SentinelIngressState>(raw)
        require(value.version == 1 && value.receipts.map { it.source to it.eventId }.distinct().size == value.receipts.size)
        existed = true
        return value
    }
    private fun save(state: SentinelIngressState) {
        val encoded = json.encodeToString(state)
        write(encoded)
        check(read() == encoded) { "sentinel_ingress_write_unverified" }
        existed = true
    }
}

fun sentinelIdentityHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
