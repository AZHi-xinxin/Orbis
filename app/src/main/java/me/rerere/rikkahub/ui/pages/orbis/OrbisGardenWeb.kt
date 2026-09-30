package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.orbis.*

private data class GardenWebEdit(val requestId: String, val original: OrbisGardenEntry?,
    val kind: OrbisGardenKind, val date: LocalDate)
private data class GardenWebExport(val requestId: String, val bytes: ByteArray)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OrbisGardenWeb(config: OrbisCloudHomeConfig, onOpenChat: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentConfig by rememberUpdatedState(config)
    val currentOpenChat by rememberUpdatedState(onOpenChat)
    val currentSettings by rememberUpdatedState(onSettings)
    val garden = remember { OrbisGardenStore.open(context) }
    val extras = remember { OrbisGardenExtrasStore.open(context) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var failure by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var soup by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GardenWebEdit?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var importingLibrary by remember { mutableStateOf<String?>(null) }
    var exportingLibrary by remember { mutableStateOf<GardenWebExport?>(null) }
    var confirmLibraryExport by remember { mutableStateOf(false) }
    val active = remember { mutableSetOf<String>() }
    fun reply(id: String, result: JsonElement? = null, error: String? = null) {
        val data = buildJsonObject { put("id", id); put("ok", error == null)
            if (error == null) put("result", result ?: JsonObject(emptyMap())) else put("error", error) }
        active.remove(id)
        // JSON is quoted as a JS string rather than interpolating arbitrary text into code.
        val quoted = Json.encodeToString(data.toString())
        view?.takeIf { GardenAssetPolicy.mainFrame(it.url) }?.evaluateJavascript(
            "window.gardenReceive(JSON.parse($quoted));", null)
    }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importing; importing = null
        if (id != null) {
            if (uri == null || uri.scheme != "content") reply(id, error = "已取消导入。")
            else scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0).take(180) else "未命名书籍"
                        } ?: "未命名书籍"
                        require(name.endsWith(".txt", true) || name.endsWith(".md", true)) { "book_extension" }
                        val text = context.contentResolver.openInputStream(uri)?.use(GardenBookImport::read)
                            ?: error("missing_input")
                        buildJsonObject { put("title", name.substringBeforeLast('.')); put("text", text) }
                    }
                    reply(id, data)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) { reply(id, error = GardenBookImport.errorMessage(failure)) }
            }
        }
    }
    val libraryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importingLibrary; importingLibrary = null
        if (id != null) {
            if (uri == null) reply(id, buildJsonObject { put("cancelled", true) })
            else scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        require(uri.scheme == "content")
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break
                                require(output.size() + count <= GardenLibraryBackup.MAX_BYTES); output.write(buffer, 0, count) }
                            output.toByteArray()
                        } ?: error("missing_input")
                        GardenLibraryBackup.decode(bytes)
                    }
                    reply(id, buildJsonObject { put("data", data) })
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { reply(id, error = "无法读取藏书阁专用备份，原书库未改动。只支持有格式标记的 UTF-8 JSON，最大约 64 MiB。") }
            }
        }
    }
    val libraryExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = exportingLibrary; exportingLibrary = null
        if (pending != null) {
            if (uri == null) reply(pending.requestId, buildJsonObject { put("cancelled", true) })
            else scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        require(uri.scheme == "content")
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(pending.bytes); out.flush()
                        } ?: error("missing_output")
                    }
                    reply(pending.requestId)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { reply(pending.requestId, error = "未确认备份写入成功；原书库保留，所选位置可能有未完成文件，请检查。") }
            }
        }
    }
    BackHandler { when { soup -> soup = false; management -> management = false; editing != null -> Unit
        else -> view?.evaluateJavascript("window.gardenBack();", null) ?: currentOpenChat() } }

    Box(Modifier.fillMaxSize().testTag("orbis-local-garden-web")) {
        if (failure || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Column { Text("本机 WebView 暂不支持安全的本地界面，数据仍保留。")
                TextButton(onClick = { management = true }) { Text("打开本地记录管理") }
                TextButton(onClick = currentOpenChat) { Text("返回聊天") } }
        } else AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/orbis-garden/") { path ->
                if (!GardenAssetPolicy.asset("${GardenAssetPolicy.ORIGIN}/assets/orbis-garden/$path")) null
                else runCatching { WebResourceResponse(when { path.endsWith(".css") -> "text/css"
                    path.endsWith(".js") -> "text/javascript"; else -> "text/html" }, "UTF-8", ctx.assets.open("orbis-garden/$path")) }.getOrNull()
            }.build()
            WebView(ctx).apply {
                view = this; setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true; settings.domStorageEnabled = false
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = true; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false); settings.mediaPlaybackRequiresUserGesture = true
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, false, false)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.method == "GET" && GardenAssetPolicy.asset(request.url.toString()))
                            loader.shouldInterceptRequest(request.url)?.let { return it }
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { failure = true; return true }
                }
                WebViewCompat.addWebMessageListener(this, "OrbisGardenBridge", setOf(GardenAssetPolicy.ORIGIN)) { source, message, origin, isMainFrame, _ ->
                    if (!isMainFrame || origin.toString() != GardenAssetPolicy.ORIGIN || !GardenAssetPolicy.mainFrame(source.url)) return@addWebMessageListener
                    val raw = message.data ?: return@addWebMessageListener
                    if (raw.toByteArray().size > GardenBookImport.MAX_LIBRARY_BYTES + 8192) return@addWebMessageListener
                    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@addWebMessageListener
                    val id = root["id"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@addWebMessageListener
                    if (id in active || active.size >= 8) return@addWebMessageListener
                    active.add(id)
                    scope.launch {
                        try {
                            require(root.keys == setOf("id", "action", "payload"))
                            val args = root.getValue("payload").jsonObject
                            fun arg(key: String) = args.getValue(key).jsonPrimitive.content
                            when (root.getValue("action").jsonPrimitive.content) {
                                "config" -> reply(id, buildJsonObject { put("gardenName", currentConfig.gardenName)
                                    put("humanName", currentConfig.humanName); put("companionName", currentConfig.companionName) })
                                "month" -> {
                                    val counts = garden.monthDays(null, YearMonth.parse(arg("month")), ZoneId.systemDefault())
                                    reply(id, buildJsonObject { put("counts", buildJsonObject { counts.forEach { (d, n) -> put(d.toString(), n) } }) })
                                }
                                "list" -> {
                                    val values = garden.listOnDate(OrbisGardenKind.valueOf(arg("kind")), LocalDate.parse(arg("date")),
                                        ZoneId.systemDefault(), 31, args["offset"]?.jsonPrimitive?.int ?: 0)
                                    reply(id, buildJsonObject { put("entries", Json.encodeToJsonElement(values.take(30))); put("hasMore", values.size > 30) })
                                }
                                "edit" -> {
                                    check(editing == null)
                                    val entryId = args["id"]?.jsonPrimitive?.contentOrNull
                                    val original = entryId?.let { garden.read(it) ?: error("missing_entry") }
                                    val day = LocalDate.parse(arg("date")); val kind = OrbisGardenKind.valueOf(arg("kind"))
                                    require(original == null || original.kind == kind && gardenEntryDate(original, ZoneId.systemDefault()) == day)
                                    editing = GardenWebEdit(id, original, kind, day)
                                }
                                "extrasLoad" -> { require(arg("module") == "library"); reply(id, Json.encodeToJsonElement(extras.load("library"))) }
                                "extrasSave" -> { require(arg("module") == "library"); reply(id, Json.encodeToJsonElement(extras.save("library", args.getValue("expectedRevision").jsonPrimitive.int, args.getValue("data").jsonObject))) }
                                "importBook" -> { check(importing == null); importing = id; bookPicker.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown")) }
                                "exportLibrary" -> {
                                    check(exportingLibrary == null)
                                    exportingLibrary = GardenWebExport(id, GardenLibraryBackup.encode(args.getValue("data").jsonObject))
                                    confirmLibraryExport = true
                                }
                                "importLibrary" -> { check(importingLibrary == null); importingLibrary = id; libraryPicker.launch(arrayOf("application/json", "text/plain")) }
                                "settings" -> { reply(id); currentSettings() }
                                "management" -> { reply(id); management = true }
                                "soup" -> { reply(id); soup = true }
                                "chat" -> { reply(id); currentOpenChat() }
                                else -> reply(id, error = "未提供此项本地操作。")
                            }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { reply(id, error = "本地操作未确认成功，原数据保留；请重新读取核对，不会自动覆盖或重试。") }
                    }
                }
                loadUrl(GardenAssetPolicy.HOME)
            }
        }, onRelease = { released ->
            if (view === released) view = null
            WebViewCompat.removeWebMessageListener(released, "OrbisGardenBridge")
            released.stopLoading(); released.destroy(); active.clear()
        })
        if (management) Surface(Modifier.fillMaxSize()) {
            Column { TextButton(onClick = { management = false }) { Text("‹ 返回后花园") }
                Box(Modifier.weight(1f)) { OrbisLocalGarden(config) } }
        }
        if (soup) Surface(Modifier.fillMaxSize()) { OrbisLocalSoupPanel(onClose = { soup = false }) }
    }
    if (confirmLibraryExport) AlertDialog(
        onDismissRequest = {
            confirmLibraryExport = false
            exportingLibrary?.let { reply(it.requestId, buildJsonObject { put("cancelled", true) }) }; exportingLibrary = null
        }, title = { Text("导出藏书阁备份？") },
        text = { Text("文件包含本机书籍全文、书签、进度和批注，为明文 JSON。不会包含聊天或模型配置。选择云盘保存位置时，由你选择的云盘应用处理；请自行妥善保管。") },
        confirmButton = { TextButton(onClick = { confirmLibraryExport = false; libraryExporter.launch("Orbis-藏书阁-${LocalDate.now()}.json") }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { confirmLibraryExport = false
            exportingLibrary?.let { reply(it.requestId, buildJsonObject { put("cancelled", true) }) }; exportingLibrary = null
        }) { Text("取消") } },
    )
    editing?.let { edit -> key(edit.requestId) {
        OrbisGardenEditor(edit.original, edit.kind, config.humanName,
            onDismiss = { editing = null; reply(edit.requestId) },
            onSave = { title, body, author ->
                if (edit.original == null) garden.create(edit.kind, title, body, author, edit.date)
                else garden.update(edit.original, title, body, author)
                editing = null; reply(edit.requestId)
            }, onDelete = { garden.delete(requireNotNull(edit.original)); editing = null; reply(edit.requestId) })
    } }
}
