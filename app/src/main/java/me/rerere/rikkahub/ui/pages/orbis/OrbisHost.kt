package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.webkit.WebViewAssetLoader
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalStatusBarAppearanceOverride
import me.rerere.rikkahub.data.model.orbisSheetLightStatusBars
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.orbis.integration.OrbisIntegration
import me.rerere.rikkahub.data.orbis.integration.OrbisIntegrationConnections
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import kotlin.math.cos
import kotlin.math.sin

val LocalOpenOrbisHome = staticCompositionLocalOf<() -> Unit> { {} }
val LocalReturnToOrbisChat = staticCompositionLocalOf<() -> Unit> { {} }

/** Stays composed when hidden, so navigation does not recreate the page or a chat. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OrbisOfflineHomeOverlay(visible: Boolean, homeRevision: Int, onOpenChat: () -> Unit) {
    var renderFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val currentOpenChat by rememberUpdatedState(onOpenChat)
    var lastHomeRevision by remember { mutableIntStateOf(homeRevision) }
    var currentView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = visible) {
        val fragment = currentView?.url?.let { Uri.parse(it).fragment }
        if (!fragment.isNullOrEmpty() && fragment != "calendar") {
            currentView?.evaluateJavascript("location.hash = '#calendar';", null)
        } else onOpenChat()
    }
    // Hidden Android View must neither draw nor capture touches/accessibility focus.
    Box(Modifier.fillMaxSize().zIndex(if (visible) 1f else -1f)) {
        if (!renderFailed) key(reload) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                    WebView(context).apply {
                        configureOrbisHomeLayout()
                        currentView = this
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.setGeolocationEnabled(false)
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                // Fail closed, including same-origin /api calls and missing assets.
                                if (request.method == "GET" && OrbisLocalPolicy.isHome(request.url.toString())) {
                                    loader.shouldInterceptRequest(request.url)?.let { return it }
                                }
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Not connected",
                                    mapOf("Cache-Control" to "no-store"),
                                    ByteArrayInputStream("orbis_service_not_connected".toByteArray()))
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && OrbisLocalPolicy.isHome(request.url.toString()) &&
                                    OrbisLocalPolicy.isHome(view.url)) return false
                                if (OrbisLocalPolicy.mayOpenChat(request.url.toString(), view.url,
                                        request.isForMainFrame, request.hasGesture())) currentOpenChat()
                                // No external URL, intent, file chooser or privileged JS interface.
                                return true
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                renderFailed = true // Removes this WebView; native chat is retained.
                                return true
                            }
                        }
                        loadUrl(OrbisLocalPolicy.HOME)
                    }
                },
                onRelease = { view ->
                    if (currentView === view) currentView = null
                    view.stopLoading(); view.destroy()
                },
                update = { view ->
                    view.visibility = if (visible) View.VISIBLE else View.GONE
                    view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    if (visible) view.onResume() else view.onPause()
                    if (homeRevision != lastHomeRevision) {
                        lastHomeRevision = homeRevision
                        view.evaluateJavascript("location.hash = '#calendar';", null)
                    }
                }
            )
        }
        if (renderFailed && visible) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Orbis 页面已中断，聊天记录未被清除")
                TextButton(onClick = { reload++; renderFailed = false }) { Text("重新打开主页") }
                TextButton(onClick = onOpenChat) { Text("返回聊天") }
            }
        }
    }
}

@Composable
fun OrbisChatDock(currentLabel: String = "当前 聊天") {
    val openHome = LocalOpenOrbisHome.current
    val returnToChat = LocalReturnToOrbisChat.current
    val navigator = LocalNavController.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val consultationEnabled = me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled
    val consultationAvailable = if (consultationEnabled) {
        val connection by koinInject<OrbisIntegrationConnections>()[OrbisIntegration.CONSULTATION].state.collectAsStateWithLifecycle()
        connection.available
    } else false
    OrbisBottomDock(
        currentLabel = currentLabel,
        onHome = returnToChat,
        homeDescription = "返回当前聊天",
        onOpenNavigation = { menuOpen = true },
    )
    if (menuOpen) OrbisVisualTheme {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        // Material places the sheet with its anchored-draggable offset inside a motion frame.
        // The external modifier's positionInWindow can remain 0 despite a short, lowered sheet.
        // Read the same observable offset Material uses for placement; before measurement it
        // throws, so keep the Activity appearance until anchors have been initialized.
        val sheetOffsetPx = runCatching { sheetState.requireOffset() }.getOrNull()
        val sheetLightBars = OrbisTheme.colors.panel.luminance() > .5f
        val activityLightBars = LocalStatusBarAppearanceOverride.current?.value ?: !LocalDarkMode.current
        val statusBarHeightPx = WindowInsets.statusBars.getTop(LocalDensity.current)
        ModalBottomSheet(
        onDismissRequest = { menuOpen = false },
        containerColor = OrbisTheme.colors.panel,
        // Material owns this separate Dialog window. Do not change Activity flags from it:
        // a short sheet leaves the dark chat visible at the top, while a tall sheet covers it.
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = orbisSheetLightStatusBars(activityLightBars, sheetLightBars,
                sheetOffsetPx, statusBarHeightPx),
            isAppearanceLightNavigationBars = sheetLightBars,
        ),
        sheetState = sheetState,
    ) {
        OrbisScrollablePanel(windowInsets = WindowInsets(0, 0, 0, 0)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("北斗导航", style = MaterialTheme.typography.titleLarge)
                    Text("在同一个家里，回到你想去的地方", style = MaterialTheme.typography.bodySmall,
                        color = OrbisTheme.colors.mutedInk)
                }
                IconButton(onClick = { menuOpen = false }) {
                    Icon(HugeIcons.Cancel01, "关闭北斗导航")
                }
            }
            fun go(screen: Screen) { menuOpen = false; navigator.navigate(screen) { launchSingleTop = true } }
            OrbisNavigationGrid(listOf(
                OrbisNavigationItem("chat", "聊天", "返回当前会话", HugeIcons.Message01,
                    { menuOpen = false; returnToChat() }),
                OrbisNavigationItem("orbis", "Orbis", "本地后花园 / 自建原站", HugeIcons.Home01,
                    { menuOpen = false; openHome() }),
                OrbisNavigationItem("hub", "群聊", "AI 群聊 · 可选 TechHub", HugeIcons.UserGroup,
                    { go(Screen.OrbisGroups) }),
                OrbisNavigationItem("memory", "记忆星图", "ST 只读连接 · 时间、类型与关联", HugeIcons.Sparkles,
                    { go(Screen.OrbisMemoryAtlas) }),
                OrbisNavigationItem("tools", "工具娱乐", "工作区、文件与本地工具", HugeIcons.Command,
                    { go(Screen.OrbisTools) }),
                OrbisNavigationItem("permissions", "后台与权限", "哨兵与手机设备能力", HugeIcons.SecurityCheck,
                    { go(Screen.OrbisPhone) }),
                OrbisNavigationItem("settings", "系统设置", "模型、MCP、声音与数据", HugeIcons.Settings01,
                    { go(Screen.Setting) }),
            ) + if (showConsultationInNavigation(consultationEnabled, consultationAvailable)) listOf(
                OrbisNavigationItem("consultation", "咨询室", "偷听角 · 独立人类授权", HugeIcons.Message01,
                    { go(Screen.OrbisConsultation) })
            ) else emptyList())
            Text("开发中：灰色入口尚未连接真实服务。聊天使用现有会话，不创建副本。",
                style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
        }
    } }
}
