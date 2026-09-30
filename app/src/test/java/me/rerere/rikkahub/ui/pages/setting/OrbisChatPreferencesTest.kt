package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OrbisChatPreferencesTest {
    @Test fun eachToggleOnlyChangesItsOwnFieldAndIsReversible() {
        val before = DisplaySetting(userNickname = "synthetic", fontSizeRatio = 1.2f,
            ttsOnlyReadQuoted = true, pasteLongTextThreshold = 2300, volumeKeyScrollRatio = .5f)
        for (preference in OrbisChatPreference.entries) {
            val after = before.withOrbisPreference(preference, !before.orbisPreferenceValue(preference))
            assertNotEquals(before.orbisPreferenceValue(preference), after.orbisPreferenceValue(preference))
            OrbisChatPreference.entries.filter { it != preference }.forEach {
                assertEquals(before.orbisPreferenceValue(it), after.orbisPreferenceValue(it))
            }
            assertEquals(before, after.withOrbisPreference(preference, before.orbisPreferenceValue(preference)))
            assertEquals("synthetic", after.userNickname)
            assertEquals(1.2f, after.fontSizeRatio, 0f)
            assertTrue(after.ttsOnlyReadQuoted)
            assertEquals(2300, after.pasteLongTextThreshold)
            assertEquals(.5f, after.volumeKeyScrollRatio, 0f)
        }
    }

    @Test fun consecutiveChangesPreserveOtherSettings() {
        val result = DisplaySetting().withOrbisPreference(OrbisChatPreference.ENTER, true)
            .withOrbisPreference(OrbisChatPreference.VOLUME_SCROLL, true)
            .withOrbisPreference(OrbisChatPreference.USAGE, false)
        assertTrue(result.sendOnEnter)
        assertTrue(result.enableVolumeKeyScroll)
        assertFalse(result.showTokenUsage)
    }

    @Test fun normalPhoneUsesTwoReadableColumns() {
        assertEquals(2, orbisChatPreferenceColumns(390f, 1f))
        assertEquals(2, orbisChatPreferenceColumns(340f, 1.4f))
    }

    @Test fun narrowOrLargeTextFallsBackToOneColumn() {
        assertEquals(1, orbisChatPreferenceColumns(320f, 1f))
        assertEquals(1, orbisChatPreferenceColumns(390f, 1.6f))
        assertEquals(1, orbisChatPreferenceColumns(800f, 1.6f))
    }

    @Test fun wideNormalTextMayUseThreeColumns() {
        assertEquals(3, orbisChatPreferenceColumns(680f, 1f))
        assertEquals(2, orbisChatPreferenceColumns(680f, 1.3f))
    }

    @Test fun preferenceIdentityAndLabelsAreUnique() {
        assertEquals(11, OrbisChatPreference.entries.size)
        assertEquals(11, OrbisChatPreference.entries.map { it.title }.toSet().size)
        assertTrue(OrbisChatPreference.entries.all { it.title.isNotBlank() && it.description.isNotBlank() })
    }

    @Test fun startedWriteFinishesDespiteThePageScopeBeingCancelled() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val allowDiskWrite = CompletableDeferred<Unit>()
            var diskValue = false
            val job = launch {
                persistOrbisPreferenceChange {
                    started.complete(Unit)
                    allowDiskWrite.await()
                    diskValue = true
                }
            }
            started.await()
            job.cancel()
            allowDiskWrite.complete(Unit)
            job.join()
            assertTrue(diskValue)
        }
    }

    @Test fun cancelledPageCannotBeginANewWrite() = runBlocking {
        var writes = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            persistOrbisPreferenceChange { writes++ }
        }
        job.join()
        assertEquals(0, writes)
    }
}
