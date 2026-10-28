package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.integration.ConnectionSetupRequest
import me.rerere.rikkahub.data.orbis.integration.connectionSetupQueue

/** Open only in the originating visible chat, never switch assistants or interrupt a call. */
@Composable
internal fun OrbisConnectionSetupPrompt(owner: String, conversation: String, settings: Settings, inCall: Boolean) {
    val store: me.rerere.rikkahub.data.datastore.SettingsStore = org.koin.compose.koinInject()
    val requests by connectionSetupQueue.requests.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var visible by remember(owner, conversation) { mutableStateOf<ConnectionSetupRequest?>(null) }
    LaunchedEffect(requests, owner, conversation, lifecycle, inCall, settings.init) {
        val current = store.settingsFlow.value // A tool completion can precede the UI's settings collection.
        if (!current.init && !inCall && lifecycle == Lifecycle.State.RESUMED && visible == null &&
            current.assistants.any { it.id.toString() == owner }) {
            visible = connectionSetupQueue.take(owner, conversation, current.connectionProposals)
        }
    }
    LaunchedEffect(settings.connectionProposals) {
        val request = visible
        if (request?.proposal != null && settings.connectionProposals.none { it.owner == owner && it.id == request.proposal.id && it.setupPromptPending }) {
            connectionSetupQueue.dismiss(owner, conversation); visible = null
        }
    }
    visible?.let { request -> key(owner, conversation, request.proposal?.id ?: "guide") {
        OrbisConnectionProposals(owner, onClose = {
            connectionSetupQueue.dismiss(owner, conversation)
            visible = null
        }, initialRequest = request.proposal, initialGuide = request.guide)
    } }
}
