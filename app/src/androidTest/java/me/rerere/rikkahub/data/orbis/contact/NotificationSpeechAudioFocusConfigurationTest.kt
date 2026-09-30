package me.rerere.rikkahub.data.orbis.contact

import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Constructs configuration only: no AudioManager request, speaker, TTS, notification or phone data. */
@RunWith(AndroidJUnit4::class)
class NotificationSpeechAudioFocusConfigurationTest {
    @Test fun notificationSpeechRequestsTransientSpeechFocusWithBoundedDelayedSupport() {
        val request = notificationSpeechFocusRequest { error("Constructing a request must not deliver focus") }
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertTrue(request.acceptsDelayedFocusGain())
        assertTrue(request.willPauseWhenDucked())
    }

    @Test fun platformTransientLossAndDuckAreNotMappedAsPermanentLoss() {
        assertEquals(NotificationSpeechFocusChange.GAIN, notificationSpeechFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(NotificationSpeechFocusChange.TRANSIENT_LOSS,
            notificationSpeechFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(NotificationSpeechFocusChange.DUCK,
            notificationSpeechFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(NotificationSpeechFocusChange.LOSS, notificationSpeechFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertNull(notificationSpeechFocusChange(0))
    }
}
