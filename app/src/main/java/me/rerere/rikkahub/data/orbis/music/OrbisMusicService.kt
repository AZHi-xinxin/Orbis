package me.rerere.rikkahub.data.orbis.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

/** The service owns audio; UI visibility and model requests never own its lifetime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OrbisMusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var runtime: MusicRuntime
    private val checkpoints = kotlinx.coroutines.channels.Channel<MusicPlayback>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        runtime = MusicRuntime.get(this)
        player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)!!
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 153, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if(controller.uid != applicationInfo.uid && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    val base = super.onConnect(session, controller)
                    if(controller.uid == applicationInfo.uid) return base
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(base.availablePlayerCommands.buildUpon()
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()).build()
                }
            }).build()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for(p in checkpoints) runCatching { runtime.store.checkpoint(p.queue, p.index, p.position, p.repeat, runtime.store.state.value.selectedBy) }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                val changed = events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                runtime.publish(p, changed)
                if(changed) checkpoint()
            }
        })
        val saved = runtime.store.state.value
        val items = saved.queue.mapNotNull { id -> runCatching { mediaItem(runtime.store, runtime.store.track(id)) }.getOrNull() }
        if(items.isNotEmpty()) {
            player.setMediaItems(items, saved.current.coerceIn(items.indices), saved.position)
            player.repeatMode = if(saved.repeat == "one") Player.REPEAT_MODE_ONE else if(saved.repeat == "off") Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = saved.repeat == "shuffle"
            // Restore paused. Starting the app or restoring a queue must not start sound.
        }
        scope.launch {
            me.rerere.tts.controller.SpeechPlaybackActivity.active.collect { speaking -> player.volume = if(speaking) .18f else 1f }
        }
        scope.launch {
            var ticks = 0
            while(isActive) { delay(if(player.isPlaying) 500 else 2000); runtime.publish(player, false); if(player.isPlaying && ++ticks % 20 == 0) checkpoint() }
        }
    }
    private fun checkpoint() {
        val p = runtime.playback.value
        checkpoints.trySend(p)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if(!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        player.pause(); runtime.publish(player, true); checkpoint(); checkpoints.close()
        session?.release(); player.release(); scope.cancel(); session = null
        super.onDestroy()
    }
}
