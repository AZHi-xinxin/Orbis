package me.rerere.rikkahub.data.orbis.body

import kotlin.math.exp
import kotlin.math.ln

/** Pure analytic integration: opening/refreshing a panel cannot multiply time-driven changes. */
object BodyTime {
    fun anchor(state: BodyState, at: Long): BodyTimeline = BodyTimeline(at,
        state.snapshot.sessions.keys.associateWith { at }, at)

    fun reset(state: BodyState): BodyState = state.copy(timeline = anchor(state, state.clock.wallMs))

    fun human(state: BodyState, conversation: String): BodyState {
        val t = state.timeline ?: anchor(state, state.clock.wallMs)
        return state.copy(timeline = t.copy(humanAt = t.humanAt + (conversation to t.at), sharedHumanAt = t.at))
    }

    fun gap(state: BodyState, conversation: String, now: Long): Long =
        BodyRules.gap(state.timeline?.humanAt?.get(conversation) ?: 0, now)

    fun settle(old: BodyState, clock: BodyClock): BodyState {
        val t = old.timeline ?: anchor(old, clock.wallMs)
        // A backwards clock is held at its last committed point, never counted a second time.
        val at = maxOf(t.at, clock.wallMs)
        val nextClock = clock.copy(wallMs = at)
        val def = old.versions.lastOrNull()?.definition
        if (!old.running || def == null) return old.copy(clock = nextClock,
            timeline = t.copy(at = at))
        fun integrate(value: Double, d: BodyDimension, humanAt: Long?): Pair<Double, Double> {
            if (at <= t.at) return value to value
            val rate = def.absence.perHour[d.id] ?: 0.0
            fun plus(a: Long, b: Long) = a + minOf(b, Long.MAX_VALUE - a)
            val start = humanAt?.let { plus(it, def.absence.graceMinutes * 60_000L) }
            val end = start?.let { plus(it, def.absence.capHours * 3_600_000L) }
            val points = (listOf(t.at, at) + listOfNotNull(start, end).filter { it > t.at && it < at }).sorted()
            var current = value.coerceIn(d.min, d.max)
            var peak = current
            val k = ln(2.0) / (d.halfLifeMinutes * 60_000.0)
            for ((from, to) in points.zipWithNext()) {
                val drift = if (start != null && end != null && from >= start && from < end) rate / 3_600_000.0 else 0.0
                val decay = exp(-k * (to - from).toDouble())
                current = (d.baseline + (current - d.baseline) * decay + drift / k * (1 - decay))
                    .coerceIn(d.min, d.max)
                peak = maxOf(peak, current)
            }
            return current to peak
        }
        val persistentDims = def.dimensions.filter { it.scope == "persistent" }
        val sessionDims = def.dimensions.filter { it.scope == "session" }
        val persistentResult = persistentDims.associate { d -> d.id to integrate(
            old.snapshot.persistent[d.id] ?: d.baseline, d, t.sharedHumanAt.takeIf { it > 0 }) }
        fun peaks(before: Map<String, Double>, values: Map<String, Pair<Double, Double>>) = values.mapValues { (id, value) ->
            maxOf(before[id] ?: value.second, value.second)
        }
        val sessions = old.snapshot.sessions.mapValues { (id, session) ->
            val result = sessionDims.associate { d -> d.id to integrate(
                session.values[d.id] ?: d.baseline, d, t.humanAt[id]) }
            session.copy(values = result.mapValues { it.value.first }, peaks = peaks(session.peaks, result))
        }
        return old.copy(clock = nextClock, timeline = t.copy(at = at), snapshot = old.snapshot.copy(
            persistent = persistentResult.mapValues { it.value.first }, peaks = peaks(old.snapshot.peaks, persistentResult), sessions = sessions))
    }
}
