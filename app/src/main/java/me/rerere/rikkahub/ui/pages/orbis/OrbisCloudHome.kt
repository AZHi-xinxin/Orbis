package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import java.net.URI
import me.rerere.rikkahub.data.orbis.OrbisCloudSettingsStore
import org.koin.compose.koinInject

/** Cloud configuration is independent of model settings, chat databases and API credentials. */
@Composable
fun OrbisHomeOverlay(visible: Boolean, homeRevision: Int, onOpenChat: () -> Unit,
    onStatusBarAppearance: (Boolean?) -> Unit = {}) {
    // Native sibling, never a bridge injected into a self-hosted website. The website remains
    // mounted while the drawer is open; neither its text nor the soup solution enters chat.
    // A WebView has its own hardware renderer. Never record it into a Compose/Haze source
    // layer: showing a source-backed drawer can trigger a redraw loop that survives closing it.
    // Keep the webpage mounted as a normal sibling; the drawer owns its chat background.
    OrbisGardenLayers(visible,
        content = { OrbisHomeContent(visible, homeRevision, onOpenChat, onStatusBarAppearance) },
        overlay = { OrbisGardenQuickChat(visible) },
    )
}

/** The stable WebView host and native chat must remain siblings, not captured drawing layers. */
@Composable
internal fun OrbisGardenLayers(visible: Boolean, content: @Composable () -> Unit, overlay: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().zIndex(if (visible) 1f else -1f)) {
        Box(Modifier.fillMaxSize()) { content() }
        overlay()
    }
}

@Composable
private fun OrbisHomeContent(visible: Boolean, homeRevision: Int, onOpenChat: () -> Unit,
    onStatusBarAppearance: (Boolean?) -> Unit) {
    val store = koinInject<OrbisCloudSettingsStore>()
    val state by store.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var editingConnection by rememberSaveable { mutableStateOf(false) }
    val policy = remember(state.config.homeUrl) { OrbisCloudWebPolicy.from(state.config.homeUrl) }
    val online = state.loaded && state.config.enabled && policy != null
    val mountCloud = shouldMountOrbisCloudHome(state.loaded, state.config.enabled, policy != null,
        visible, lifecycle.isAtLeast(Lifecycle.State.RESUMED), editingConnection)
    val currentStatusBarAppearance by rememberUpdatedState(onStatusBarAppearance)
    LaunchedEffect(mountCloud, visible, online) { currentStatusBarAppearance(if (mountCloud || (visible && !online)) true else null) }
    DisposableEffect(Unit) { onDispose { currentStatusBarAppearance(null) } }
    BackHandler(enabled = visible && !online && !editingConnection) { onOpenChat() }

    if (!online) {
        Column(Modifier.fillMaxSize().zIndex(if (visible) 1f else -1f)
            .then(if (visible) Modifier.background(orbisCloudHomeGradient) else Modifier)
            .safeDrawingPadding()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // New bundled renderer reuses only original CSS; the old cloud asset is never opened.
                // Record storage and file/permission dialogs remain native and app-private.
                if (visible && state.loaded && state.canEdit) OrbisGardenWeb(state.config, onOpenChat,
                    onSettings = { editingConnection = true })
                else if (visible) Text(state.error ?: "正在读取本机设置…", Modifier.padding(24.dp))
            }
        }
    } else if (mountCloud) {
        // A cloud WebView exists only while the approved homepage is actually foregrounded.
        // Disposing it stops future page timers; an already-sent server request may still finish.
        OrbisCloudHomePane(policy!!, homeRevision, onOpenChat)
    }
    if (visible && editingConnection) OrbisCloudConnectionSheet { editingConnection = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OrbisCloudHomePane(
    policy: OrbisCloudWebPolicy, homeRevision: Int,
    onOpenChat: () -> Unit,
) {
    val currentOpenChat by rememberUpdatedState(onOpenChat)
    var reload by remember { mutableIntStateOf(0) }
    var currentView by remember { mutableStateOf<WebView?>(null) }
    var error by remember(policy.homeUrl, reload) { mutableStateOf<String?>(null) }
    var loading by remember(policy.homeUrl, reload) { mutableStateOf(true) }
    var notice by remember(policy.homeUrl, reload) { mutableStateOf<String?>(null) }
    var lastHomeRevision by remember { mutableIntStateOf(homeRevision) }
    BackHandler {
        navigateBackFromOrbisCloudHome(currentView, policy, onOpenChat)
    }
    Column(Modifier.fillMaxSize().zIndex(1f).background(orbisCloudHomeGradient).safeDrawingPadding()
        .testTag("orbis-cloud-home")) {
        notice?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            fontSize = 11.sp, color = OrbisTheme.colors.mutedInk) }
        if (loading && error == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (error != null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = OrbisTheme.colors.ink)
                TextButton(onClick = { reload++ }) { Text("重新连接") }
                TextButton(onClick = onOpenChat) { Text("返回当前聊天") }
            }
        } else key(policy.homeUrl, reload) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                WebView(context).apply {
                    configureOrbisHomeLayout()
                    currentView = this
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.setGeolocationEnabled(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                            callback?.invoke(origin, false, false)
                        }
                    }
                    setDownloadListener { _, _, _, _, _ -> notice = "如需下载文件，可在浏览器打开原站。" }
                    webViewClient = OrbisCloudWebViewClient(policy,
                        onLoading = { loading = it }, onFailure = { error = it },
                        onOpenChat = { currentOpenChat() },
                        onBlockedNavigation = { notice = "已留在当前主页；外部链接请在浏览器中打开。" }
                    ).also { it.installChatNavigation(this) }
                    loadUrl(policy.homeUrl)
                }
            }, update = { view ->
                if (homeRevision != lastHomeRevision) {
                    lastHomeRevision = homeRevision
                    if (policy.allowsNavigation(view.url)) view.evaluateJavascript("location.hash = '#calendar';", null)
                }
            }, onRelease = { view ->
                if (currentView === view) currentView = null
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            })
        }
    }
}

internal fun navigateBackFromOrbisCloudHome(
    view: WebView?, policy: OrbisCloudWebPolicy, onOpenChat: () -> Unit,
) {
    val fragment = runCatching { URI(view?.url.orEmpty()).fragment }.getOrNull()
    if (view != null && policy.allowsNavigation(view.url) && !fragment.isNullOrEmpty() && fragment != "calendar") {
        view.evaluateJavascript("location.hash = '#calendar';", null)
    } else onOpenChat()
}
