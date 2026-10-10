package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.service.OrbisScreenShareSpeechOutput
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomAutomaticSpeechText
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
        vm.generationDoneFlow.collect { conversationId ->
            if (conversationId == currentConversation.id &&
                !vm.voiceSession.state.value.isActive &&
                !OrbisScreenShareSpeechOutput.get(applicationContext).ownsConversation(conversationId.toString()) &&
                updatedSetting.displaySetting.autoPlayTTSAfterGeneration
            ) {
                val lastMessage = currentConversation.currentMessages.lastOrNull()
                // Private tools hide their details, not the assistant's public reply Text.
                val text = lastMessage?.privateRoomAutomaticSpeechText()
                if (text != null) {
                    var textToSpeak = text
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
}
