package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreenShareObservationCacheTest {
    @Test fun foregroundAndObserverShareOneInFlightDescription() = runBlocking {
        val cache = ScreenShareObservationCache<String>()
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<String>()
        var requests = 0
        val observer = async { cache.get("frame/config", {}) { requests++; started.complete(Unit); response.await() } }
        started.await()
        val foreground = async { cache.get("frame/config", {}) { requests++; "duplicate" } }
        response.complete("actual description")
        assertEquals("actual description", observer.await()); assertEquals(observer.await(), foreground.await())
        assertEquals(1, requests)
    }

    @Test fun revokedInFlightResultAndCachedResultCannotReachChat() = runBlocking {
        val cache = ScreenShareObservationCache<String>()
        var permitted = true
        fun checkAccess() { check(permitted) }
        cache.get("cached", ::checkAccess) { "earlier" }
        permitted = false
        try { cache.get("cached", ::checkAccess) { error("must not request") }; fail("revoked cache") }
        catch (_: IllegalStateException) { }
        permitted = true
        try { cache.get("pending", ::checkAccess) { cache.clear(); permitted = false; "late private data" }; fail("late result") }
        catch (_: IllegalStateException) { }
        permitted = true
        assertEquals("fresh", cache.get("pending", ::checkAccess) { "fresh" })
    }

    @Test fun emptyFailureAndCancellationAreNotCached() = runBlocking {
        val cache = ScreenShareObservationCache<String>()
        for (kind in 0..2) {
            try { cache.get("frame", {}) { when (kind) { 0 -> " "; 1 -> throw IllegalStateException(); else -> throw CancellationException() } }; fail("invalid result") }
            catch (_: IllegalStateException) { }
        }
        assertEquals("recovered", cache.get("frame", {}) { "recovered" })
    }

    @Test fun changedConfigurationAndEvictedFramesNeedFreshDescriptions() = runBlocking {
        val cache = ScreenShareObservationCache<String>(2)
        assertEquals("one", cache.get("frame/config1", {}) { "one" })
        assertEquals("two", cache.get("frame/config2", {}) { "two" })
        cache.get("next/config2", {}) { "next" }
        assertEquals("refreshed", cache.get("frame/config1", {}) { "refreshed" })
        cache.clear()
        assertEquals("new session", cache.get("frame/config2", {}) { "new session" })
    }
}
