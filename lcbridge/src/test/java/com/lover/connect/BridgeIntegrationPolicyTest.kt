package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeIntegrationPolicyTest {
    @Test fun endpointDoesNotReuseStandalonePort() {
        assertEquals(5001, BridgeIdentity.MCP_PORT)
        assertTrue(BridgeIdentity.MCP_PORT != 5000)
    }

    @Test fun cleanInstallNeverStartsMcpByDefault() {
        assertFalse(McpServiceLifecyclePolicy.DEFAULT_ENABLED)
        assertFalse(McpServiceLifecyclePolicy.shouldRequestStart(false))
    }

    @Test fun bothHostVariantsAreExcludedFromContinuousUse() {
        listOf("org.orbis.agent", "org.orbis.agent.dev", "me.rerere.rikkahub", "com.lover.connect").forEach {
            assertTrue(AppRestPolicy.isChatPackage(it))
            assertTrue(AppRestPolicy.isExcludedPackage(it))
        }
        assertFalse(AppRestPolicy.isChatPackage("example.entertainment"))
    }

    @Test fun hostCannotBeLockedOrRedirected() {
        assertTrue(AppLockManager.isPermanentlyDenied("org.orbis.agent"))
        assertTrue(AppLockManager.isPermanentlyDenied("org.orbis.agent.dev"))
        assertTrue(AppLockManager.isPermanentlyDenied("org.orbis.agent.future"))
    }

    @Test fun visualEventNamesActualSenderWithoutClaimingForegroundApp() {
        val event = EyesAlertPolicy.visual("notify", "visual_observation", "A short observation", 1_000L, "org.orbis.agent.dev")!!
        assertEquals("org.orbis.agent.dev", event.appPackage)
        assertEquals("visual_interaction", event.type)
    }

    @Test fun independentSettingsDestinationsCoverEveryFeatureFamily() {
        assertEquals(setOf("CONNECTION", "VISION", "REST", "LOCATION", "CONTEXT", "CONTROLS", "SENTINEL", "LOCAL"),
            BridgeSection.entries.map { it.name }.toSet())
        assertEquals(BridgeSection.entries.size, BridgeSection.entries.map { it.title }.distinct().size)
    }
}
