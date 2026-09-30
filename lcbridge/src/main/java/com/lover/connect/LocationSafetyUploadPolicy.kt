package com.lover.connect

enum class LocationUploadDisposition {
    DELIVERED,
    RETRY,
    REJECTED
}

/** Selected host ownership is sticky for this attempt; an uncertain result retries the same ID. */
internal fun deliverLocationEvent(
    event: CompanionHostEvent,
    hostOwned: Boolean,
    legacy: () -> LocationUploadDisposition,
    dispatch: (CompanionHostEvent) -> CompanionHostEventResult = CompanionHostEvents::dispatch,
): LocationUploadDisposition {
    val result = try { dispatch(event) } catch (_: Exception) { CompanionHostEventResult.UNKNOWN }
    return when (result) {
        CompanionHostEventResult.ACCEPTED, CompanionHostEventResult.DUPLICATE -> LocationUploadDisposition.DELIVERED
        CompanionHostEventResult.REJECTED -> LocationUploadDisposition.REJECTED
        CompanionHostEventResult.UNKNOWN -> LocationUploadDisposition.RETRY
        CompanionHostEventResult.NOT_OWNED -> if (hostOwned) LocationUploadDisposition.RETRY else legacy()
    }
}

object LocationSafetyUploadPolicy {
    /** A persisted local claim must also block legacy delivery before the process router restores. */
    fun isHostOwned(
        registered: Boolean,
        persistedOwnership: () -> Boolean,
    ): Boolean = registered || try { persistedOwnership() } catch (_: Exception) {
        // Unknown durable ownership is not permission to send the same event to the old route.
        true
    }

    /** No queue access, worker, network or alarm is needed for an ineligible sender. */
    fun shouldStartUploader(
        enabled: Boolean,
        configuredUrl: String,
        tokenLength: Int,
        hasPendingEvents: () -> Boolean,
    ): Boolean {
        if (!enabled || tokenLength < 16 ||
            SentinelEndpointPolicy.locationEventsUrl(configuredUrl) == null) return false
        // An unreadable queue is not consent to create a sender or discard events.
        return try { hasPendingEvents() } catch (_: Exception) { false }
    }

    fun disposition(httpCode: Int): LocationUploadDisposition = when {
        httpCode in 200..299 -> LocationUploadDisposition.DELIVERED
        httpCode in setOf(400, 413, 415, 422) -> LocationUploadDisposition.REJECTED
        else -> LocationUploadDisposition.RETRY
    }

    fun retryDelayMs(attemptNumber: Int): Long = when (attemptNumber.coerceAtLeast(1)) {
        1 -> 60_000L
        2 -> 120_000L
        3 -> 300_000L
        else -> 900_000L
    }

    fun wireType(type: LocationSafetyEventType): String = when (type) {
        LocationSafetyEventType.DEPARTED -> "zone_exit_confirmed"
        LocationSafetyEventType.DISTANCE_REMINDER -> "distance_tier_crossed"
        LocationSafetyEventType.ARRIVED -> "zone_enter_confirmed"
        LocationSafetyEventType.LOCATION_DEGRADED -> "location_degraded"
        LocationSafetyEventType.TRACKING_PAUSED -> "tracking_paused"
        LocationSafetyEventType.OFFLINE_TRIP_SUMMARY -> "offline_trip_summary"
        LocationSafetyEventType.REPORT_ACKNOWLEDGED -> "report_acknowledged"
    }
}
