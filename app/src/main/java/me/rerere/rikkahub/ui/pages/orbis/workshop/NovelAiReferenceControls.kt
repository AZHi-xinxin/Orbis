package me.rerere.rikkahub.ui.pages.orbis.workshop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun NovelAiReferenceControls(preciseSupported: Boolean, mode: String, strength: Float,
    enabled: Boolean, onMode: (String) -> Unit, onStrength: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("参考图的用法", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "img2img", onClick = { onMode("img2img") }, enabled = enabled,
                label = { Text("原图重绘") })
            if (preciseSupported) FilterChip(selected = mode == "character&style", onClick = { onMode("character&style") },
                enabled = enabled, label = { Text("角色与画风参考") })
        }
        Text(if (mode == "img2img") "沿用原图构图，再按描述重绘。重绘幅度越低，越接近原图；越高，改动越大。"
            else "参考角色外貌与画风重新创作。参考强度越高，越贴近原图。", style = MaterialTheme.typography.bodySmall)
        Text("${if (mode == "img2img") "重绘幅度" else "参考强度"}：${(strength * 100).roundToInt()}%")
        Slider(value = strength, onValueChange = onStrength, enabled = enabled, valueRange = 0f..1f,
            modifier = Modifier.testTag("novelai-reference-strength"))
        Text(if (mode == "img2img") "图生图会消耗 Anlas，不属于 Opus 免费文生图。原文件保留，结果另存。"
            else "精确参考仅限 NAI 4.5，每张参考图额外消耗 5 Anlas。原文件保留，结果另存。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}
