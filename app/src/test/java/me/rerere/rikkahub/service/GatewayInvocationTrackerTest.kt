package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class GatewayInvocationTrackerTest {
    private fun batch(conversation: String = "conversation", assistant: String = "assistant",
        model: String = "model", message: String = "message", input: String = "{\"secret\":\"value\"}",
        name: String = "test_tool", callId: String = "call") = checkNotNull(GatewayToolBatchIdentity.create(
        conversation, assistant, model, message, listOf(GatewayToolCallIdentity(callId, name, input))))

    @Test fun `failed invocation has exactly its own request handles`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val old = tracker.begin("conversation")
        tracker.remember(old, "old")
        tracker.finish(old)
        val next = tracker.begin("conversation")
        tracker.remember(next, "new")
        assertEquals(listOf("new"), tracker.requests(next))
        assertEquals(listOf("old"), tracker.requests(old))
    }

    @Test fun `approval resume inherits batch before any new HTTP attempt`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val initial = tracker.begin("conversation")
        tracker.remember(initial, "delivered-tool-batch")
        assertTrue(tracker.retainForApproval(initial, batch()))
        tracker.finish(initial)
        val resumed = tracker.begin("conversation", batch())
        // A local tool can throw before observing another provider request. The owner survives.
        assertEquals(listOf("delivered-tool-batch"), tracker.requests(resumed))
        tracker.finish(resumed)
        assertTrue(tracker.requests(tracker.begin("conversation", batch())).isEmpty())
    }

    @Test fun `all identity fields reject unrelated approval continuation`() {
        val changed = listOf(batch(assistant = "other"), batch(model = "other"),
            batch(message = "other"), batch(input = "changed"), batch(name = "other"),
            batch(callId = "other"), batch(conversation = "other"))
        changed.forEach { different ->
            val tracker = GatewayInvocationTracker<String>({ it })
            val initial = tracker.begin("conversation")
            tracker.remember(initial, "old")
            assertTrue(tracker.retainForApproval(initial, batch()))
            assertTrue(tracker.requests(tracker.begin("conversation", different)).isEmpty())
        }
    }

    @Test fun `batch order and number are bound without ambiguous string concatenation`() {
        fun identity(calls: List<GatewayToolCallIdentity>) = checkNotNull(GatewayToolBatchIdentity.create(
            "conversation", "assistant", "model", "message", calls))
        val a = GatewayToolCallIdentity("a", "tool", "x")
        val b = GatewayToolCallIdentity("b", "tool", "y")
        assertFalse(identity(listOf(a, b)).matches(identity(listOf(b, a))))
        assertFalse(identity(listOf(a, b)).matches(identity(listOf(a))))
        assertFalse(batch(name = "ab", input = "c").matches(batch(name = "a", input = "bc")))
        assertTrue(identity(listOf(a, b)).matches(identity(listOf(a, b))))
    }

    @Test fun `one pending handoff cannot be consumed twice`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val initial = tracker.begin("conversation")
        tracker.remember(initial, "old")
        tracker.retainForApproval(initial, batch())
        val resumed = tracker.begin("conversation", batch())
        assertEquals(listOf("old"), tracker.requests(resumed))
        assertTrue(tracker.requests(tracker.begin("conversation", batch())).isEmpty())
    }

    @Test fun `late old callbacks cannot overwrite or remove newer owner`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val old = tracker.begin("conversation")
        tracker.remember(old, "old")
        val current = tracker.begin("conversation")
        tracker.remember(current, "current")
        tracker.remember(old, "late")
        assertFalse(tracker.retainForApproval(old, batch()))
        tracker.finish(old)
        assertEquals(listOf("old"), tracker.requests(old))
        assertTrue(tracker.retainForApproval(current, batch()))
        assertEquals(listOf("current"), tracker.requests(tracker.begin("conversation", batch())))
    }

    @Test fun `independent conversations preserve distinct pending handoffs`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        listOf("one", "two").forEach { conversation ->
            val invocation = tracker.begin(conversation)
            tracker.remember(invocation, conversation)
            tracker.retainForApproval(invocation, batch(conversation = conversation))
        }
        assertEquals(listOf("one"), tracker.requests(tracker.begin("one", batch(conversation = "one"))))
        assertEquals(listOf("two"), tracker.requests(tracker.begin("two", batch(conversation = "two"))))
    }

    @Test fun `duplicate observed nonce is refreshed and requests are most recent first`() {
        val tracker = GatewayInvocationTracker<String>({ it }, maxRequests = 2)
        val invocation = tracker.begin("conversation")
        listOf("one", "two", "one", "three").forEach { tracker.remember(invocation, it) }
        assertEquals(listOf("three", "one"), tracker.requests(invocation))
    }

    @Test fun `new failed attempt does not hide inherited waiting request`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val old = tracker.begin("conversation")
        tracker.remember(old, "waiting")
        tracker.retainForApproval(old, batch())
        val resumed = tracker.begin("conversation", batch())
        tracker.remember(resumed, "rejected-continuation")
        assertEquals(listOf("rejected-continuation", "waiting"), tracker.requests(resumed))
    }

    @Test fun `capacity eviction does not let late owner restore its handoff`() {
        val tracker = GatewayInvocationTracker<String>({ it }, maxConversations = 1)
        val old = tracker.begin("old")
        tracker.remember(old, "old")
        tracker.begin("new")
        assertFalse(tracker.retainForApproval(old, batch(conversation = "old")))
        assertTrue(tracker.requests(tracker.begin("old", batch(conversation = "old"))).isEmpty())
    }

    @Test fun `foreign tracker cannot adopt another tracker invocation`() {
        val first = GatewayInvocationTracker<String>({ it })
        val second = GatewayInvocationTracker<String>({ it })
        val invocation = first.begin("conversation")
        first.remember(invocation, "private-handle")
        second.remember(invocation, "wrong")
        assertTrue(second.requests(invocation).isEmpty())
        assertFalse(second.retainForApproval(invocation, batch()))
        second.finish(invocation)
        assertEquals(listOf("private-handle"), first.requests(invocation))
    }

    @Test fun `invalid batches cannot form a handoff identity`() {
        fun create(conversation: String, calls: List<GatewayToolCallIdentity>) =
            GatewayToolBatchIdentity.create(conversation, "assistant", "model", "message", calls)
        val call = GatewayToolCallIdentity("call", "tool", "{}")
        assertNull(create("", listOf(call)))
        assertNull(create("conversation", emptyList()))
        assertNull(create("conversation", listOf(call, call)))
        assertNull(create("conversation", listOf(GatewayToolCallIdentity("", "tool", "{}"))))
        assertNull(create("conversation", listOf(GatewayToolCallIdentity("call", "", "{}"))))
    }

    @Test fun `diagnostic strings never expose batch data or request credentials`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val invocation = tracker.begin("private-conversation")
        tracker.remember(invocation, "Bearer private-credential")
        assertFalse(invocation.toString().contains("private"))
        assertFalse(batch().toString().contains("secret"))
        assertFalse(GatewayToolCallIdentity("id", "name", "private-input").toString().contains("private"))
    }

    @Test fun `non approval finish never leaves transferable ownership`() {
        val tracker = GatewayInvocationTracker<String>({ it })
        val invocation = tracker.begin("conversation")
        tracker.remember(invocation, "max-step-wait")
        tracker.finish(invocation)
        assertTrue(tracker.requests(tracker.begin("conversation", batch())).isEmpty())
    }
}
