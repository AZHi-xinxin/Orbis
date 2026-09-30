package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import org.koin.compose.koinInject

@Composable
internal fun OrbisChatPreferencesPage(vm: SettingVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val store = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var saving by remember { mutableStateOf(false) }
    // Match RouteActivity's Orbis default: reopen the current conversation unless opted in.
    var startNew by rememberSharedPreferenceBoolean("create_new_conversation_on_start", false)
    fun updateSettings(transform: (Settings) -> Settings) {
        if (settings.init || saving) return
        saving = true
        scope.launch {
            try {
                persistOrbisPreferenceChange {
                    store.update { latest ->
                        if (latest.init) latest else transform(latest)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toaster.show("保存未确认，请重新进入核对。", type = ToastType.Error)
            } finally {
                saving = false
            }
        }
    }
    fun update(transform: (DisplaySetting) -> DisplaySetting) = updateSettings { latest ->
        latest.copy(displaySetting = transform(latest.displaySetting))
    }
    OrbisSettingsScaffold(topBar = {
        OrbisSettingsTopBar(title = { Text("聊天偏好") }, navigationIcon = { BackButton() })
    }) { padding ->
        OrbisChatPreferencesGrid(
            display = settings.displaySetting, startNew = startNew,
            enabled = !settings.init && !saving,
            onStartNew = { startNew = it },
            onToggle = { preference, checked -> update { it.withOrbisPreference(preference, checked) } },
            onLongTextThreshold = { value -> update { it.copy(pasteLongTextThreshold = value.coerceIn(100, 10000)) } },
            onVolumeRatio = { value -> update { it.copy(volumeKeyScrollRatio = value.coerceIn(.25f, 1f)) } },
            suggestionsEnabled = settings.enableSuggestion,
            onSuggestionsChange = { checked -> updateSettings { it.copy(enableSuggestion = checked) } },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/** Bounded scrolling grid, not a single giant lazy item and not nested in another scroller. */
@Composable
internal fun OrbisChatPreferencesGrid(
    display: DisplaySetting,
    startNew: Boolean,
    enabled: Boolean,
    onStartNew: (Boolean) -> Unit,
    onToggle: (OrbisChatPreference, Boolean) -> Unit,
    onLongTextThreshold: (Int) -> Unit,
    onVolumeRatio: (Float) -> Unit,
    suggestionsEnabled: Boolean,
    onSuggestionsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OrbisTheme.colors
    BoxWithConstraints(modifier) {
        val columns = orbisChatPreferenceColumns(maxWidth.value, LocalDensity.current.fontScale)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("orbis-chat-preferences-grid"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("让聊天顺手一点", fontSize = 23.sp, lineHeight = 30.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.semantics { heading() })
                    Text("灰色四芒星是关闭，点亮五角星是开启。偏好自动保存，影响本 App 的聊天界面。",
                        style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                }
            }
            item("start-new") {
                OrbisPreferenceTile("启动时新建对话", "打开应用时准备一个新窗口", startNew, enabled,
                    onStartNew, Modifier.testTag("chat-preference-start-new"))
            }
            item("chat-suggestions") {
                OrbisPreferenceTile("AI 建议回复", "聊天底部的预制回复。关闭后立即隐藏，并停止后续生成；X 只收起当前一组。",
                    suggestionsEnabled, enabled, onSuggestionsChange,
                    Modifier.testTag("chat-preference-suggestions"))
            }
            items(OrbisChatPreference.entries.filter {
                it != OrbisChatPreference.JUMPER_LEFT || display.showMessageJumper
            }, key = { it.name }) { preference ->
                OrbisPreferenceTile(preference.title, preference.description,
                    display.orbisPreferenceValue(preference), enabled,
                    { onToggle(preference, it) }, Modifier.testTag("chat-preference-${preference.name}"))
            }
            if (display.pasteLongTextAsFile) item("text-limit", span = { GridItemSpan(maxLineSpan) }) {
                OrbisPreferenceSlider("长文转附件门槛", "超过多少字后转成附件", display.pasteLongTextThreshold.toFloat(),
                    "${display.pasteLongTextThreshold} 字", 100f..10000f, enabled,
                    onSave = { onLongTextThreshold(it.toInt()) })
            }
            if (display.enableVolumeKeyScroll) item("volume-ratio", span = { GridItemSpan(maxLineSpan) }) {
                OrbisPreferenceSlider("音量键滚动距离", "每次按键滚动的屏幕比例", display.volumeKeyScrollRatio,
                    "${(display.volumeKeyScrollRatio * 100).toInt()}%", .25f..1f, enabled,
                    steps = 2, onSave = onVolumeRatio)
            }
            item("end", span = { GridItemSpan(maxLineSpan) }) {
                Text("已到最后 · 生成参数在聊天的「对话设置」；声音在系统设置的「语音与朗读」。",
                    modifier = Modifier.testTag("chat-preferences-end").padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
            }
        }
    }
}

@Composable
private fun OrbisPreferenceTile(
    title: String, description: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = OrbisTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "已开启" else "已关闭" },
        shape = RoundedCornerShape(22.dp), color = if (checked) colors.sand.copy(alpha = .32f) else colors.panel,
        border = BorderStroke(1.dp, if (checked) colors.onSand.copy(alpha = .4f) else colors.border),
    ) {
        Column(Modifier.heightIn(min = 190.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, color = colors.ink)
            Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = colors.mutedInk,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            StarSwitch(checked = checked, onCheckedChange = null, enabled = enabled, iconSize = 34.dp,
                modifier = Modifier.testTag("chat-preference-star-$title"))
        }
    }
}

@Composable
private fun OrbisPreferenceSlider(
    title: String, description: String, saved: Float, savedLabel: String,
    range: ClosedFloatingPointRange<Float>, enabled: Boolean, steps: Int = 0, onSave: (Float) -> Unit,
) {
    val colors = OrbisTheme.colors
    var draft by remember(saved) { mutableFloatStateOf(saved.coerceIn(range)) }
    Surface(shape = RoundedCornerShape(20.dp), color = colors.panel, border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = colors.ink, fontWeight = FontWeight.SemiBold)
            Text("$description · 已保存 $savedLabel", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
            Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onSave(draft) },
                valueRange = range, steps = steps, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
    }
}
