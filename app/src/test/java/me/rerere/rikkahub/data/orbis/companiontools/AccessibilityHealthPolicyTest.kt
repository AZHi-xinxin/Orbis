package me.rerere.rikkahub.data.orbis.companiontools

import org.junit.Assert.*
import org.junit.Test

class AccessibilityHealthPolicyTest {
    private val healthy = AccessibilityHealthPolicy.Sample(enabledInSettings = true, connected = true)
    private val disabled = AccessibilityHealthPolicy.Sample(enabledInSettings = false, connected = false)
    private val disconnected = AccessibilityHealthPolicy.Sample(enabledInSettings = true, connected = false)

    private fun previouslyEnabled() = AccessibilityHealthPolicy(
        AccessibilityHealthPolicy.State(everEnabled = true),
    )

    private fun AccessibilityHealthPolicy.outage(sample: AccessibilityHealthPolicy.Sample = disabled, at: Long = 0L) {
        observe(sample, at)
        observe(sample, at + 15_000L)
    }

    @Test fun `never enabled fresh installation never alerts`() {
        val policy = AccessibilityHealthPolicy()
        policy.observe(disabled, 0)
        policy.observe(disabled, 1_000_000)
        assertFalse(policy.state.everEnabled)
        assertFalse(policy.state.outageActive)
        assertNull(policy.issue)
        assertFalse(policy.shouldShowForeground())
        assertFalse(policy.shouldPostNotification())
    }

    @Test fun `observing an enabled switch remembers prior consent without requiring a live connection`() {
        val policy = AccessibilityHealthPolicy()
        policy.observe(disconnected, 0)
        assertTrue(policy.state.everEnabled)
        assertFalse(policy.shouldShowForeground())
        policy.observe(disconnected, 15_000)
        assertEquals(AccessibilityHealthPolicy.Issue.CONNECTION_UNAVAILABLE, policy.issue)
        assertTrue(policy.shouldShowForeground())
    }

    @Test fun `disabled setting has its own factual reason and a fifteen second grace`() {
        val policy = previouslyEnabled()
        policy.observe(disabled, 100)
        policy.observe(disabled, 15_099)
        assertNull(policy.issue)
        assertFalse(policy.state.outageActive)
        policy.observe(disabled, 15_100)
        assertEquals(AccessibilityHealthPolicy.Issue.SETTING_DISABLED, policy.issue)
        assertTrue(policy.shouldShowForeground())
    }

    @Test fun `brief service rebind does not create an outage`() {
        val policy = previouslyEnabled()
        policy.observe(disconnected, 0)
        policy.observe(healthy, 14_999)
        assertNull(policy.issue)
        assertFalse(policy.state.outageActive)
        policy.observe(disconnected, 16_000)
        policy.observe(disconnected, 30_999)
        assertFalse(policy.shouldShowForeground())
    }

    @Test fun `foreground delivery suppresses repeated checks and background notices`() {
        val policy = previouslyEnabled()
        policy.outage()
        policy.foregroundWasShown()
        repeat(10) { policy.observe(disabled, 20_000L + it * 5_000L) }
        assertFalse(policy.shouldShowForeground())
        assertFalse(policy.shouldPostNotification())
        assertTrue(policy.state.outageActive)
    }

    @Test fun `later dismissal remains deduplicated after process restart`() {
        val policy = previouslyEnabled()
        policy.outage()
        policy.foregroundWasShown()
        val restarted = AccessibilityHealthPolicy(policy.state)
        restarted.outage(at = 1_000_000)
        assertFalse(restarted.shouldShowForeground())
        assertFalse(restarted.shouldPostNotification())
    }

    @Test fun `background notification is sent once and leaves a foreground explanation available`() {
        val policy = previouslyEnabled()
        policy.outage()
        assertTrue(policy.shouldPostNotification())
        policy.notificationWasPosted()
        policy.observe(disabled, 30_000)
        assertFalse(policy.shouldPostNotification())
        assertTrue(policy.shouldShowForeground())
        policy.foregroundWasShown()
        assertFalse(policy.shouldShowForeground())
    }

    @Test fun `missing notification permission is not treated as delivery`() {
        val policy = previouslyEnabled()
        policy.outage()
        // The monitor does not call notificationWasPosted when Android denies it.
        policy.observe(disabled, 40_000)
        assertFalse(policy.state.notificationPosted)
        assertTrue(policy.shouldPostNotification())
        assertTrue(policy.shouldShowForeground())
    }

    @Test fun `persisted background notification does not get reposted on every process start`() {
        val policy = previouslyEnabled()
        policy.outage()
        policy.notificationWasPosted()
        val restarted = AccessibilityHealthPolicy(policy.state)
        restarted.outage()
        assertFalse(restarted.shouldPostNotification())
        assertTrue(restarted.shouldShowForeground())
    }

    @Test fun `restoration clears an active outage and permits a later new outage`() {
        val policy = previouslyEnabled()
        policy.outage()
        policy.foregroundWasShown()
        policy.observe(healthy, 20_000)
        assertEquals(AccessibilityHealthPolicy.State(everEnabled = true), policy.state)
        assertNull(policy.issue)
        assertFalse(policy.shouldShowForeground())
        policy.outage(at = 25_000)
        assertTrue(policy.shouldShowForeground())
        assertTrue(policy.shouldPostNotification())
    }

    @Test fun `switch alone cannot claim restoration while service is still disconnected`() {
        val policy = previouslyEnabled()
        policy.outage()
        policy.foregroundWasShown()
        policy.observe(disconnected, 30_000)
        assertTrue(policy.state.outageActive)
        assertEquals(AccessibilityHealthPolicy.Issue.CONNECTION_UNAVAILABLE, policy.issue)
        assertFalse(policy.shouldShowForeground())
    }

    @Test fun `stale service instance cannot claim restoration after switch was disabled`() {
        val policy = previouslyEnabled()
        policy.outage(AccessibilityHealthPolicy.Sample(enabledInSettings = false, connected = true))
        assertEquals(AccessibilityHealthPolicy.Issue.SETTING_DISABLED, policy.issue)
        assertTrue(policy.shouldShowForeground())
    }

    @Test fun `restarted process gives system a fresh rebind grace even with saved outage`() {
        val previous = previouslyEnabled()
        previous.outage()
        val restarted = AccessibilityHealthPolicy(previous.state)
        restarted.observe(disconnected, 1_000)
        assertNull(restarted.issue)
        restarted.observe(healthy, 10_000)
        assertFalse(restarted.state.outageActive)
        assertFalse(restarted.shouldShowForeground())
    }

    @Test fun `elapsed clock going backwards restarts grace instead of alerting immediately`() {
        val policy = previouslyEnabled()
        policy.observe(disconnected, 50_000)
        policy.observe(disconnected, 1_000)
        policy.observe(disconnected, 15_999)
        assertNull(policy.issue)
        policy.observe(disconnected, 16_000)
        assertTrue(policy.shouldShowForeground())
    }

    @Test fun `delivery calls when no problem exists have no effect`() {
        val policy = previouslyEnabled()
        policy.observe(healthy, 0)
        policy.foregroundWasShown()
        policy.notificationWasPosted()
        assertEquals(AccessibilityHealthPolicy.State(everEnabled = true), policy.state)
    }
}
