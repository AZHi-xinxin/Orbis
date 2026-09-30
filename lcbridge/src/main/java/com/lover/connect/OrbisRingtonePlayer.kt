package com.lover.connect

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** No automatic replay on process restart. All content-provider/decoder work runs off the UI thread. */
class OrbisRingtonePlayer(context: Context) {
    private val context = context.applicationContext
    private val revision = AtomicLong()
    private var playback: OrbisRingtonePlayback? = null // Worker-thread only.
    @Volatile var isActive: Boolean = false
        private set

    fun startIncoming(onStarted: () -> Unit = {}, onFailure: () -> Unit = {}) =
        start(OrbisRingtoneKind.INCOMING, onStarted, onFailure)

    fun startAlarm(onStarted: () -> Unit = {}, onFailure: () -> Unit = {}) =
        start(OrbisRingtoneKind.ALARM, onStarted, onFailure)

    fun stop() {
        revision.incrementAndGet()
        isActive = false
        worker.post { playback?.stop(); playback = null }
    }

    private fun start(kind: OrbisRingtoneKind, onStarted: () -> Unit, onFailure: () -> Unit) {
        val token = revision.incrementAndGet()
        isActive = true
        worker.post {
            if (revision.get() != token) return@post
            playback?.stop()
            fun dispatch(callback: () -> Unit) {
                main.post { if (revision.get() == token) callback() }
            }
            try {
                val custom = OrbisRingtonePreferences(context).get(kind)?.uri
                val defaults = if (kind == OrbisRingtoneKind.ALARM)
                    listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
                else listOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
                val candidates = ringtoneCandidates(custom, defaults.map { RingtoneManager.getDefaultUri(it)?.toString() })
                val usage = if (kind == OrbisRingtoneKind.ALARM) AudioAttributes.USAGE_ALARM
                    else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                OrbisRingtonePlayback { AndroidHandle(usage) }.also { playback = it }
                    .start(candidates, { dispatch(onStarted) }, {
                        if (revision.get() == token) { isActive = false; dispatch(onFailure) }
                    })
            } catch (_: Exception) {
                playback?.stop()
                if (revision.get() == token) { isActive = false; dispatch(onFailure) }
            }
        }
    }

    private inner class AndroidHandle(usage: Int) : RingtonePlaybackHandle {
        private val player = MediaPlayer().also { player ->
            try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                player.isLooping = true
            } catch (failure: Exception) {
                runCatching { player.release() }
                throw failure
            }
        }
        private var timeout: Runnable? = null
        override fun prepare(source: String, onReady: () -> Unit, onError: () -> Unit) {
            val expired = Runnable(onError)
            timeout = expired
            player.setOnErrorListener { _, _, _ -> worker.removeCallbacks(expired); onError(); true }
            player.setOnPreparedListener { worker.removeCallbacks(expired); onReady() }
            // URI permission loss and missing documents throw here and select the next default.
            player.setDataSource(context, Uri.parse(source))
            worker.postDelayed(expired, 8_000)
            player.prepareAsync()
        }
        override fun start() = player.start()
        override fun release() {
            timeout?.let(worker::removeCallbacks)
            player.setOnErrorListener(null)
            player.setOnPreparedListener(null)
            player.release()
        }
    }

    companion object {
        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val worker by lazy { Handler(HandlerThread("OrbisRingtone").apply { start() }.looper) }
    }
}
