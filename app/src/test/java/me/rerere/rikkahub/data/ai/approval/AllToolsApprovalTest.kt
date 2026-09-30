package me.rerere.rikkahub.data.ai.approval

import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AllToolsApprovalTest {
    private class Disk {
        var content: String? = null
        var fail = false
        fun open() = ToolApprovalStore({ content }) { check(!fail); content = it }
    }
    private val args = JsonObject(emptyMap())
    private fun source(name: String = "local_write", host: Boolean = false, legacyFresh: Boolean = false) = Tool(
        name = name, description = "Synthetic tool", needsApproval = { true }, execute = { error("Not executed in policy tests") },
        hostApproval = if (host) HostToolApproval(name, "revision", name, rememberable = !legacyFresh) else null,
        requiresFreshApproval = { legacyFresh },
    )

    @Test fun defaultIsOffAndExplicitSwitchDoesNotExecuteAnything() {
        val store = Disk().open()
        val tool = bindToolApproval(source(), "azhi", store)
        assertFalse(store.allowsAll("azhi"))
        assertTrue(tool.needsApproval(args))
        store.setAllowAll("azhi", true)
        assertTrue(store.allowsAll("azhi"))
        assertFalse(tool.needsApproval(args))
    }

    @Test fun allToolFamiliesAndLocalToolsWithoutMetadataAreCovered() {
        val store = Disk().open()
        store.setAllowAll("azhi", true)
        listOf("local_write", "companion_lock_app", "toy_bluetooth_set", "cloud_orbis_write", "mcp__Example__write", "workspace_shell")
            .forEach { name ->
                val tool = bindToolApproval(source(name, host = name != "local_write", legacyFresh = true), "azhi", store)
                assertFalse(name, tool.needsApproval(args))
                assertTrue(tool.hostApproval!!.rememberable)
                assertFalse(tool.requiresFreshApproval(args))
            }
    }

    @Test fun oldFreshFlagOnlyRequestsInitialApprovalNotPermanentHumanConfirmation() {
        val store = Disk().open()
        val original = source(host = true, legacyFresh = true).copy(needsApproval = { false })
        val tool = bindToolApproval(original, "azhi", store)
        assertTrue(tool.needsApproval(args))
        store.allow("azhi", tool.hostApproval!!)
        assertFalse(tool.needsApproval(args))
    }

    @Test fun switchIsScopedToOneAssistantAndSurvivesRestart() {
        val disk = Disk()
        disk.open().setAllowAll("azhi", true)
        val store = disk.open()
        assertTrue(store.allowsAll("azhi"))
        assertFalse(store.allowsAll("asu"))
        assertFalse(bindToolApproval(source(), "azhi", store).needsApproval(args))
        assertTrue(bindToolApproval(source(), "asu", store).needsApproval(args))
    }

    @Test fun disableAffectsAlreadyBuiltToolsAndPersists() {
        val disk = Disk()
        val store = disk.open()
        val tool = bindToolApproval(source(), "azhi", store)
        store.setAllowAll("azhi", true)
        assertFalse(tool.needsApproval(args))
        store.setAllowAll("azhi", false)
        assertTrue(tool.needsApproval(args))
        assertFalse(disk.open().allowsAll("azhi"))
    }

    @Test fun disablingGlobalKeepsIndividualGrantsButRevokeAllRemovesBoth() {
        val store = Disk().open()
        val tool = bindToolApproval(source(), "azhi", store)
        store.allow("azhi", tool.hostApproval!!)
        store.setAllowAll("azhi", true)
        store.setAllowAll("asu", true)
        store.setAllowAll("azhi", false)
        assertFalse(tool.needsApproval(args))
        store.setAllowAll("azhi", true)
        store.revoke("azhi")
        assertFalse(store.allowsAll("azhi"))
        assertTrue(store.allowsAll("asu"))
        assertTrue(tool.needsApproval(args))
    }

    @Test fun globalDoesNotBypassTargetGuardOrOldInputAndRevisionChecks() {
        val store = Disk().open()
        store.setAllowAll("azhi", true)
        var current = true
        val original = source(host = true).copy(isApprovalCurrent = { current })
        val tool = bindToolApproval(original, "azhi", store)
        val oldCall = UIMessagePart.Tool("id", tool.name, "{\"path\":\"one\"}").awaitHostApproval(tool)
        assertTrue(oldCall.matchesHostApproval(tool))
        assertFalse(oldCall.copy(input = "{\"path\":\"two\"}").matchesHostApproval(tool))
        assertFalse(oldCall.matchesHostApproval(bindToolApproval(original.copy(hostApproval = original.hostApproval!!.copy(revision = "new-target")), "azhi", store)))
        current = false
        assertTrue(tool.needsApproval(args))
        assertFalse(tool.isApprovalCurrent())
        assertFalse(oldCall.matchesHostApproval(tool))
    }

    @Test fun rebuildingUpdatedToolRespectsGlobalButDoesNotReuseOldSnapshot() {
        val store = Disk().open()
        store.setAllowAll("azhi", true)
        val a = bindToolApproval(source(host = true), "azhi", store)
        val pending = UIMessagePart.Tool("id", a.name, "{}").awaitHostApproval(a)
        val updatedSource = source(host = true).let { it.copy(hostApproval = it.hostApproval!!.copy(revision = "updated")) }
        val b = bindToolApproval(updatedSource, "azhi", store)
        assertFalse(b.needsApproval(args))
        assertFalse(pending.matchesHostApproval(b))
    }

    @Test fun legacyFalseRememberabilityDoesNotHideValidCurrentGrant() {
        val store = Disk().open()
        val tool = bindToolApproval(source(host = true, legacyFresh = true), "azhi", store)
        val old = UIMessagePart.Tool("id", tool.name, "{}").awaitHostApproval(tool)
            .let { it.copy(hostApproval = it.hostApproval!!.copy(rememberable = false)) }
        assertTrue(old.matchesHostApproval(tool))
        assertTrue(tool.hostApproval!!.rememberable)
        store.allow("azhi", tool.hostApproval!!)
        assertFalse(tool.needsApproval(args))
    }

    @Test fun askUserStillRequiresAnswerAndEmergencyStopStaysAvailable() {
        val store = Disk().open()
        store.setAllowAll("azhi", true)
        val ask = source("ask_user")
        assertSame(ask, bindToolApproval(ask, "azhi", store))
        assertTrue(bindToolApproval(ask, "azhi", store).needsApproval(args))
        val stop = source("toy_bluetooth_stop").copy(needsApproval = { false })
        assertSame(stop, bindToolApproval(stop, "azhi", store))
        assertFalse(bindToolApproval(stop, "azhi", store).needsApproval(args))
    }

    @Test fun oldFileWithoutGlobalSettingDoesNotEnableItOnUpgrade() {
        val disk = Disk().apply { content = """{"version":1,"installationScope":"old-installation","grants":[]}""" }
        val store = disk.open()
        assertEquals("old-installation", store.installationScope)
        assertTrue(store.allowAllAssistants.value.isEmpty())
        store.setAllowAll("azhi", true)
        assertEquals(2, Json.parseToJsonElement(disk.content!!).jsonObject["version"]!!.jsonPrimitive.int)
        assertTrue(disk.open().allowsAll("azhi"))
    }

    @Test fun failedEnableDoesNotGrantAndFailedDisableClosesLivePermissions() {
        val disk = Disk()
        val store = disk.open()
        disk.fail = true
        assertTrue(runCatching { store.setAllowAll("azhi", true) }.isFailure)
        assertFalse(store.allowsAll("azhi"))
        disk.fail = false
        store.setAllowAll("azhi", true)
        disk.fail = true
        assertTrue(runCatching { store.setAllowAll("azhi", false) }.isFailure)
        assertFalse(store.allowsAll("azhi"))
        // Failed disk revocation cannot be called restart-durable. UI explicitly warns about this.
        assertTrue(disk.open().allowsAll("azhi"))
    }

    @Test fun malformedFileCannotEnableGlobalUntilExplicitRecovery() {
        val disk = Disk().apply { content = "invalid" }
        val store = disk.open()
        assertFalse(store.allowsAll("azhi"))
        assertTrue(runCatching { store.setAllowAll("azhi", true) }.isFailure)
        store.revoke("azhi")
        store.setAllowAll("azhi", true)
        assertTrue(store.allowsAll("azhi"))
    }

    @Test fun noNewPendingCallsAreAutoApprovedJustByChangingSwitch() {
        val store = Disk().open()
        val tool = bindToolApproval(source(), "azhi", store)
        val waiting = UIMessagePart.Tool("id", tool.name, "{}").awaitHostApproval(tool)
        store.setAllowAll("azhi", true)
        assertTrue(waiting.isPending)
        assertTrue(waiting.output.isEmpty())
        assertFalse(tool.needsApproval(args))
    }

    @Test fun explicitReadOnlyOrOriginalNoApprovalSettingIsNotChangedOnDisable() {
        val store = Disk().open()
        val read = bindToolApproval(source().copy(needsApproval = { false }), "azhi", store)
        store.setAllowAll("azhi", true)
        store.setAllowAll("azhi", false)
        assertFalse(read.needsApproval(args))
    }
}
