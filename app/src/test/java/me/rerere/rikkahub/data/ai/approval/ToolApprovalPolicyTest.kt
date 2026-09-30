package me.rerere.rikkahub.data.ai.approval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ToolApprovalPolicyTest {
    private class Disk {
        var content: String? = null
        var fail = false
        fun open() = ToolApprovalStore({ content }) { check(!fail); content = it }
    }
    private val args = JsonObject(emptyMap())
    private fun tool(revision: String = "r1", rememberable: Boolean = true) = Tool(
        name = "test_write", description = "writes one item", needsApproval = { true },
        execute = { emptyList() },
        hostApproval = HostToolApproval("test:write", revision, "Test", rememberable),
    )

    @Test fun unknownAndFreshToolsRequireApprovalByDefault() {
        val store = Disk().open()
        val wrapped = bindToolApproval(tool(), "a", store)
        assertTrue(wrapped.needsApproval(args))
        assertFalse(store.permits("a", HostToolApproval("unknown", "v1", "Unknown")))
    }

    @Test fun rememberedGrantSurvivesRestartButNotAssistantOrRevisionChanges() {
        val disk = Disk()
        val store = disk.open()
        val a = bindToolApproval(tool(), "a", store)
        store.allow("a", a.hostApproval!!)
        assertFalse(a.needsApproval(args))
        assertFalse(bindToolApproval(tool(), "a", disk.open()).needsApproval(args))
        assertTrue(bindToolApproval(tool(), "b", store).needsApproval(args))
        assertTrue(bindToolApproval(tool("r2"), "a", store).needsApproval(args))
    }

    @Test fun revokeAffectsAlreadyBuiltToolAndPersists() {
        val disk = Disk()
        val store = disk.open()
        val wrapped = bindToolApproval(tool(), "a", store)
        store.allow("a", wrapped.hostApproval!!)
        store.revoke("a", wrapped.hostApproval!!.stableId)
        assertTrue(wrapped.needsApproval(args))
        assertTrue(bindToolApproval(tool(), "a", disk.open()).needsApproval(args))
    }

    @Test fun failedSaveDoesNotGrantAndFailedRevokeClosesLiveStore() {
        val disk = Disk()
        val store = disk.open()
        val wrapped = bindToolApproval(tool(), "a", store)
        disk.fail = true
        assertTrue(runCatching { store.allow("a", wrapped.hostApproval!!) }.isFailure)
        assertTrue(wrapped.needsApproval(args))
        disk.fail = false
        store.allow("a", wrapped.hostApproval!!)
        disk.fail = true
        assertTrue(runCatching { store.revoke("a") }.isFailure)
        assertTrue(wrapped.needsApproval(args))
        // The UI explicitly warns that failed disk revocation is not restart-durable.
    }

    @Test fun malformedPersistenceFailsClosed() {
        val store = ToolApprovalStore({ "not-json" }, {})
        val wrapped = bindToolApproval(tool(), "a", store)
        assertTrue(wrapped.needsApproval(args))
        assertTrue(runCatching { store.allow("a", wrapped.hostApproval!!) }.isFailure)
    }

    @Test fun legacyFreshOnlyCanBeRememberedButChangingTargetsCannotReuseGrant() {
        var current = true
        val store = Disk().open()
        val source = tool().copy(isApprovalCurrent = { current })
        val wrapped = bindToolApproval(source, "a", store)
        store.allow("a", wrapped.hostApproval!!)
        assertFalse(wrapped.needsApproval(args))
        current = false
        assertTrue(wrapped.needsApproval(args))
        val fresh = bindToolApproval(tool().copy(requiresFreshApproval = { true }), "a", store)
        assertFalse(fresh.needsApproval(args))
        assertFalse(fresh.requiresFreshApproval(args))
        assertTrue(UIMessagePart.Tool("id", fresh.name, "{}").awaitHostApproval(fresh).hostApproval!!.rememberable)
    }

    @Test fun approvedCallBoundToInputIdentityRevisionAndCurrentTarget() {
        var current = true
        val store = Disk().open()
        val wrapped = bindToolApproval(tool().copy(isApprovalCurrent = { current }), "a", store)
        val pending = UIMessagePart.Tool("id", wrapped.name, "{\"level\":1}").awaitHostApproval(wrapped)
        assertTrue(pending.matchesHostApproval(wrapped))
        assertFalse(pending.copy(input = "{\"level\":5}").matchesHostApproval(wrapped))
        assertFalse(pending.matchesHostApproval(bindToolApproval(tool("r2"), "a", store)))
        assertFalse(pending.matchesHostApproval(bindToolApproval(tool(), "b", store)))
        assertFalse(pending.copy(hostApproval = null).matchesHostApproval(wrapped))
        current = false
        assertFalse(pending.matchesHostApproval(wrapped))
    }

    @Test fun canonicalInputIgnoresObjectOrderButNotValue() {
        assertEquals(approvalInputFingerprint("{\"a\":1,\"b\":2}"), approvalInputFingerprint("{\"b\":2,\"a\":1}"))
        assertNotEquals(approvalInputFingerprint("{\"a\":1}"), approvalInputFingerprint("{\"a\":2}"))
    }

    @Test fun askUserAndEmergencyStopNeverAcquireRememberedBypass() {
        val store = Disk().open()
        val ask = tool().copy(name = "ask_user")
        assertSame(ask, bindToolApproval(ask, "a", store))
        val stop = tool().copy(name = "toy_bluetooth_stop", needsApproval = { false })
        assertSame(stop, bindToolApproval(stop, "a", store))
        assertFalse(bindToolApproval(stop, "a", store).needsApproval(args))
    }

    @Test fun onceApprovalSnapshotCannotBeRestoredOnAnotherInstallation() {
        val disk = Disk()
        val original = bindToolApproval(tool(), "a", disk.open())
        val snapshot = UIMessagePart.Tool("id", original.name, "{}").awaitHostApproval(original)
        assertTrue(snapshot.matchesHostApproval(bindToolApproval(tool(), "a", disk.open())))
        assertFalse(snapshot.matchesHostApproval(bindToolApproval(tool(), "a", Disk().open())))
    }

    @Test fun existingReadOnlyOrExplicitNoApprovalConfigurationIsPreserved() {
        val source = tool().copy(needsApproval = { false })
        assertFalse(bindToolApproval(source, "a", Disk().open()).needsApproval(args))
    }

    @Test fun oldMessagesDeserializeWithoutApprovalSnapshot() {
        val old = Json.decodeFromString<UIMessagePart.Tool>("{\"toolCallId\":\"id\",\"toolName\":\"test\",\"input\":\"{}\"}")
        assertNull(old.hostApproval)
        assertNull(old.approvalInputFingerprint)
        assertTrue(old.approvalState is ToolApprovalState.Auto)
    }

    @Test fun previewRedactsNestedCredentialFieldsWithoutChangingOriginal() {
        val raw = Json.parseToJsonElement("{\"payload\":{\"api_key\":\"not-a-real-key\",\"sentinelToken\":\"demo\",\"message\":\"hello\"}}")
        val shown = redactApprovalJson(raw).toString()
        assertFalse(shown.contains("not-a-real-key"))
        assertFalse(shown.contains("demo"))
        assertTrue(shown.contains("hello"))
        assertTrue(raw.toString().contains("not-a-real-key"))
    }
}
