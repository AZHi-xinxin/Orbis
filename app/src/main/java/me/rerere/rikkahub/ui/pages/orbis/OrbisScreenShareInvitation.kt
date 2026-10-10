package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.service.OrbisScreenShareRuntime

@Composable
fun OrbisScreenShareInvitation() {
    val context = LocalContext.current
    val runtime = remember { OrbisScreenShareRuntime.get(context) }
    val request by runtime.invitation.collectAsStateWithLifecycle()
    request?.let { invitation ->
        AlertDialog(onDismissRequest = { runtime.declineInvitation(invitation.id) }, title = { Text("一起看屏幕？") },
            text = { Text(invitation.reason + "\n\n接受后仍需你在系统界面选择共享范围。默认不开麦克风。") },
            confirmButton = { TextButton(onClick = { runtime.acceptInvitation(invitation.id)?.let(context::startActivity) }) { Text("接受") } },
            dismissButton = { TextButton(onClick = { runtime.declineInvitation(invitation.id) }) { Text("暂时不用") } })
    }
}
