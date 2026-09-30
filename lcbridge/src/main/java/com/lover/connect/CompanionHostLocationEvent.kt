package com.lover.connect

import org.json.JSONObject

/**
 * Coordinate-free adapter shared by the durable local host and legacy uploader.
 * Keeps the original event ID so a host acceptance followed by process death is safe to retry.
 */
fun LocationSafetyEvent.asHostEvent(appVersion: String): CompanionHostEvent {
    val payload = JSONObject().apply {
        put("event_id", eventId)
        put("type", LocationSafetyUploadPolicy.wireType(type))
        put("away_session_id", awaySessionId)
        put("zone_id", zoneId)
        put("zone_label", zoneLabel.take(24))
        put("occurred_at", occurredAt)
        put("distance_bucket", distanceBucket ?: JSONObject.NULL)
        put("reported_override", reportedOverride)
        put("app_version", appVersion)
    }
    return CompanionHostEvent(CompanionHostEvents.LC_LOCATION, eventId,
        payload.getString("type"), null, payload.toString(), occurredAt)
}
