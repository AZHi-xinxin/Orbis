package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisHomeNavigationStateTest {
    @Test fun closedConsultationHasNoNavigationTileForFreshOrPreviouslyConfiguredUsers() {
        assertFalse(showConsultationInNavigation(featureEnabled = false, connectionAvailable = false))
        assertFalse(showConsultationInNavigation(featureEnabled = false, connectionAvailable = true))
    }

    @Test fun internalConsultationNavigationStillRequiresAnAvailableConnection() {
        assertFalse(showConsultationInNavigation(featureEnabled = true, connectionAvailable = false))
        assertTrue(showConsultationInNavigation(featureEnabled = true, connectionAvailable = true))
    }

    @Test fun newActivityStartsWithNativeChat() {
        val navigation = OrbisHomeNavigationState()
        assertFalse(navigation.visible)
        assertEquals(0, navigation.homeRevision)
    }

    @Test fun originalHomeRemainsExplicitlyReachable() {
        val navigation = OrbisHomeNavigationState()
        navigation.openHome()
        assertTrue(navigation.visible)
        assertEquals(1, navigation.homeRevision)
        navigation.returnToChat()
        assertFalse(navigation.visible)
        assertEquals(1, navigation.homeRevision)
        navigation.openHome()
        assertTrue(navigation.visible)
        assertEquals(2, navigation.homeRevision)
    }

    @Test fun repeatedChatReturnIsIdempotent() {
        val navigation = OrbisHomeNavigationState()
        navigation.openHome()
        repeat(10) { navigation.returnToChat() }
        assertFalse(navigation.visible)
        assertEquals(1, navigation.homeRevision)
    }

    @Test fun newActivityDoesNotInheritPreviousCloudPage() {
        val previousActivity = OrbisHomeNavigationState().apply { openHome() }
        val newActivity = OrbisHomeNavigationState()
        assertTrue(previousActivity.visible)
        assertFalse(newActivity.visible)
        assertEquals(0, newActivity.homeRevision)
    }
}
