package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.assertEquals
import org.junit.Test
import com.lover.connect.CompanionHostEvent
import com.lover.connect.CompanionHostEventResult
import com.lover.connect.CompanionHostEventRouter

class OrbisSentinelTransportPolicyTest {
    private val known = setOf("lc_visual", "lc_rest", "lc_location")

    @Test fun noCutoverDoesNotClaimAnyLegacyProducer() {
        assertEquals(emptySet<String>(), sentinelOwnedSources(emptySet(), false, known))
    }

    @Test fun partialCutoverDoesNotExpandToOtherLegacyCapabilities() {
        assertEquals(setOf("lc_location"), sentinelOwnedSources(setOf("lc_location", "invalid"), false, known))
    }

    @Test fun explicitFullCutoverClaimsKnownSourcesOnly() {
        assertEquals(known, sentinelOwnedSources(setOf("invalid"), true, known))
    }

    private fun nativeRule(type: OrbisSentinelType, enabled: Boolean = true) = OrbisSentinelRule(
        assistantId = "synthetic-assistant", conversationId = "synthetic-conversation",
        type = type, enabled = enabled,
    )

    @Test fun enabledNativeGeofenceClaimsOnlyItsIndependentLocationOutbox() {
        val state = OrbisSentinelState(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE)))
        assertEquals(setOf("lc_location"), sentinelOwnedSources(emptySet(), false, known, state))
    }

    @Test fun disabledGeofenceAndHumanMasterOffDoNotStartANewRoute() {
        val disabled = OrbisSentinelState(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE, false)))
        val paused = OrbisSentinelState(enabled = false, rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE)))
        assertEquals(emptySet<String>(), sentinelOwnedSources(emptySet(), false, known, disabled))
        assertEquals(emptySet<String>(), sentinelOwnedSources(emptySet(), false, known, paused))
    }

    @Test fun unrelatedNativeRulesNeverClaimLegacyProducers() {
        OrbisSentinelType.entries.filterNot { it == OrbisSentinelType.GEOFENCE }.forEach { type ->
            val state = OrbisSentinelState(rules = listOf(nativeRule(type)))
            assertEquals(type.name, emptySet<String>(), sentinelOwnedSources(emptySet(), false, known, state))
        }
    }

    @Test fun geofenceClaimSurvivesPauseDeletionAndRestartWithoutLegacyFallback() {
        val active = OrbisSentinelState(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE)))
        val persisted = sentinelOwnedSources(emptySet(), false, known, active)
        for (later in listOf(active.copy(enabled = false), active.copy(rules = emptyList()),
            active.copy(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE, false))))) {
            assertEquals(setOf("lc_location"), sentinelOwnedSources(persisted, false, known, later))
        }
    }

    @Test fun geofenceDoesNotClaimAnUnknownSourceOrBroadenExistingCutover() {
        val state = OrbisSentinelState(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE)))
        assertEquals(emptySet<String>(), sentinelOwnedSources(emptySet(), false, setOf("lc_visual"), state))
        assertEquals(setOf("lc_rest", "lc_location"), sentinelOwnedSources(setOf("lc_rest", "invalid"), false, known, state))
    }

    @Test fun nativeGeofenceRouteReceivesOriginalQueuedIdentityWithoutAnyLegacyConfiguration() {
        val state = OrbisSentinelState(rules = listOf(nativeRule(OrbisSentinelType.GEOFENCE)))
        val router = CompanionHostEventRouter()
        val received = mutableListOf<CompanionHostEvent>()
        val event = CompanionHostEvent("lc_location", "synthetic-queued-location", "zone_enter_confirmed",
            null, "{\"event_id\":\"synthetic-queued-location\"}", 1_000L)
        router.install(sentinelOwnedSources(emptySet(), false, known, state)) {
            received += it
            CompanionHostEventResult.ACCEPTED
        }.use {
            assertEquals(CompanionHostEventResult.ACCEPTED, router.dispatch(event))
            assertEquals(listOf(event), received)
            assertEquals(CompanionHostEventResult.NOT_OWNED, router.dispatch(event.copy(source = "lc_visual")))
            assertEquals(listOf(event), received)
        }
    }
}
