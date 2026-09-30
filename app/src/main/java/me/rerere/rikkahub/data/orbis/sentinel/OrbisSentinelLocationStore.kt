package me.rerere.rikkahub.data.orbis.sentinel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class SentinelTrip(
    val key: String, val ruleId: String, val sessionId: String,
    val origin: String = "安全区域", val destination: String = "安全区域",
    val departedAtMs: Long = 0, val away: Boolean = true, val reported: Boolean = false,
    val firstSentAtMs: Long? = null, val distanceAtMs: Long? = null,
    /** A completed/paused trip cannot be reopened by a delayed departure from that same session. */
    val closedAtMs: Long? = null,
)
@Serializable
data class SentinelLocationJob(
    val eventId: String, val ruleId: String, val tripKey: String, val kind: String,
    val dueAtMs: Long, val occurredAtMs: Long, val status: String = "pending",
)
@Serializable
private data class SentinelLocationState(
    val version: Int = 1, val seen: Set<String> = emptySet(),
    val trips: List<SentinelTrip> = emptyList(), val jobs: List<SentinelLocationJob> = emptyList(),
)

/** Coordinate-free local replacement for the old remote trip/report/distance jobs. */
class OrbisSentinelLocationStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var existed = false

    @Synchronized fun accept(ruleId: String, raw: String, now: Long, allowNotices: Boolean): Boolean {
        val event = json.parseToJsonElement(raw).jsonObject
        fun required(key: String) = event[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("invalid_location_event")
        val id = required("event_id")
        val session = required("away_session_id")
        val type = required("type")
        require(id.length <= 180 && session.length <= 180)
        val occurred = event["occurred_at"]?.jsonPrimitive?.longOrNull ?: error("invalid_location_time")
        require(occurred in 1..now + 300_000)
        val label = event["zone_label"]?.jsonPrimitive?.contentOrNull.orEmpty().filterNot { it.isISOControl() }.take(24)
            .ifBlank { "安全区域" }
        val override = event["reported_override"]?.jsonPrimitive?.booleanOrNull == true
        val before = load()
        val seenKey = sentinelIdentityHash("$ruleId\n$id")
        if (seenKey in before.seen) return false
        val key = sentinelIdentityHash("$ruleId\n$session")
        var trip = before.trips.firstOrNull { it.key == key } ?: SentinelTrip(key, ruleId, session, departedAtMs = occurred)
        var jobs = before.jobs
        fun cancelChecks() { jobs = jobs.map { if (it.tripKey == key && it.kind in CHECKS && it.status == "pending") it.copy(status = "cancelled") else it } }
        fun schedule(kind: String, at: Long) {
            if (!allowNotices) return
            val eventId = "geo:" + sentinelIdentityHash("$ruleId\n$session\n$kind")
            if (jobs.none { it.eventId == eventId }) jobs = jobs + SentinelLocationJob(eventId, ruleId, key, kind, at, occurred)
        }
        when (type) {
            "zone_exit_confirmed" -> {
                if (trip.closedAtMs == null) {
                    trip = trip.copy(origin = label, departedAtMs = occurred, away = true, reported = trip.reported || override)
                    if (trip.reported) cancelChecks() else schedule("initial", maxOf(now, occurred))
                }
            }
            "distance_tier_crossed" -> {
                trip = trip.copy(distanceAtMs = occurred)
                if (trip.away && !trip.reported && trip.firstSentAtMs != null) schedule("distance", maxOf(now, occurred, checkNotNull(trip.firstSentAtMs) + SECOND_DELAY))
            }
            "zone_enter_confirmed", "offline_trip_summary" -> {
                val wasClosed = trip.closedAtMs != null
                trip = trip.copy(away = false, destination = label, reported = trip.reported || override,
                    closedAtMs = maxOf(trip.closedAtMs ?: 0, occurred))
                cancelChecks()
                if (!wasClosed) schedule(if (type == "zone_enter_confirmed") "arrival" else "offline", now)
            }
            "report_acknowledged" -> {
                trip = trip.copy(reported = true)
                cancelChecks()
                if (trip.firstSentAtMs != null) schedule("correction", now)
            }
            "tracking_paused", "location_degraded" -> {
                // Privacy stop cancels pending speculative safety notices; degraded fixes are diagnostics.
                if (type == "tracking_paused") {
                    trip = trip.copy(away = false, closedAtMs = occurred)
                    cancelChecks()
                }
            }
            else -> error("invalid_location_type")
        }
        save(before.copy(seen = before.seen + seenKey, trips = before.trips.filterNot { it.key == key } + trip, jobs = jobs))
        return true
    }

    @Synchronized fun due(now: Long): List<SentinelLocationJob> = load().jobs.filter { it.status == "pending" && it.dueAtMs <= now }
    @Synchronized fun trip(job: SentinelLocationJob): SentinelTrip? = load().trips.firstOrNull { it.key == job.tripKey }

    @Synchronized fun mark(job: SentinelLocationJob, status: String, now: Long) {
        require(status in setOf("accepted", "cancelled", "reported", "unknown"))
        val before = load()
        val existing = before.jobs.firstOrNull { it.eventId == job.eventId } ?: return
        if (existing.status != "pending") return
        var jobs = before.jobs.map { if (it.eventId == job.eventId) it.copy(status = status) else it }
        var trips = before.trips
        val trip = before.trips.firstOrNull { it.key == job.tripKey }
        if (trip != null) {
            var updated = trip
            if (status == "reported") {
                updated = trip.copy(reported = true)
                jobs = jobs.map { if (it.tripKey == trip.key && it.kind in CHECKS && it.status == "pending") it.copy(status = "reported") else it }
            } else if (status == "accepted" && job.kind == "initial") {
                updated = trip.copy(firstSentAtMs = now)
                if (trip.distanceAtMs != null && trip.away && !trip.reported) {
                    val id = "geo:" + sentinelIdentityHash("${trip.ruleId}\n${trip.sessionId}\ndistance")
                    if (jobs.none { it.eventId == id }) jobs += SentinelLocationJob(id, trip.ruleId, trip.key, "distance", now + SECOND_DELAY, checkNotNull(trip.distanceAtMs))
                }
            }
            trips = trips.map { if (it.key == trip.key) updated else it }
        }
        save(before.copy(trips = trips, jobs = jobs))
    }

    @Synchronized fun cancelInactive(activeRuleIds: Set<String>, resumedAtMs: Long?) {
        val before = load()
        val jobs = before.jobs.map {
            if (it.status == "pending" && (it.ruleId !in activeRuleIds || (resumedAtMs != null && it.occurredAtMs < resumedAtMs))) it.copy(status = "cancelled") else it
        }
        if (jobs != before.jobs) save(before.copy(jobs = jobs))
    }

    private fun load(): SentinelLocationState {
        val raw = read() ?: run { check(!existed) { "sentinel_locations_disappeared" }; return SentinelLocationState() }
        val state = json.decodeFromString<SentinelLocationState>(raw)
        require(state.version == 1 && state.jobs.map { it.eventId }.distinct().size == state.jobs.size)
        existed = true
        return state
    }
    private fun save(state: SentinelLocationState) {
        val encoded = json.encodeToString(state)
        write(encoded)
        check(read() == encoded) { "sentinel_locations_write_unverified" }
        existed = true
    }
    companion object {
        const val SECOND_DELAY = 15 * 60_000L
        val CHECKS = setOf("initial", "distance")
    }
}

/** Human messages only are passed here; sentinel/assistant text must never count as reporting. */
fun sentinelHumanReported(messages: List<String>): Boolean {
    val negative = listOf("我.*(不出门了|不出去了|不去了|不走了)", "我.*取消.*(出门|外出|行程|返程)",
        "如果我.*(出门|外出|回家|返程)", "假如我.*(出门|外出|回家|返程)", "不是我.*出门",
        "我.*(出门|外出|回家|返程).*(吗|么|没|没有|\\?|？)$").map(::Regex)
    val positive = listOf("我(已经|刚刚|刚|现在|已)?(出门|出发|离开家|离开学校|离开宿舍)了?",
        "我(已经|刚刚|刚|现在|已)?(到家|到学校|到宿舍|到公司|到了)了?", "我(已经|现在)?在外面",
        "我(今天|现在|一会儿|等会儿|待会儿|马上|稍后)?(要|准备|打算|计划|会)(出门|出发|离开|去学校|去公司|回家|回学校|回宿舍)",
        "我(一会儿|等会儿|待会儿|马上|稍后)(出门|出发|去学校|去公司|回家|回学校|回宿舍)",
        "我已(经)?向.*(报备|说明|告诉)", "我已(经)?(告诉|告知)(你|对方|联系人)").map(::Regex)
    var remaining = 6000
    for (raw in messages.takeLast(20).asReversed()) {
        if (remaining == 0) break
        val bounded = raw.takeLast(remaining)
        remaining -= bounded.length
        val text = bounded.replace(Regex("\\s+"), "")
        if (Regex("我.*(不出门了|不出去了|不去了|不走了)|我.*取消.*(出门|外出|行程|返程)").containsMatchIn(text)) return false
        if (negative.any { it.containsMatchIn(text) }) continue
        if (positive.any { it.containsMatchIn(text) }) return true
    }
    return false
}

fun sentinelLocationFacts(job: SentinelLocationJob, trip: SentinelTrip): String = when (job.kind) {
    "initial" -> "定位已确认用户离开「${trip.origin}」，当前对话中尚未核对到报备。"
    "distance" -> "用户仍在外且已越过人类设定的距离，尚未核对到报备；这是本次外出的最后一次距离提醒。"
    "arrival" -> "用户已到达「${trip.destination}」。"
    "offline" -> "离线期间完成了一次外出，现已到达「${trip.destination}」；已合并为一条播报，不补发过时的离开提醒。"
    "correction" -> "已收到本次外出的补充报备，后续提醒已取消。"
    else -> error("invalid_location_job")
}
