package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** The chosen mode is explicit: an ORPV1-prefixed human password is still a password. */
internal enum class PrivateRoomRecoveryMethod { PASSWORD, RANDOM_CODE }

internal fun privateRoomPasswordValidation(password: String, confirmation: String): String? = when {
    password.length !in 8..128 -> "恢复密码需要 8–128 个字符，可使用中文；空格和大小写会原样保留。"
    password != confirmation -> "两次输入的恢复密码不一致，请核对后再保存。"
    else -> null
}

/** Validation never includes the password; even cancellation releases this temporary copy. */
internal suspend fun <T> privateRoomUsePassword(password: CharArray, action: suspend (CharArray) -> T): T =
    try { action(password) } finally { password.fill('\u0000') }

@Composable
internal fun PrivateRoomSecretField(value: String, onValueChange: (String) -> Unit, label: String,
                                    visible: Boolean, onVisibilityChange: () -> Unit,
                                    enabled: Boolean, tag: String) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            TextButton(enabled = enabled, modifier = Modifier.testTag("$tag-visibility"),
                onClick = onVisibilityChange) { Text(if (visible) "隐藏" else "显示") }
        }, modifier = Modifier.fillMaxWidth().testTag(tag), singleLine = true, enabled = enabled)
}
