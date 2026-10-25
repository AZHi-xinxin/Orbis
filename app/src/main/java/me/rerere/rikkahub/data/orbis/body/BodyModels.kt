package me.rerere.rikkahub.data.orbis.body

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** A configurable expression simulator, never a physiological measurement or inferred feeling. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class BodyDimension(
    val id: String, val label: String, val scope: String = "session",
    val baseline: Double = 50.0, val min: Double = 0.0, val max: Double = 100.0,
    val maxDelta: Double = 10.0, val halfLifeMinutes: Double = 30.0,
    @SerialName("private") @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val isPrivate: Boolean = false,
)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class BodyRule(val event: String, val deltas: Map<String, Double>, val keywords: List<String> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val synonyms: List<String> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val weight: Double = 1.0)
@Serializable data class BodyAbsence(val graceMinutes: Int = 30, val capHours: Int = 24,
    val perHour: Map<String, Double> = emptyMap(), val returnEvent: String? = null)
/** Optional author-defined calming cues. No built-in vocabulary, affected dimensions or valence. */
@Serializable data class BodyCooling(val keywords: List<String> = emptyList(), val synonyms: List<String> = emptyList(),
    val event: String? = null, val blockedEvents: List<String> = emptyList(),
    val cooldownMinutes: Int = 5, val pauseAuto: Boolean = false)
/** Author-configured response pace; limits below bound storage and computation, not personality. */
@Serializable data class BodyAutoPolicy(val maxConcentration: Double = 3.0,
    val windowMinutes: Int = 10, val maxEventsPerWindow: Int = 3)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class BodyDefinition(val dimensions: List<BodyDimension> = emptyList(),
    val rules: List<BodyRule> = emptyList(), val absence: BodyAbsence = BodyAbsence(),
    // Existing documents remain manual until the assistant explicitly publishes an auto definition.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val triggerMode: String = "manual",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val cooling: BodyCooling = BodyCooling(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val autoPolicy: BodyAutoPolicy = BodyAutoPolicy())
@Serializable data class BodyVersion(val version: Int, val effectiveAt: Long, val definition: BodyDefinition)
@Serializable data class BodyClock(val process: String = "", val activeMs: Long = 0, val wallMs: Long = 0)
/** Durable elapsed-time origin; session clocks never borrow another chat's human activity. */
@Serializable data class BodyTimeline(val at: Long = 0,
    val humanAt: Map<String, Long> = emptyMap(), val sharedHumanAt: Long = 0)
@Serializable data class BodySession(val values: Map<String, Double> = emptyMap(),
    val peaks: Map<String, Double> = emptyMap(), val visible: Boolean = false, val selfReport: String = "",
    val privateShared: Boolean = false)
@Serializable data class BodySnapshot(val persistent: Map<String, Double> = emptyMap(),
    val peaks: Map<String, Double> = emptyMap(), val sessions: Map<String, BodySession> = emptyMap())
@Serializable data class BodyLog(val seq: Long, val at: Long, val version: Int, val conversation: String,
    val event: String, val strength: Int = 0, val gapMs: Long = 0,
    val values: Map<String, Double> = emptyMap(), val concentration: Double? = null)
@Serializable data class BodyUndo(val seq: Long, val version: Int, val conversation: String,
    val before: BodySnapshot, val clock: BodyClock, val timeline: BodyTimeline? = null)
@Serializable data class BodyState(val schema: Int = 1, val revision: Long = 0, val running: Boolean = false,
    val versions: List<BodyVersion> = emptyList(), val draft: BodyDefinition? = null,
    val clock: BodyClock = BodyClock(), val snapshot: BodySnapshot = BodySnapshot(),
    val lastHumanAt: Long = 0, val receipts: Map<String, String> = emptyMap(),
    val logs: List<BodyLog> = emptyList(), val undo: BodyUndo? = null,
    val checkpointSeq: Long = 0,
    val resumedProcess: String = "",
    // Sticky classification, including drafts: removing/readding a draft must not unveil a dimension.
    val privateDimensionIds: Set<String> = emptySet(),
    val auto: BodyAutoState = BodyAutoState(),
    val timeline: BodyTimeline? = null,
)

object BodyRules {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    const val MAX_BYTES = 4 * 1024 * 1024
    private val safeId = Regex("[A-Za-z0-9_-]{1,80}")
    private val sha256 = Regex("[a-f0-9]{64}")
    fun id(value: String) = safeId.matches(value)
    fun privateIds(state: BodyState): Set<String> = state.privateDimensionIds +
        state.versions.flatMap { it.definition.dimensions }.filter { it.isPrivate }.map { it.id } +
        state.draft?.dimensions.orEmpty().filter { it.isPrivate }.map { it.id }
    fun validate(def: BodyDefinition) {
        require(def.autoPolicy.maxConcentration.isFinite() && def.autoPolicy.maxConcentration in 0.1..10.0 &&
            def.autoPolicy.windowMinutes in 1..1440 && def.autoPolicy.maxEventsPerWindow in 1..60) { "body_auto_policy_invalid" }
        require(def.triggerMode in setOf("manual", "auto")) { "body_trigger_mode_invalid" }
        require(def.dimensions.size <= 16 && def.rules.size <= 64) { "body_definition_limit" }
        require(def.dimensions.map { it.id }.distinct().size == def.dimensions.size) { "body_duplicate_dimension" }
        def.dimensions.forEach { d ->
            require(id(d.id) && d.label.isNotBlank() && d.label.length <= 32 && d.label.none(Char::isISOControl)) { "body_dimension_label" }
            require(d.scope in setOf("session", "persistent")) { "body_dimension_scope" }
            require(listOf(d.min, d.max, d.baseline, d.maxDelta, d.halfLifeMinutes).all(Double::isFinite)) { "body_nonfinite" }
            require(d.min >= -10000 && d.max <= 10000 && d.min < d.max && d.baseline in d.min..d.max &&
                d.maxDelta > 0 && d.maxDelta <= d.max - d.min && d.halfLifeMinutes in 0.01..525600.0) { "body_dimension_bounds" }
        }
        val ids = def.dimensions.map { it.id }.toSet()
        fun checkDelta(map: Map<String, Double>) {
            require(map.keys.all { it in ids } && map.values.all { it.isFinite() && it in -20000.0..20000.0 }) { "body_delta_invalid" }
        }
        require(def.rules.map { it.event }.distinct().size == def.rules.size) { "body_duplicate_event" }
        def.rules.forEach {
            require(id(it.event) && !it.event.startsWith("host_") && it.deltas.isNotEmpty()) { "body_event_invalid" }
            checkDelta(it.deltas)
            require(it.keywords.size <= 8 && it.keywords.all { word -> word.isNotBlank() && word.length <= 64 && word.none(Char::isISOControl) }) { "body_keyword_limit" }
            require(it.synonyms.size <= 24 && it.synonyms.all { word -> word.isNotBlank() && word.length <= 64 && word.none(Char::isISOControl) }) { "body_keyword_limit" }
            require(it.weight.isFinite() && it.weight in 0.1..3.0) { "body_weight_invalid" }
        }
        require(def.absence.graceMinutes in 1..1440 && def.absence.capHours in 1..24) { "body_absence_bounds" }
        checkDelta(def.absence.perHour)
        require(def.absence.returnEvent == null || def.rules.any { it.event == def.absence.returnEvent }) { "body_return_event" }
        val events = def.rules.map { it.event }.toSet()
        require(def.cooling.event == null || def.cooling.event in events) { "body_cooling_event_invalid" }
        require(def.cooling.blockedEvents.size <= 64 && def.cooling.blockedEvents.all { it in events } &&
            def.cooling.cooldownMinutes in 1..1440) { "body_cooling_invalid" }
        require(def.cooling.keywords.size <= 8 && def.cooling.synonyms.size <= 24 &&
            (def.cooling.keywords + def.cooling.synonyms).all { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }) { "body_keyword_limit" }
    }
    fun decay(value: Double, dim: BodyDimension, activeMs: Long): Double {
        require(value.isFinite()) { "body_nonfinite" }
        val safe = value.coerceIn(dim.min, dim.max)
        return (dim.baseline + (safe - dim.baseline) * 0.5.pow(activeMs.coerceAtLeast(0).toDouble() /
            (dim.halfLifeMinutes * 60000.0))).coerceIn(dim.min, dim.max)
    }
    fun delta(value: Double, amount: Double, dim: BodyDimension): Double {
        require(value.isFinite() && amount.isFinite()) { "body_nonfinite" }
        // Normalize first, limit the increment second, hard clamp LAST (not two incompatible clamps).
        return (value.coerceIn(dim.min, dim.max) + amount.coerceIn(-dim.maxDelta, dim.maxDelta)).coerceIn(dim.min, dim.max)
    }
    fun elapsed(before: BodyClock, now: BodyClock): Long =
        if (before.process == now.process && now.activeMs >= before.activeMs) now.activeMs - before.activeMs else 0
    fun gap(before: Long, now: Long): Long = if (before <= 0 || now <= before) 0 else
        (now - before).coerceIn(0, 25L * 3600000)
    fun values(snapshot: BodySnapshot, conversation: String, def: BodyDefinition): Map<String, Double> =
        def.dimensions.associate { d -> d.id to (if (d.scope == "persistent") snapshot.persistent[d.id]
            else snapshot.sessions[conversation]?.values?.get(d.id)).let { it ?: d.baseline } }
    fun materialize(snapshot: BodySnapshot, def: BodyDefinition, ms: Long): BodySnapshot {
        val persistent = def.dimensions.filter { it.scope == "persistent" }
        val local = def.dimensions.filter { it.scope == "session" }
        val values = persistent.associate { it.id to decay(snapshot.persistent[it.id] ?: it.baseline, it, ms) }
        fun peaks(old: Map<String, Double>, values: Map<String, Double>) = values.mapValues { (id, value) -> maxOf(old[id] ?: value, value) }
        return snapshot.copy(persistent = values, peaks = peaks(snapshot.peaks, values),
            sessions = snapshot.sessions.mapValues { (_, session) ->
                val current = local.associate { it.id to decay(session.values[it.id] ?: it.baseline, it, ms) }
                session.copy(values = current, peaks = peaks(session.peaks, current))
            })
    }
    fun apply(snapshot: BodySnapshot, conversation: String, def: BodyDefinition, deltas: Map<String, Double>): BodySnapshot {
        require(id(conversation)) { "body_conversation_invalid" }
        require(conversation in snapshot.sessions || snapshot.sessions.size < 128) { "body_session_limit" }
        val current = snapshot.sessions[conversation] ?: BodySession()
        val persistent = snapshot.persistent.toMutableMap(); val peaks = snapshot.peaks.toMutableMap()
        val local = current.values.toMutableMap(); val localPeaks = current.peaks.toMutableMap()
        def.dimensions.forEach { d ->
            val target = if (d.scope == "persistent") persistent else local
            val maximum = if (d.scope == "persistent") peaks else localPeaks
            val value = delta(target[d.id] ?: d.baseline, deltas[d.id] ?: 0.0, d)
            target[d.id] = value; maximum[d.id] = maxOf(maximum[d.id] ?: d.baseline, value)
        }
        return snapshot.copy(persistent = persistent, peaks = peaks,
            sessions = snapshot.sessions + (conversation to current.copy(values = local, peaks = localPeaks)))
    }
    fun validate(state: BodyState) {
        BodyAutoTrigger.validate(state.auto)
        require(state.schema == 1 && state.revision >= 0 && state.versions.size <= 64 && state.receipts.size <= 16000 &&
            state.logs.size <= 2000 && state.snapshot.sessions.size <= 128) { "body_storage_invalid" }
        require(state.versions.map { it.version } == (1..state.versions.size).toList()) { "body_version_invalid" }
        state.versions.forEach { validate(it.definition) }; state.draft?.let(::validate)
        require(state.privateDimensionIds.size <= 1024 && state.privateDimensionIds.all(::id)) { "body_private_classification_invalid" }
        val locked = mutableSetOf<String>()
        (state.versions.map { it.definition } + listOfNotNull(state.draft)).forEach { definition ->
            require(definition.dimensions.none { it.id in locked && !it.isPrivate }) { "body_private_downgrade_forbidden" }
            locked += definition.dimensions.filter { it.isPrivate }.map { it.id }
        }
        require(!state.running || state.versions.lastOrNull()?.definition?.dimensions?.isNotEmpty() == true) { "body_definition_empty" }
        require(state.clock.activeMs >= 0 && state.clock.wallMs >= 0 && state.lastHumanAt >= 0) { "body_clock_invalid" }
        require(state.clock.process.length <= 80 && state.resumedProcess.length <= 80 && state.checkpointSeq in 0..state.revision) { "body_storage_invalid" }
        fun checkTimeline(t: BodyTimeline) {
            require(t.at >= 0 && t.sharedHumanAt in 0..t.at && t.humanAt.size <= 128 &&
                t.humanAt.all { (key, at) -> id(key) && at in 0..t.at }) { "body_timeline_invalid" }
        }
        state.timeline?.let(::checkTimeline)
        fun checkValues(values: Map<String, Double>, dimensions: List<BodyDimension>) {
            require(values.all { (key, value) -> value.isFinite() && dimensions.any { it.id == key && value in it.min..it.max } }) { "body_storage_bounds" }
        }
        fun checkSnapshot(snapshot: BodySnapshot, def: BodyDefinition) {
            require(snapshot.sessions.size <= 128 && snapshot.sessions.keys.all(::id)) { "body_storage_invalid" }
            val persistent = def.dimensions.filter { it.scope == "persistent" }
            val local = def.dimensions.filter { it.scope == "session" }
            checkValues(snapshot.persistent, persistent); checkValues(snapshot.peaks, persistent)
            snapshot.sessions.values.forEach {
                require(it.selfReport.length <= 300) { "body_self_report_limit" }
                checkValues(it.values, local); checkValues(it.peaks, local)
            }
        }
        checkSnapshot(state.snapshot, state.versions.lastOrNull()?.definition ?: BodyDefinition())
        require(state.receipts.all { (key,value) -> sha256.matches(key) && sha256.matches(value.substringBefore(':')) &&
            value.substringAfter(':', "").toLongOrNull()?.let { it in 1..state.revision } == true }) { "body_receipt_invalid" }
        require(state.logs.zipWithNext().all { (a,b) -> a.seq < b.seq } && state.logs.all {
            it.seq in 1..state.revision && it.seq > state.checkpointSeq && it.version in 0..state.versions.size &&
                it.at >= 0 && id(it.conversation) && id(it.event) && it.strength in 0..3 && it.gapMs in 0..90_000_000 &&
                (it.concentration == null || it.concentration.isFinite() && it.concentration in 0.0..10.0)
        }) { "body_log_invalid" }
        state.logs.forEach { checkValues(it.values, state.versions.getOrNull(it.version - 1)?.definition?.dimensions.orEmpty()) }
        state.undo?.let {
            it.timeline?.let(::checkTimeline)
            require(it.seq == state.revision && it.version == state.versions.lastOrNull()?.version && id(it.conversation) &&
                it.clock.activeMs >= 0 && it.clock.wallMs >= 0 && it.clock.process.length <= 80) { "body_undo_invalid" }
            checkSnapshot(it.before, state.versions.last().definition)
        }
    }
}
