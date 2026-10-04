package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisGalleryFirstFrameTest {
    private val url = "https://gallery-fixture.orbis-gallery.invalid/artwork.html"

    @Test fun initialFrameIsCoveredAndPageFinishedAloneDoesNotRevealIt() {
        val frame = GalleryFirstFrame(url)
        assertTrue(frame.covered)
        assertNotNull(frame.requestAfterPageFinished(url))
        assertTrue(frame.covered)
    }

    @Test fun unrelatedUrlsAndUnsolicitedOrWrongAcknowledgementsCannotRevealTheFrame() {
        val frame = GalleryFirstFrame(url)
        listOf(null, "about:blank", "$url#fragment", "https://example.invalid/").forEach {
            assertNull(frame.requestAfterPageFinished(it))
        }
        assertFalse(frame.acknowledgeVisualState(1L))
        val request = requireNotNull(frame.requestAfterPageFinished(url))
        assertFalse(frame.acknowledgeVisualState(request + 1))
        assertTrue(frame.covered)
        assertTrue(frame.acknowledgeVisualState(request))
        assertFalse(frame.covered)
    }

    @Test fun aVisibleArtworkIsNotCoveredAgainByDuplicateFinishOrFullscreenResize() {
        val frame = GalleryFirstFrame(url)
        val request = requireNotNull(frame.requestAfterPageFinished(url))
        assertTrue(frame.acknowledgeVisualState(request))
        repeat(3) {
            assertNull(frame.requestAfterPageFinished(url))
            assertFalse(frame.acknowledgeVisualState(request))
            assertFalse(frame.covered)
        }
    }

    @Test fun failureAndReleaseAreTerminalEvenWithAQueuedVisualCallback() {
        val frame = GalleryFirstFrame(url)
        val request = requireNotNull(frame.requestAfterPageFinished(url))
        assertTrue(frame.stop())
        assertFalse(frame.stop())
        assertFalse(frame.acknowledgeVisualState(request))
        assertNull(frame.requestAfterPageFinished(url))
        assertTrue(frame.covered)
    }

    @Test fun eachNewArtworkMustReceiveItsOwnAcknowledgement() {
        val first = GalleryFirstFrame(url)
        first.acknowledgeVisualState(requireNotNull(first.requestAfterPageFinished(url)))
        assertFalse(first.covered)
        val reopened = GalleryFirstFrame(url)
        assertTrue(reopened.covered)
        assertFalse(reopened.acknowledgeVisualState(1L))
        first.stop()
        assertTrue(reopened.covered)
        reopened.acknowledgeVisualState(requireNotNull(reopened.requestAfterPageFinished(url)))
        assertFalse(reopened.covered)
    }

    @Test fun failureAfterFirstFrameReCoversAndCannotReviveIt() {
        val frame = GalleryFirstFrame(url)
        val request = requireNotNull(frame.requestAfterPageFinished(url))
        frame.acknowledgeVisualState(request)
        assertTrue(frame.stop())
        assertTrue(frame.covered)
        assertFalse(frame.acknowledgeVisualState(request))
    }

    @Test fun deferredDetachedFrameRequiresANewAcknowledgementNotAnOldCallback() {
        val frame = GalleryFirstFrame(url)
        val oldRequest = requireNotNull(frame.requestAfterPageFinished(url))
        frame.deferVisualState(oldRequest)
        assertTrue(frame.covered)
        assertFalse(frame.acknowledgeVisualState(oldRequest))
        val newRequest = requireNotNull(frame.requestAfterPageFinished(url))
        assertNotEquals(oldRequest, newRequest)
        assertFalse(frame.acknowledgeVisualState(oldRequest))
        assertTrue(frame.acknowledgeVisualState(newRequest))
        assertFalse(frame.covered)
    }
}
