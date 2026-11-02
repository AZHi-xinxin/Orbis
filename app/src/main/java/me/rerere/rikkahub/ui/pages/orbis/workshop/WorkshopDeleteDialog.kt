package me.rerere.rikkahub.ui.pages.orbis.workshop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun WorkshopDeleteDialog(imageOnly: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (imageOnly) "删除这张工坊图片？" else "删除这条制作记录？") },
        text = { Text(if (imageOnly)
            "这张图片会从工坊移除，提示词和同批其他图片保留。已发送到聊天或仍被其他作品引用的原图会保留；其余原图将从本机删除。此操作不能撤销。"
        else "这条记录的提示词和图片会从工坊移除。已发送到聊天或仍被其他作品引用的原图会保留；其余原图将从本机删除。此操作不能撤销，也不会重新制作。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
