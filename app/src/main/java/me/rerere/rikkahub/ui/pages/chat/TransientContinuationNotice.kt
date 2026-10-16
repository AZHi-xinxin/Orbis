package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Presentation only: hiding a notice must not release a recovery hold or claim a save. */
@Composable
internal fun TransientContinuationNotice(notice: String?) {
    var visible by rememberSaveable(notice) { mutableStateOf(notice != null) }
    LaunchedEffect(notice) {
        if (notice != null) { delay(4_000); visible = false }
    }
    if (visible && notice != null) Text(notice, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}
