package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.update.OrbisPublishedRelease
import me.rerere.rikkahub.data.update.OrbisUpdateService

/** The service throttles across app restarts. Failed checks never interrupt ordinary chat. */
@Composable
internal fun OrbisUpdateReminder(enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf<OrbisPublishedRelease?>(null) }
    LaunchedEffect(enabled) {
        if (enabled) try {
            val result = OrbisUpdateService(context).check()
            if (result.updateAvailable) pending = result.latest
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Manual retry remains in settings. */ }
    }
    if (enabled) pending?.let { OrbisUpdateDialog(onDismiss = { pending = null }, initialRelease = it) }
}
