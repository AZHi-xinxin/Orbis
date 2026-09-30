package com.lover.connect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic event data and local lambdas only: no Android context, endpoint, observation or wakeup. */
class CompanionHostEventsTest {
    private fun event(source: String = CompanionHostEvents.LC_VISUAL) = CompanionHostEvent(
        source, "synthetic-event-1", "visual_interaction", "原来的提醒，保持原文。",
        "{\"event_id\":\"synthetic-event-1\",\"type\":\"visual_interaction\",\"message\":\"原来的提醒，保持原文。\"}", 1_000L,
    )

    @Test fun installationAndOwnershipChecksAreInert() {
        val router = CompanionHostEventRouter()
        var accepted = 0
        val registration = router.install(setOf(CompanionHostEvents.LC_VISUAL)) {
            accepted++; CompanionHostEventResult.ACCEPTED
        }
        assertTrue(router.owns(CompanionHostEvents.LC_VISUAL))
        assertFalse(router.owns(CompanionHostEvents.LC_REST))
        assertEquals(0, accepted)
        registration.close()
        assertFalse(router.owns(CompanionHostEvents.LC_VISUAL))
        assertEquals(0, accepted)
    }

    @Test fun noHostPreservesTheExistingLegacyOutcomeAndSingleDispatch() {
        val router = CompanionHostEventRouter()
        var sent = 0
        val result = deliverCompanionEvent(event(), { sent++; SentinelDelivery.UNAVAILABLE }, router::dispatch)
        assertFalse(result.viaHost)
        assertEquals(SentinelDelivery.UNAVAILABLE, result.delivery)
        assertTrue(DeliveryPolicy.shouldUseLocalFallback(result.delivery))
        assertEquals(1, sent)
    }

    @Test fun unownedSourceIsNeverCapturedByAnInstalledHost() {
        val router = CompanionHostEventRouter()
        var accepted = 0
        var legacy = 0
        router.install(setOf(CompanionHostEvents.LC_VISUAL)) { accepted++; CompanionHostEventResult.ACCEPTED }
        val result = deliverCompanionEvent(event(CompanionHostEvents.LC_REST),
            { legacy++; SentinelDelivery.DELIVERED }, router::dispatch)
        assertEquals(0, accepted)
        assertEquals(1, legacy)
        assertFalse(result.viaHost)
    }

    @Test fun claimedOutcomesNeverDoublePostOrUseLocalNotificationFallback() {
        CompanionHostEventResult.entries.forEach { response ->
            val router = CompanionHostEventRouter()
            var calls = 0
            router.install(setOf(CompanionHostEvents.LC_VISUAL)) { calls++; response }
            val result = deliverCompanionEvent(event(), { error("must not use legacy") }, router::dispatch)
            assertTrue(result.viaHost)
            assertFalse(DeliveryPolicy.shouldUseLocalFallback(result.delivery))
            assertEquals(1, calls)
            if (response == CompanionHostEventResult.NOT_OWNED) {
                assertEquals(CompanionHostEventResult.UNKNOWN, result.hostResult)
                assertEquals(SentinelDelivery.UNCERTAIN, result.delivery)
            }
        }
    }

    @Test fun sinkFailureAfterPossiblePersistenceIsUnknownWithNoRetryOrFallback() {
        val router = CompanionHostEventRouter()
        var calls = 0
        router.install(setOf(CompanionHostEvents.LC_VISUAL)) { calls++; error("private-diagnostic-do-not-return") }
        val result = deliverCompanionEvent(event(), { error("must not use legacy") }, router::dispatch)
        assertEquals(CompanionHostEventResult.UNKNOWN, result.hostResult)
        assertEquals(SentinelDelivery.UNCERTAIN, result.delivery)
        assertEquals(1, calls)
    }

    @Test fun acceptedAndDuplicateMeanInboxAcceptanceNotASecondMessage() {
        val router = CompanionHostEventRouter()
        val received = mutableSetOf<Pair<String, String>>()
        router.install(setOf(CompanionHostEvents.LC_VISUAL)) {
            if (received.add(it.source to it.eventId)) CompanionHostEventResult.ACCEPTED else CompanionHostEventResult.DUPLICATE
        }
        val original = event()
        assertEquals(CompanionHostEventResult.ACCEPTED, router.dispatch(original))
        assertEquals(CompanionHostEventResult.DUPLICATE, router.dispatch(original))
        assertEquals(1, received.size)
    }

    @Test fun originalContentAndWireSnapshotAreNotRewrittenOrGivenARole() {
        val router = CompanionHostEventRouter()
        val original = event()
        var received: CompanionHostEvent? = null
        router.install(setOf(CompanionHostEvents.LC_VISUAL)) { received = it; CompanionHostEventResult.ACCEPTED }
        router.dispatch(original)
        assertSame(original, received)
        assertEquals(original.content, received?.content)
        assertEquals(original.payloadJson, received?.payloadJson)
        assertFalse(JSONObject(received!!.payloadJson).has("role"))
    }

    @Test fun registrationCopiesOwnedSourceSet() {
        val router = CompanionHostEventRouter()
        val sources = mutableSetOf(CompanionHostEvents.LC_VISUAL)
        router.install(sources) { CompanionHostEventResult.ACCEPTED }
        sources.clear()
        assertTrue(router.owns(CompanionHostEvents.LC_VISUAL))
        assertEquals(CompanionHostEventResult.ACCEPTED, router.dispatch(event()))
    }

    @Test fun staleCloseCannotUninstallNewRegistration() {
        val router = CompanionHostEventRouter()
        val old = router.install(setOf(CompanionHostEvents.LC_VISUAL)) { CompanionHostEventResult.REJECTED }
        val fresh = router.install(setOf(CompanionHostEvents.LC_VISUAL)) { CompanionHostEventResult.ACCEPTED }
        old.close()
        assertEquals(CompanionHostEventResult.ACCEPTED, router.dispatch(event()))
        fresh.close()
        assertEquals(CompanionHostEventResult.NOT_OWNED, router.dispatch(event()))
    }

    @Test fun replacementInsideCallbackDoesNotRedispatchTheInFlightEvent() {
        val router = CompanionHostEventRouter()
        var nextCalls = 0
        router.install(setOf(CompanionHostEvents.LC_VISUAL)) {
            router.install(setOf(CompanionHostEvents.LC_VISUAL)) { nextCalls++; CompanionHostEventResult.ACCEPTED }
            CompanionHostEventResult.UNKNOWN
        }
        assertEquals(CompanionHostEventResult.UNKNOWN, router.dispatch(event()))
        assertEquals(0, nextCalls)
    }

    @Test fun unknownSourcesFailAtRegistrationRatherThanHijackingUnrelatedEvents() {
        val router = CompanionHostEventRouter()
        assertThrows(IllegalArgumentException::class.java) {
            router.install(setOf("another-source")) { CompanionHostEventResult.ACCEPTED }
        }
        assertEquals(CompanionHostEventResult.NOT_OWNED, router.dispatch(event()))
    }

    @Test fun visualEventPreservesExistingPolicyMessageReasonAndEventId() {
        val original = EyesAlertPolicy.visual("notify", "visual_observation", "原文：今天想起一首歌。", 10_000L)!!
        val adapted = original.asHostEvent("synthetic-visual")
        assertEquals(CompanionHostEvents.LC_VISUAL, adapted.source)
        assertEquals(original.message, adapted.content)
        assertEquals(original.toJson("synthetic-visual").toString(), adapted.payloadJson)
        assertEquals(10_000L, adapted.occurredAtMs)
        assertEquals("synthetic-visual", adapted.eventId)
    }

    @Test fun restEventPreservesTextAndDoesNotInventMessageFieldInLegacyPayload() {
        val snapshot = AppRestSnapshot("com.example.reader", 67, 1_000_000L, 4_000_000L, 1L, 60)
        val original = EyesAlertPolicy.rest(snapshot, "测试阅读器")!!
        val adapted = original.asHostEvent("synthetic-rest")
        assertEquals(CompanionHostEvents.LC_REST, adapted.source)
        assertEquals(original.message, adapted.content)
        assertEquals(original.toJson("synthetic-rest").toString(), adapted.payloadJson)
        assertFalse(JSONObject(adapted.payloadJson).has("message"))
        assertEquals(67, JSONObject(adapted.payloadJson).getInt("duration_minutes"))
    }

    @Test fun optionalLocationAdapterKeepsStableIdAndCoordinateFreeWireFieldsWithoutSending() {
        val original = LocationSafetyEvent("synthetic-location", LocationSafetyEventType.ARRIVED,
            "synthetic-trip", "test-zone", "测试区域", 12_345L)
        val adapted = original.asHostEvent("test-build")
        val payload = JSONObject(adapted.payloadJson)
        assertEquals(CompanionHostEvents.LC_LOCATION, adapted.source)
        assertEquals(original.eventId, adapted.eventId)
        assertEquals(original.eventId, payload.getString("event_id"))
        assertEquals(original.occurredAt, adapted.occurredAtMs)
        assertEquals(original.zoneLabel, payload.getString("zone_label"))
        assertNull(adapted.content)
        assertFalse(payload.has("latitude")); assertFalse(payload.has("longitude"))
        assertFalse(payload.has("token")); assertFalse(payload.has("role"))
    }
}
