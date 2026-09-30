package me.rerere.rikkahub.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.web.routes.OrbisContextPreviewStore
import org.junit.Assert.*
import org.junit.Test

class OrbisContextPreviewStoreTest {
    @Test fun `ticket belongs to both authenticated owner and conversation`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("browser-a", "chat-a", "preview")
        var calls = 0
        assertTrue(runCatching { store.apply("browser-b", "chat-a", id) { calls++; "archive" } }.exceptionOrNull() is NotFoundException)
        assertTrue(runCatching { store.apply("browser-a", "chat-b", id) { calls++; "archive" } }.exceptionOrNull() is NotFoundException)
        assertEquals(0, calls)
        assertEquals("archive", store.apply("browser-a", "chat-a", id) { calls++; "archive" })
        assertEquals(1, calls)
    }

    @Test fun `expired unused preview is never committed`() = runBlocking {
        var now = 1000L
        val store = OrbisContextPreviewStore<String> { now }
        val id = store.put("owner", "chat", "preview")
        now += 600_001L
        var calls = 0
        assertTrue(runCatching { store.apply("owner", "chat", id) { calls++; "archive" } }.exceptionOrNull() is NotFoundException)
        assertEquals(0, calls)
    }

    @Test fun `ten minute boundary remains valid and receipt later expires`() = runBlocking {
        var now = 1000L
        val store = OrbisContextPreviewStore<String> { now }
        val id = store.put("owner", "chat", "preview")
        now += 600_000L
        assertEquals("archive", store.apply("owner", "chat", id) { "archive" })
        now++
        var repeats = 0
        assertTrue(runCatching { store.apply("owner", "chat", id) { repeats++; "bad" } }.exceptionOrNull() is NotFoundException)
        assertEquals(0, repeats)
    }

    @Test fun `same owner replacing pending preview invalidates old ticket`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val old = store.put("owner", "chat-a", "old")
        val latest = store.put("owner", "chat-b", "new")
        assertNotEquals(old, latest)
        assertTrue(runCatching { store.apply("owner", "chat-a", old) { "bad" } }.exceptionOrNull() is NotFoundException)
        assertEquals("new", store.apply("owner", "chat-b", latest) { it })
    }

    @Test fun `at most two active previews fit and a committed ticket releases active capacity`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val first = store.put("a", "chat", "one")
        store.put("b", "chat", "two")
        assertTrue(runCatching { store.put("c", "chat", "three") }.isFailure)
        store.apply("a", "chat", first) { "archive-a" }
        val third = store.put("c", "chat", "three")
        assertEquals("three", store.apply("c", "chat", third) { it })
    }

    @Test fun `confirmed repeat returns original receipt without rerunning commit`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("owner", "chat", "preview")
        var calls = 0
        repeat(3) {
            assertEquals("archive-original", store.apply("owner", "chat", id) { calls++; "archive-original" })
        }
        assertEquals(1, calls)
    }

    @Test fun `new preview does not invalidate previous committed receipt`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val old = store.put("owner", "chat", "old")
        store.apply("owner", "chat", old) { "old-archive" }
        val latest = store.put("owner", "chat", "new")
        assertEquals("old-archive", store.apply("owner", "chat", old) { error("must not repeat") })
        assertEquals("new", store.apply("owner", "chat", latest) { it })
    }

    @Test fun `oldest receipt is evicted at eight ticket capacity`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val ids = (0..7).map { n ->
            store.put("owner-$n", "chat", "preview-$n").also { store.apply("owner-$n", "chat", it) { "archive-$n" } }
        }
        val newest = store.put("owner-8", "chat", "preview-8")
        assertTrue(runCatching { store.apply("owner-0", "chat", ids[0]) { "bad" } }.exceptionOrNull() is NotFoundException)
        assertEquals("archive-7", store.apply("owner-7", "chat", ids[7]) { "bad" })
        assertEquals("preview-8", store.apply("owner-8", "chat", newest) { it })
    }

    @Test fun `failed commit invalidates preview and never retries automatically`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("owner", "chat", "preview")
        var calls = 0
        assertTrue(runCatching { store.apply("owner", "chat", id) { calls++; error("synthetic storage failure") } }.isFailure)
        assertTrue(runCatching { store.apply("owner", "chat", id) { calls++; "bad" } }.exceptionOrNull() is ConflictException)
        assertEquals(1, calls)
    }

    @Test fun `explicit cancellation thrown by commit also invalidates preview`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("owner", "chat", "preview")
        assertTrue(runCatching { store.apply("owner", "chat", id) { throw CancellationException("synthetic") } }
            .exceptionOrNull() is CancellationException)
        assertTrue(runCatching { store.apply("owner", "chat", id) { "bad" } }.exceptionOrNull() is ConflictException)
    }

    @Test fun `two simultaneous confirmations commit once and both receive same receipt`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("owner", "chat", "preview")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            store.apply("owner", "chat", id) { calls++; started.complete(Unit); release.await(); "archive" }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.apply("owner", "chat", id) { calls++; "bad" } }
        release.complete(Unit)
        assertEquals("archive", first.await()); assertEquals("archive", second.await())
        assertEquals(1, calls)
    }

    @Test fun `browser cancellation during durable commit preserves receipt for retry`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val id = store.put("owner", "chat", "preview")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            store.apply("owner", "chat", id) { started.complete(Unit); release.await(); calls++; "archive" }
        }
        started.await(); request.cancel(); release.complete(Unit)
        runCatching { request.await() }
        assertEquals("archive", store.apply("owner", "chat", id) { calls++; "bad" })
        assertEquals(1, calls)
    }

    @Test fun `cancelled lock waiter neither commits nor consumes its preview`() = runBlocking {
        val store = OrbisContextPreviewStore<String>()
        val firstId = store.put("owner-a", "chat", "a")
        val secondId = store.put("owner-b", "chat", "b")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            store.apply("owner-a", "chat", firstId) { started.complete(Unit); release.await(); "archive-a" }
        }
        started.await()
        var secondCalls = 0
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            store.apply("owner-b", "chat", secondId) { secondCalls++; "archive-b" }
        }
        waiter.cancelAndJoin()
        assertEquals(0, secondCalls)
        release.complete(Unit); first.await()
        assertEquals("archive-b", store.apply("owner-b", "chat", secondId) { secondCalls++; "archive-b" })
        assertEquals(1, secondCalls)
    }
}
