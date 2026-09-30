package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.roundToInt

/** UI-only editor. The caller owns persistence; null means follow the current theme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrbisChatTextColorEditor(
    color: Int?,
    defaultColor: Color,
    onColorChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultArgb = defaultColor.copy(alpha = 1f).toArgb()
    val initialArgb = color?.opaqueChatTextColor() ?: defaultArgb
    val initialHsl = remember { initialArgb.chatTextHsl() }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsl[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsl[1]) }
    var lightness by rememberSaveable { mutableFloatStateOf(initialHsl[2]) }
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var hexDraft by rememberSaveable { mutableStateOf(initialArgb.chatTextHex()) }
    var lastEmittedColor by rememberSaveable { mutableStateOf<Int?>(null) }

    fun acceptColor(argb: Int) {
        val opaque = argb.opaqueChatTextColor()
        val hsl = opaque.chatTextHsl()
        // Achromatic colors have no defined hue. Retain the last useful hue so
        // increasing saturation after choosing gray does not unexpectedly turn red.
        if (hsl[1] > 0.0001f) hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
        hexDraft = opaque.chatTextHex()
    }

    fun emitColor(argb: Int) {
        val opaque = argb.opaqueChatTextColor()
        lastEmittedColor = opaque
        hexDraft = opaque.chatTextHex()
        onColorChange(opaque)
    }

    fun emitHsl() = emitColor(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))

    LaunchedEffect(color, defaultArgb) {
        val effective = color?.opaqueChatTextColor() ?: defaultArgb
        // An echo of a slider write must not erase hue/saturation at black or
        // white, whose RGB representation cannot preserve those components.
        if (color != null && effective == lastEmittedColor) return@LaunchedEffect
        lastEmittedColor = null
        acceptColor(effective)
    }

    val previewArgb = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
    val parsedHex = parseChatTextHex(hexDraft)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (color == null) "随主题" else previewArgb.chatTextHex(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("chat-text-color-value"),
            )
            TextButton(onClick = {
                lastEmittedColor = null
                acceptColor(defaultArgb)
                onColorChange(null)
            }) { Text("恢复默认") }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "白色" to 0xFFFFFFFF.toInt(),
                "黑色" to 0xFF000000.toInt(),
                "奶油" to 0xFFF5EAD2.toInt(),
                "灰蓝" to 0xFF9DB5CF.toInt(),
            ).forEach { (label, argb) ->
                OutlinedButton(
                    onClick = { acceptColor(argb); emitColor(argb) },
                    modifier = Modifier.testTag("chat-text-color-preset-$label"),
                ) {
                    Box(
                        Modifier.padding(end = 6.dp).size(16.dp)
                            .background(Color(argb), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text(label)
                }
            }
        }

        Text("亮度：${(lightness * 100).roundToInt()}%（0% 黑 · 100% 白）")
        Slider(
            value = lightness,
            onValueChange = { lightness = it; emitHsl() },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().testTag("chat-text-color-lightness")
                .semantics { contentDescription = "聊天字体亮度" },
        )
        Text(
            "亮度调整颜色的明暗，不会让文字变透明。请同时检查深浅背景的可读性。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatTextPreview("深色背景", Color(0xFF17182B), Color.White, Color(previewArgb), Modifier.weight(1f))
            ChatTextPreview("浅色背景", Color(0xFFF8F4EC), Color(0xFF272530), Color(previewArgb), Modifier.weight(1f))
        }

        TextButton(onClick = { customExpanded = !customExpanded }) {
            Text(if (customExpanded) "收起自定义颜色" else "自定义颜色")
        }
        if (customExpanded) {
            Text("色相：${hue.roundToInt()}°")
            Slider(
                value = hue,
                onValueChange = { hue = it; emitHsl() },
                valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth().testTag("chat-text-color-hue")
                    .semantics { contentDescription = "聊天字体色相" },
            )
            Text("饱和度：${(saturation * 100).roundToInt()}%")
            Slider(
                value = saturation,
                onValueChange = { saturation = it; emitHsl() },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().testTag("chat-text-color-saturation")
                    .semantics { contentDescription = "聊天字体饱和度" },
            )
            OutlinedTextField(
                value = hexDraft,
                onValueChange = { hexDraft = it },
                label = { Text("HEX（#RRGGBB）") },
                isError = parsedHex == null,
                supportingText = {
                    Text(if (parsedHex == null) "请输入 #RRGGBB，例如 #F5EAD2；不接受透明度。" else "点击应用后更改聊天字体颜色。")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    parsedHex?.let { acceptColor(it); emitColor(it) }
                }),
                modifier = Modifier.fillMaxWidth().testTag("chat-text-color-hex"),
            )
            Button(
                enabled = parsedHex != null,
                onClick = { parsedHex?.let { acceptColor(it); emitColor(it) } },
                modifier = Modifier.testTag("chat-text-color-apply"),
            ) { Text("应用 HEX 颜色") }
        }
    }
}

@Composable
private fun ChatTextPreview(label: String, background: Color, labelColor: Color, textColor: Color, modifier: Modifier) {
    Column(
        modifier.background(background, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = labelColor, style = MaterialTheme.typography.labelSmall)
        Text("聊天文字 Aa", color = textColor, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("chat-text-color-preview-$label"))
    }
}

private fun Int.opaqueChatTextColor(): Int = this or 0xFF000000.toInt()
private fun Int.chatTextHsl(): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
private fun Int.chatTextHex(): String = String.format(Locale.ROOT, "#%06X", this and 0x00FFFFFF)
private fun parseChatTextHex(text: String): Int? = text.trim().takeIf {
    it.matches(Regex("#[0-9a-fA-F]{6}"))
}?.substring(1)?.toIntOrNull(16)?.opaqueChatTextColor()
