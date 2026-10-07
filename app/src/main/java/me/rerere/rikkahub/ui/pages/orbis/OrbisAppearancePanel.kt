package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.model.withAppearanceForStyle
import me.rerere.rikkahub.data.model.deepSeekDefaultAppearance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.dokar.sonner.ToastType
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBackgroundStyle
import me.rerere.rikkahub.data.model.OrbisBubbleStyle
import me.rerere.rikkahub.data.model.shouldAnimateOrbisStars
import me.rerere.rikkahub.data.model.copyOrbisImage
import me.rerere.rikkahub.data.model.ORBIS_IMAGE_MAX_BYTES
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.message.OrbisMessageBubble
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import org.koin.compose.koinInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LocalInlineAppearance = staticCompositionLocalOf { false }

/** Drawer-only editor. Updates are transforms so delayed picker results do not replace other preferences. */
@Composable
fun OrbisAppearancePanel(
    displaySetting: DisplaySetting,
    assistant: Assistant?,
    onUpdateDisplay: ((DisplaySetting) -> DisplaySetting) -> Unit,
    onUpdateUserNickname: (String) -> Unit,
    onUpdateAssistantAvatar: (Avatar) -> Unit,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    showDoneButton: Boolean = true,
) {
    if (!BuildConfig.ORBIS_ENABLED) return
    val deepSeek = LocalOrbisDeepSeekStyle.current
    val appearance = displaySetting.appearanceForStyle(deepSeek)
    val colors = OrbisTheme.colors
    var colorMode by rememberColorMode()
    val userName = displaySetting.userNickname.ifBlank { "我" }
    val assistantName = assistant?.name?.ifBlank { "当前 AI" } ?: "当前 AI"
    fun updateAppearance(transform: (OrbisAppearance) -> OrbisAppearance) {
        onUpdateDisplay { old -> old.withAppearanceForStyle(deepSeek, transform) }
    }

    val typography = MaterialTheme.typography
    MaterialTheme(typography = typography.copy(
        bodyMedium = typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
        bodySmall = typography.bodySmall.copy(fontSize = if (embedded) 9.sp else 11.sp, lineHeight = if (embedded) 13.sp else 15.sp),
        titleSmall = typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        titleMedium = typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelLarge = typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 17.sp),
    )) {
    CompositionLocalProvider(LocalInlineAppearance provides embedded) {
    Surface(modifier = modifier, color = colors.panel, contentColor = colors.ink,
        shape = RoundedCornerShape(18.dp), border = if (embedded) BorderStroke(1.dp, colors.border) else null) {
    Column(
        modifier = if (embedded) Modifier.padding(12.dp) else Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) TextButton(onClick = onBack) { Text("‹ 回到会话") }
            Text(if (embedded) "外观 DIY · 即时预览" else "外观 DIY", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text("外观更改即时保存；昵称需点“保存昵称”。头像与背景不改变聊天、模型或身份。", style = MaterialTheme.typography.bodySmall,
            color = colors.mutedInk)

        if (!embedded) AppearanceCard("即时预览") {
            Box(Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(16.dp)).background(colors.page),
                contentAlignment = Alignment.Center) {
                if (!appearance.backgroundEnabled && !assistant?.background.isNullOrBlank()) {
                    val context = LocalContext.current
                    val request = remember(context, assistant?.background) {
                        ImageRequest.Builder(context).data(assistant?.background).size(800, 960).build()
                    }
                    val opacity = assistant?.backgroundOpacity?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                    AsyncImage(request, null, Modifier.matchParentSize().alpha(opacity), contentScale = ContentScale.Crop)
                }
                OrbisChatBackdrop(appearance, Modifier.matchParentSize(), isVisible = false,
                    drawBackground = appearance.backgroundEnabled || assistant?.background.isNullOrBlank())
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PreviewBubble("星光在这里。", appearance, displaySetting, false)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        PreviewBubble("仅外观预览", appearance, displaySetting, true)
                    }
                }
            }
        }

        AppearanceCard("个人设置 · 昵称与头像") {
            OrbisNicknameSetting(displaySetting.userNickname, onUpdateUserNickname)
            if (deepSeek) {
                AppearanceToggle("DS 主题显示头像", displaySetting.deepSeekShowAvatars) { value ->
                    onUpdateDisplay { it.copy(deepSeekShowAvatars = value) }
                }
                AppearanceToggle("DS 主题思考默认收起", displaySetting.deepSeekCollapseThinking) { value ->
                    onUpdateDisplay { it.copy(deepSeekCollapseThinking = value) }
                }
            }
            AvatarChoices(userName, displaySetting.userAvatar, listOf(userName.take(1), "🌸", "☁")) { value ->
                onUpdateDisplay { it.copy(userAvatar = value) }
            }
            AppearanceToggle("显示用户头像", displaySetting.showUserAvatar) { checked ->
                onUpdateDisplay { it.copy(showUserAvatar = checked) }
            }
        }
        AppearanceCard("当前 AI 头像") {
            if (assistant != null) {
                Text(assistantName, style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                AvatarChoices(assistantName, assistant.avatar, listOf(assistantName.take(1), "🌙", "✦"),
                    onUpdateAssistantAvatar)
                Text("只修改当前会话所属 AI 的头像；主动选择后用于聊天头像。",
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
            } else {
                Text("此会话尚未关联可编辑的 AI 配置。", style = MaterialTheme.typography.bodySmall)
            }
        }
        AppearanceCard("聊天背景") {
            if (LocalOrbisSeason.current != OrbisSeason.NONE) {
                Text("${LocalOrbisSeason.current.title} · 自带季节壁纸，自选图片仍可覆盖。", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                TextButton(onClick = { updateAppearance { it.copy(backgroundEnabled = true, backgroundStyle = OrbisBackgroundStyle.PAPER, backgroundImage = null) } }) {
                    Text("使用当前季节壁纸")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrbisBackgroundStyle.entries.forEach { style ->
                    BackgroundSwatch(style, appearance.backgroundEnabled && appearance.backgroundImage == null && appearance.backgroundStyle == style) {
                        updateAppearance { it.copy(backgroundEnabled = true, backgroundStyle = style, backgroundImage = null) }
                    }
                }
            }
            CroppedBackgroundButton { image ->
                updateAppearance { it.copy(backgroundEnabled = true, backgroundImage = image) }
            }
            if (appearance.backgroundImage != null) {
                TextButton(onClick = { updateAppearance { it.copy(backgroundImage = null) } }) { Text("移除自选背景，使用当前底色") }
            }
            TextButton(onClick = { updateAppearance { it.copy(backgroundEnabled = false) } }) {
                Text("沿用当前 AI 原背景")
            }
            Text(if (!appearance.backgroundEnabled) "正沿用原背景；未设置原图时显示纸页底色。" else "正在使用 Orbis 自选背景，原背景仍保留。",
                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("气泡款式") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrbisBubbleStyle.entries.forEach { style ->
                    FilterChip(selected = appearance.bubbleStyle == style,
                        onClick = { updateAppearance { it.copy(bubbleStyle = style) } }, label = { Text(style.label()) })
                }
            }
        }
        AppearanceCard("星光与动效") {
            AppearanceToggle(if (LocalOrbisSeason.current == OrbisSeason.NONE) "向上漂浮的星光" else "季节漂浮物", appearance.floatingStars) { checked ->
                updateAppearance { it.copy(floatingStars = checked) }
            }
            AppearanceToggle("减少动效", appearance.reduceMotion) { checked ->
                updateAppearance { it.copy(reduceMotion = checked) }
            }
            Text("省电模式、系统关闭动画或页面不在前台时，星光保持静止。",
                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("聊天字体") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ChatFontFamily.DEFAULT, ChatFontFamily.SERIF, ChatFontFamily.MONOSPACE).forEach { family ->
                    FilterChip(selected = displaySetting.chatFontFamily == family,
                        onClick = { onUpdateDisplay { it.copy(chatFontFamily = family) } },
                        label = { Text(when (family) { ChatFontFamily.SERIF -> "宋体"; ChatFontFamily.MONOSPACE -> "等宽"; else -> "系统" }) })
                }
                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                    FilterChip(selected = displaySetting.chatFontFamily == ChatFontFamily.CUSTOM,
                        onClick = { onUpdateDisplay { it.copy(chatFontFamily = ChatFontFamily.CUSTOM) } }, label = { Text("已上传字体") })
                }
            }
            Text("自定义字体上传与字号见下方“外观高级”。", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("气泡不透明度") {
            OrbisBubbleOpacityControls(appearance, ::updateAppearance)
            Text("0% 为全透明，气泡底色、边框和阴影都隐藏。文字看不清时，可在“外观高级 → 界面偏好”调整聊天文字颜色与亮度。", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("聊天输入框底色") {
            Text("背景不透明度 ${(appearance.composerOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = appearance.composerOpacity,
                onValueChange = { value -> updateAppearance { it.copy(composerOpacity = value) } },
                valueRange = .15f..1f, steps = 16,
                modifier = Modifier.semantics { contentDescription = "聊天输入框背景不透明度，百分之十五到百分之一百" })
            Text("向左更通透，向右底色更实。文字与图标保持原有颜色；复杂背景建议提高不透明度。",
                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("顶部导航底色") {
            Text("背景不透明度 ${(appearance.headerOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = appearance.headerOpacity,
                onValueChange = { value -> updateAppearance { it.copy(headerOpacity = value) } },
                valueRange = 0f..1f, steps = 19,
                modifier = Modifier.semantics { contentDescription = "顶部导航背景不透明度，百分之零到百分之一百" })
            Text("调整名称、用量、列表和菜单的底色；文字、图标与描边保持主题配色。换壁纸不会切换日夜风格。",
                style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        AppearanceCard("日夜与主题") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorMode.entries.forEach { mode ->
                    FilterChip(selected = colorMode == mode, onClick = { colorMode = mode },
                        label = { Text(when (mode) { ColorMode.SYSTEM -> "随系统"; ColorMode.LIGHT -> "白天"; ColorMode.DARK -> "夜晚" }) })
                }
            }
            TextButton(onClick = { onNavigate(Screen.SettingTheme) }) { Text("主题配色与自定义 ›") }
        }
        AppearanceCard("外观高级") {
            TextButton(onClick = { onNavigate(Screen.SettingPreferencesTheme) }) { Text("主题与深色细节 ›") }
            TextButton(onClick = { onNavigate(Screen.SettingPreferencesUI) }) { Text("字号、字体与显示细节 ›") }
        }
        Text("图片使用应用现有的本地文件管理；导出包含文件的备份可能带上这些素材。此面板不会主动把图片发给模型。",
            style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        TextButton(onClick = { onUpdateDisplay { it.withAppearanceForStyle(deepSeek) {
            if (deepSeek) deepSeekDefaultAppearance() else OrbisAppearance()
        } } }, modifier = Modifier.fillMaxWidth()) {
            Text("恢复气泡、顶部导航、输入框与背景默认")
        }
        Text("重置气泡、顶部导航与输入框底色、背景与星光；头像、字体和主题保持原样。", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        if (showDoneButton) Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(if (embedded) "完成预览" else "完成，回到会话") }
    }
    }
    }
    }
}

@Composable
private fun AppearanceCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = OrbisTheme.colors
    if (LocalInlineAppearance.current) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                color = colors.mutedInk, modifier = Modifier.semantics { heading() })
            content()
            HorizontalDivider(Modifier.padding(top = 5.dp), color = colors.border)
        }
        return
    }
    Surface(color = colors.panel, contentColor = colors.ink, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun AppearanceToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun AvatarChoices(name: String, value: Avatar, presets: List<String>, onUpdate: (Avatar) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            UIAvatar(name, value, Modifier.size(40.dp).semantics { contentDescription = "${name}头像，点击上传并剪裁" }, onUpdate = onUpdate)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            presets.distinct().forEach { text ->
                FilterChip(selected = value == Avatar.Emoji(text), onClick = { onUpdate(Avatar.Emoji(text)) }, label = { Text(text) })
            }
        }
    }
    CroppedBackgroundButton(label = "上传并裁剪", square = true) { onUpdate(Avatar.Image(it)) }
    Text("图片保存在本机；点头像还可以选择更多样式。", style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
}

@Composable
private fun CroppedBackgroundButton(label: String = "上传背景并裁剪", square: Boolean = false, onUpdate: (String) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val filesManager: FilesManager = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val update by rememberUpdatedState(onUpdate)
    var sourceFile by remember { mutableStateOf<File?>(null) }
    var importing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { sourceFile?.delete() }
    }
    val (_, launchCrop) = useCropLauncher(
        onCroppedImageReady = { uri ->
            // The shared cropper deletes its result immediately after this callback.
            // Its output is already bounded to 4096 x 4096; preserve that synchronous ownership contract.
            runCatching {
                require(uri.scheme == "file" && File(requireNotNull(uri.path)).length() in 1..ORBIS_IMAGE_MAX_BYTES)
                filesManager.createChatFilesByContents(listOf(uri)).firstOrNull() ?: error("image_save_failed")
            }.onSuccess { update(it.toString()) }.onFailure {
                toaster.show("图片未保存（上限 30 MB），原外观未改动。", type = ToastType.Error)
            }
        },
        onCleanup = { sourceFile?.delete(); sourceFile = null },
        aspectRatio = if (square) 1f to 1f else configuration.screenWidthDp.toFloat().coerceAtLeast(1f) to configuration.screenHeightDp.toFloat().coerceAtLeast(1f),
        freeStyleCropEnabled = !square,
    )
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importing = true
            scope.launch {
                var temp: File? = null
                var handedToCropper = false
                try {
                    withContext(Dispatchers.IO) {
                        val target = File.createTempFile("orbis_background_", ".image", context.appTempFolder)
                        temp = target
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> copyOrbisImage(input, output) }
                        } ?: error("image_unavailable")
                    }
                    sourceFile?.delete()
                    sourceFile = temp
                    launchCrop(requireNotNull(temp).toUri())
                    handedToCropper = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    toaster.show("图片无法打开或超过 30 MB，原外观未改动。", type = ToastType.Error)
                } finally {
                    if (!handedToCropper) {
                        temp?.delete()
                        if (sourceFile == temp) sourceFile = null
                    }
                    importing = false
                }
            }
        }
    }
    Button(onClick = { picker.launch("image/*") }, enabled = !importing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = OrbisTheme.colors.sand, contentColor = OrbisTheme.colors.onSand)) {
        Text(if (importing) "正在读取图片…" else label, fontSize = 11.sp)
    }
}

@Composable
private fun BackgroundSwatch(style: OrbisBackgroundStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    Surface(onClick = onClick, modifier = Modifier.size(width = 64.dp, height = 48.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) colors.accent else colors.border)) {
        Box(contentAlignment = Alignment.BottomCenter) {
            OrbisChatBackdrop(OrbisAppearance(backgroundEnabled = true, backgroundStyle = style,
                floatingStars = style == OrbisBackgroundStyle.STARS), Modifier.matchParentSize(), isVisible = false)
            Text(style.label(), fontSize = 9.sp, modifier = Modifier.padding(5.dp),
                color = if (style == OrbisBackgroundStyle.STARS) Color.White else colors.ink)
        }
    }
}

@Composable
private fun PreviewBubble(text: String, appearance: OrbisAppearance, display: DisplaySetting, user: Boolean) {
    OrbisMessageBubble(user = user, appearance = appearance) {
        Text(text, fontFamily = rememberChatFontFamily(display), style = MaterialTheme.typography.bodySmall)
    }
}

private fun OrbisBubbleStyle.label(): String = when (this) {
    OrbisBubbleStyle.SILK -> "云绸"; OrbisBubbleStyle.GLASS -> "星雾"
    OrbisBubbleStyle.STARS -> "星芒"; OrbisBubbleStyle.BOOK -> "书页"
}
private fun OrbisBackgroundStyle.label(): String = when (this) {
    OrbisBackgroundStyle.PAPER -> "纸页"; OrbisBackgroundStyle.BLUSH -> "暖霞"; OrbisBackgroundStyle.STARS -> "星夜"
}

/** Non-interactive chat underlay; caller keeps any existing assistant image above/below as appropriate. */
@Composable
fun OrbisChatBackdrop(
    appearance: OrbisAppearance,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    drawBackground: Boolean = true,
    onImageSettled: (() -> Unit)? = null,
) {
    if (!BuildConfig.ORBIS_ENABLED) return
    val dark = LocalDarkMode.current
    val backgroundStyle = if (appearance.backgroundEnabled) appearance.backgroundStyle else OrbisBackgroundStyle.PAPER
    val deepSeek = LocalOrbisDeepSeekStyle.current
    val gradient = if (deepSeek && backgroundStyle == OrbisBackgroundStyle.PAPER) {
        listOf(OrbisTheme.colors.page, OrbisTheme.colors.page)
    } else when (backgroundStyle) {
        OrbisBackgroundStyle.PAPER -> if (dark) listOf(Color(0xFF252438), Color(0xFF191C30)) else listOf(Color(0xFFFBF8F0), Color(0xFFF5F2F6))
        OrbisBackgroundStyle.BLUSH -> if (dark) listOf(Color(0xFF392936), Color(0xFF232033)) else listOf(Color(0xFFF4DDD9), Color(0xFFFFF4DE))
        OrbisBackgroundStyle.STARS -> listOf(Color(0xFF4C4166), Color(0xFF15192F))
    }
    val canAnimate = rememberStarAnimationAllowed(appearance, isVisible)
    val season = LocalOrbisSeason.current
    Box(modifier) {
        if (drawBackground) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(gradient)))
            if (usesSeasonWallpaper(season, appearance)) OrbisSeasonWallpaper(season, dark, Modifier.fillMaxSize())
            if (appearance.backgroundEnabled && !appearance.backgroundImage.isNullOrBlank()) {
                // Bounded decode; the preset remains visible if a missing/corrupt file cannot load.
                val context = LocalContext.current
                val request = remember(context, appearance.backgroundImage) {
                    ImageRequest.Builder(context).data(appearance.backgroundImage).size(1440, 2560).build()
                }
                AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    onSuccess = { onImageSettled?.invoke() }, onError = { onImageSettled?.invoke() })
            }
        }
        if (appearance.floatingStars) {
            val phase = if (canAnimate) animatedStarPhase() else 0f
            val starColor = if (deepSeek) OrbisTheme.colors.accent else if (dark || backgroundStyle == OrbisBackgroundStyle.STARS) Color(0xFFFFE5AD) else Color(0xFFA78A60)
            val seasonalFloatColor = if (season == OrbisSeason.NONE) starColor else orbisSeasonColors(season, dark).accent
            Canvas(Modifier.fillMaxSize()) {
                if (season != OrbisSeason.NONE) {
                    drawSeasonFloat(season, phase, seasonalFloatColor)
                    return@Canvas
                }
                repeat(23) { index ->
                    val x = (((index * 37 + 11) % 101) / 101f) * size.width
                    val initialY = ((index * 53 + 17) % 103) / 103f
                    val y = ((initialY - phase + 1f) % 1f) * size.height
                    val radius = (if (index % 5 == 0) 1.6f else .9f).dp.toPx()
                    drawCircle(starColor.copy(alpha = if (index % 5 == 0) .48f else .28f), radius, Offset(x, y))
                    if (index % 7 == 0) {
                        drawLine(starColor.copy(alpha = .35f), Offset(x - radius * 2.4f, y), Offset(x + radius * 2.4f, y), .6f.dp.toPx())
                        drawLine(starColor.copy(alpha = .35f), Offset(x, y - radius * 2.4f), Offset(x, y + radius * 2.4f), .6f.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
private fun animatedStarPhase(): Float {
    val transition = rememberInfiniteTransition(label = "Orbis upward stars")
    val phase by transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(70000, easing = LinearEasing), RepeatMode.Restart), label = "upward position")
    return phase
}

@Composable
private fun rememberStarAnimationAllowed(appearance: OrbisAppearance, isVisible: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var powerSave by remember(powerManager) { mutableStateOf(powerManager?.isPowerSaveMode ?: true) }
    fun systemAnimationsEnabled(): Boolean = runCatching {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(false)
    var animations by remember(context) { mutableStateOf(systemAnimationsEnabled()) }
    var monitoringReady by remember(context, lifecycle) { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            powerSave = powerManager?.isPowerSaveMode ?: true
            animations = systemAnimationsEnabled()
        }
        lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = powerManager?.isPowerSaveMode ?: true }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animations = systemAnimationsEnabled() }
        }
        val observed = runCatching {
            context.contentResolver.registerContentObserver(AndroidSettings.Global.getUriFor(AndroidSettings.Global.ANIMATOR_DURATION_SCALE), false, contentObserver)
        }.isSuccess
        monitoringReady = registered && observed
        onDispose {
            lifecycle.removeObserver(observer)
            if (registered) context.unregisterReceiver(receiver)
            if (observed) context.contentResolver.unregisterContentObserver(contentObserver)
        }
    }
    return shouldAnimateOrbisStars(appearance, isVisible, resumed, powerSave, animations && monitoringReady)
}
