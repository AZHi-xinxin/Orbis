package me.rerere.tts.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local playback coordination only; never changes system volume or owns audio focus. */
object SpeechPlaybackActivity {
    private val speakers = mutableSetOf<Any>()
    private val mutable = MutableStateFlow(false)
    val active = mutable.asStateFlow()
    @Synchronized fun update(owner: Any, playing: Boolean) {
        if(playing) speakers.add(owner) else speakers.remove(owner)
        mutable.value = speakers.isNotEmpty()
    }
}
