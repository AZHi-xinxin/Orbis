package me.rerere.rikkahub.data.orbis.sentinel

/**
 * A configured native geofence needs the independent location outbox, not a VPS credential.
 * Other rules must never claim the shared legacy visual/rest timer. Persisted claims stay owned
 * after a pause/delete so an uncertain local acceptance cannot fall back to the old sender.
 * This selects delivery only; it does not enable tracking, permissions or the human master.
 */
internal fun sentinelOwnedSources(
    persisted: Set<String>,
    allMigrated: Boolean,
    known: Set<String>,
    state: OrbisSentinelState = OrbisSentinelState(),
): Set<String> {
    val nativeLocation = if (state.enabled && state.rules.any { it.enabled && it.type == OrbisSentinelType.GEOFENCE })
        setOf("lc_location") else emptySet()
    return (persisted + nativeLocation + if (allMigrated) known else emptySet()).intersect(known)
}
