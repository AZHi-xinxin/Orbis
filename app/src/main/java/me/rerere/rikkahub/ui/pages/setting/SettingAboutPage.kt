package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.utils.openUrl

/** App identity and upstream attribution are separate; upstream links are not this app's homepage. */
@Composable
fun SettingAboutPage() {
    val context = LocalContext.current
    val navigator = LocalNavController.current
    OrbisSettingsScaffold(
        topBar = {
            OrbisSettingsTopBar(
                title = { Text("关于 Orbis Dev") },
                subtitle = { Text("版本信息与开源来源") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        val colors = OrbisTheme.colors
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item("identity") {
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(92.dp), shape = RoundedCornerShape(28.dp),
                        color = colors.indigo, contentColor = colors.onIndigo,
                        border = BorderStroke(1.dp, colors.border)) {
                        Box(contentAlignment = Alignment.Center) { Text("✦", fontSize = 55.sp) }
                    }
                    Text("Orbis Dev", color = colors.ink, fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text("聊天与生活，留在同一个家里。", color = colors.mutedInk,
                        fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            item("version") {
                AboutCard {
                    Column(Modifier.fillMaxWidth().combinedClickable(
                        onClick = {}, onLongClick = { if (BuildConfig.DEBUG) navigator.navigate(Screen.Debug) })
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前版本", color = colors.mutedInk, fontSize = 12.sp)
                        Text("${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",
                            color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("版本号以当前安装包为准；不会把服务端版本当作 App 版本。",
                            color = colors.mutedInk, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            item("device") {
                AboutCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("运行环境", color = colors.mutedInk, fontSize = 12.sp)
                        Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                            color = colors.ink, fontSize = 14.sp)
                        Text("Android ${android.os.Build.VERSION.RELEASE} · SDK ${android.os.Build.VERSION.SDK_INT}",
                            color = colors.mutedInk, fontSize = 12.sp)
                    }
                }
            }
            item("attribution") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开源来源与许可证", color = colors.ink, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text("Orbis Dev 在 RikkaHub 开源基础上开发。下面保留上游出处和许可证，它们不是 Orbis Dev 的官方主页。",
                        color = colors.mutedInk, fontSize = 12.sp, lineHeight = 20.sp)
                    AboutCard {
                        Column {
                            AboutLink("RikkaHub · 上游源码", "github.com/rikkahub/rikkahub", true) {
                                context.openUrl("https://github.com/rikkahub/rikkahub")
                            }
                            AboutLink("上游许可证 · AGPL-3.0", "保留版权、许可证与对应源码义务", false) {
                                context.openUrl("https://github.com/rikkahub/rikkahub/blob/master/LICENSE")
                            }
                        }
                    }
                    Text("第三方组件的原始版权与许可证继续保留。品牌调整不代表将上游代码归为原创。",
                        color = colors.mutedInk, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = OrbisTheme.colors
    Surface(Modifier.fillMaxWidth(), color = colors.panel, shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, colors.border.copy(alpha = .65f))) {
        Column(content = content)
    }
}

@Composable
private fun AboutLink(title: String, description: String, github: Boolean, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (github) HugeIcons.Github else HugeIcons.File02, contentDescription = null, tint = colors.indigo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.mutedInk, fontSize = 11.sp, lineHeight = 17.sp)
        }
        Text("↗", color = colors.mutedInk)
    }
}
