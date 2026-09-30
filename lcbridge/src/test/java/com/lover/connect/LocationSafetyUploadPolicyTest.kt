package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSafetyUploadPolicyTest {
    @Test
    fun `cold start retains persisted location ownership while router is not restored`() {
        val owned = LocationSafetyUploadPolicy.isHostOwned(false) { true }
        assertTrue(owned)
        val event = CompanionHostEvent("lc_location", "synthetic-pending", "zone_enter_confirmed",
            null, "{}", 1_000L)
        assertEquals(LocationUploadDisposition.RETRY, deliverLocationEvent(event, owned,
            { error("persisted local ownership must not contact VPS") },
            { CompanionHostEventResult.NOT_OWNED }))
        assertEquals(LocationUploadDisposition.DELIVERED, deliverLocationEvent(event, owned,
            { error("restored local route must not contact VPS") },
            { CompanionHostEventResult.ACCEPTED }))
    }

    @Test
    fun `unclaimed route keeps legacy behavior and registered route needs no preference read`() {
        assertFalse(LocationSafetyUploadPolicy.isHostOwned(false) { false })
        assertTrue(LocationSafetyUploadPolicy.isHostOwned(true) { error("registered route needs no preference read") })
        assertTrue(LocationSafetyUploadPolicy.isHostOwned(false) { true })
    }

    @Test
    fun `unreadable ownership retains queue instead of permitting VPS fallback`() {
        val owned = LocationSafetyUploadPolicy.isHostOwned(false) { error("synthetic preferences unavailable") }
        assertTrue(owned)
        val event = CompanionHostEvent("lc_location", "synthetic-pending", "zone_enter_confirmed", null, "{}", 1_000L)
        assertEquals(LocationUploadDisposition.RETRY, deliverLocationEvent(event, owned,
            { error("unknown ownership must not contact VPS") }, { CompanionHostEventResult.NOT_OWNED }))
    }

    @Test
    fun `only successful HTTP acknowledgements are delivered`() {
        assertEquals(
            LocationUploadDisposition.DELIVERED,
            LocationSafetyUploadPolicy.disposition(200)
        )
        assertEquals(
            LocationUploadDisposition.DELIVERED,
            LocationSafetyUploadPolicy.disposition(202)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(401)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(429)
        )
        assertEquals(
            LocationUploadDisposition.RETRY,
            LocationSafetyUploadPolicy.disposition(503)
        )
        assertEquals(
            LocationUploadDisposition.REJECTED,
            LocationSafetyUploadPolicy.disposition(400)
        )
    }

    @Test
    fun `wire names match the server contract`() {
        assertEquals(
            "zone_exit_confirmed",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.DEPARTED)
        )
        assertEquals(
            "report_acknowledged",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.REPORT_ACKNOWLEDGED)
        )
        assertEquals(
            "location_degraded",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.LOCATION_DEGRADED)
        )
        assertEquals(
            "tracking_paused",
            LocationSafetyUploadPolicy.wireType(LocationSafetyEventType.TRACKING_PAUSED)
        )
    }

    @Test
    fun `retry delay is bounded`() {
        assertEquals(60_000L, LocationSafetyUploadPolicy.retryDelayMs(1))
        assertEquals(120_000L, LocationSafetyUploadPolicy.retryDelayMs(2))
        assertEquals(300_000L, LocationSafetyUploadPolicy.retryDelayMs(3))
        assertEquals(900_000L, LocationSafetyUploadPolicy.retryDelayMs(99))
    }
}
