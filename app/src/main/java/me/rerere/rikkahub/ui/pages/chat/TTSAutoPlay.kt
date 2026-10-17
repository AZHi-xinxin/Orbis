package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.service.OrbisScreenShareSpeechOutput
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.AutomaticSpeechDelivery
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent

@Composable
fun TTSAutoPlay(vm: ChatVM, setting: Settings, conversation: Conversation) {
    // Auto-play TTS after generation completes
    val tts = LocalTTSState.current
    val applicationContext = LocalContext.current.applicationContext
    val currentConversation by rememberUpdatedState(conversation)
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(Unit) {
        val delivery = AutomaticSpeechDelivery()
        vm.generationDoneFlow.collect { event ->
            if (event.conversationId == currentConversation.id && delivery.claim(event,
                enabled = updatedSetting.displaySetting.autoPlayTTSAfterGeneration,
                current = vm.isCurrentGenerationDone(event),
                voiceActive = vm.voiceSession.state.value.isActive,
                screenOwnsSpeech = OrbisScreenShareSpeechOutput.get(applicationContext)
                    .ownsConversation(event.conversationId.toString()))) {
                // The event contains only the completed public reply, never reasoning/tools.
                var textToSpeak = event.text
                if (updatedSetting.displaySetting.ttsOnlyReadQuoted) {
                    textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                }
                if (updatedSetting.displaySetting.ttsOnlyReadOutsideBrackets) {
                    textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                }
                if (textToSpeak.isNotBlank()) {
                    tts.speak(textToSpeak)
                }
            }
        }
    }
}
