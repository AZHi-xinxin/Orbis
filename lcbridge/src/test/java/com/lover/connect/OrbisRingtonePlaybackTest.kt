package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

class OrbisRingtonePlaybackTest {
    @Test fun localSelectionAcceptsOnlyBoundedContentUris() {
        assertTrue(isLocalRingtoneUri("content://com.android.providers.media.documents/document/audio%3A5"))
        listOf("file:///sdcard/alarm.mp3", "https://example.invalid/a.mp3", "javascript:alert(1)",
            "content:///missing", "content://user:pass@authority/audio", "content://authority/a#fragment",
            "content://authority/" + "a".repeat(8192)).forEach { assertFalse(it, isLocalRingtoneUri(it)) }
    }

    @Test fun incomingAndAlarmCandidatesRemainIndependentAndDeduplicated() {
        assertEquals(listOf("content://local/incoming", "content://settings/ringtone"),
            ringtoneCandidates("content://local/incoming", listOf("content://settings/ringtone", null, "content://settings/ringtone")))
        assertEquals(listOf("content://local/alarm", "content://settings/alarm", "content://settings/ringtone"),
            ringtoneCandidates("content://local/alarm", listOf("content://settings/alarm", "content://settings/ringtone")))
        assertEquals(listOf("content://settings/alarm"),
            ringtoneCandidates("https://example.invalid/a.mp3", listOf("content://settings/alarm")))
    }

    @Test fun startsOnlyAfterPreparedAndStopsIdempotently() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var started = 0
        playback.start(listOf("custom"), { started++ }, { fail("Unexpected failure") })
        assertTrue(playback.isActive)
        assertEquals(0, started)
        assertEquals(0, handles.single().starts)
        handles.single().ready()
        assertEquals(1, started)
        assertEquals(1, handles.single().starts)
        playback.stop()
        playback.stop()
        assertFalse(playback.isActive)
        assertEquals(1, handles.single().releases)
    }

    @Test fun missingPermissionOrFileFallsBackToSystemAndReleasesCustom() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var started = 0
        playback.start(listOf("custom", "system"), { started++ }, { fail("Fallback must work") })
        handles[0].error()
        assertEquals(1, handles[0].releases)
        assertEquals("system", handles[1].source)
        handles[1].ready()
        assertEquals(1, started)
    }

    @Test fun synchronousPrepareAndStartFailuresAlsoFallback() {
        val handles = mutableListOf<FakeHandle>()
        val playback = OrbisRingtonePlayback {
            FakeHandle(failPrepare = handles.isEmpty(), failStart = handles.size == 1).also(handles::add)
        }
        var started = 0
        playback.start(listOf("missing", "broken", "system"), { started++ }, { fail("Third candidate works") })
        assertEquals(2, handles.size)
        handles[1].ready()
        assertEquals(3, handles.size)
        handles[2].ready()
        assertEquals(1, started)
        assertEquals(listOf(1, 1, 0), handles.map { it.releases })
    }

    @Test fun stopWhilePreparingRejectsLateCallbacksWithoutPlayingOrFallback() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        playback.start(listOf("custom", "system"), { fail("Stopped") }, { fail("Stopped") })
        val old = handles.single()
        playback.stop()
        old.ready()
        old.error()
        assertEquals(0, old.starts)
        assertEquals(1, handles.size)
        assertFalse(playback.isActive)
    }

    @Test fun replacingAlarmRejectsOldReadyAndErrorCallbacks() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var replacementStarted = 0
        playback.start(listOf("old", "oldFallback"), { fail("Replaced") }, { fail("Replaced") })
        val old = handles.single()
        playback.start(listOf("new"), { replacementStarted++ }, { fail("Replacement works") })
        old.ready()
        old.error()
        handles[1].ready()
        assertEquals(0, old.starts)
        assertEquals(2, handles.size)
        assertEquals(1, replacementStarted)
    }

    @Test fun staleCustomErrorCannotReplaceAlreadyRunningFallback() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var started = 0
        playback.start(listOf("custom", "system", "last"), { started++ }, { fail("Not exhausted") })
        handles[0].error()
        handles[1].ready()
        handles[0].error()
        assertEquals(2, handles.size)
        assertEquals(1, started)
        assertTrue(playback.isActive)
    }

    @Test fun playbackFailureAfterStartFallsBackWithoutDoubleStartedReceipt() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var started = 0
        playback.start(listOf("custom", "system"), { started++ }, { fail("Fallback exists") })
        handles[0].ready()
        handles[0].error()
        handles[1].ready()
        assertEquals(1, started)
    }

    @Test fun allCandidatesFailOnlyOnceAndNeverRestart() {
        val handles = mutableListOf<FakeHandle>()
        val playback = playback(handles)
        var failed = 0
        playback.start(listOf("custom", "system"), { fail("No success") }, { failed++ })
        handles[0].error()
        handles[1].error()
        handles[1].error()
        handles[0].ready()
        assertEquals(1, failed)
        assertFalse(playback.isActive)
        assertEquals(2, handles.size)
        assertEquals(listOf(1, 1), handles.map { it.releases })
    }

    @Test fun noCandidatesAndFactoryFailureFailClosed() {
        var factories = 0
        val playback = OrbisRingtonePlayback { factories++; throw IllegalStateException("synthetic") }
        var failures = 0
        playback.start(emptyList(), { fail("Empty") }, { failures++ })
        assertEquals(0, factories)
        playback.start(listOf("custom", "system"), { fail("Factory failed") }, { failures++ })
        assertEquals(2, factories)
        assertEquals(2, failures)
        assertFalse(playback.isActive)
    }

    private fun playback(handles: MutableList<FakeHandle>) = OrbisRingtonePlayback { FakeHandle().also(handles::add) }
    private class FakeHandle(private val failPrepare: Boolean = false, private val failStart: Boolean = false) : RingtonePlaybackHandle {
        var source = ""
        var starts = 0
        var releases = 0
        lateinit var ready: () -> Unit
        lateinit var error: () -> Unit
        override fun prepare(source: String, onReady: () -> Unit, onError: () -> Unit) {
            this.source = source
            ready = onReady
            error = onError
            if (failPrepare) throw SecurityException("synthetic revoked permission")
        }
        override fun start() {
            if (failStart) throw IllegalStateException("synthetic decoder failure")
            starts++
        }
        override fun release() { releases++ }
    }
}
