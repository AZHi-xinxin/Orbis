package me.rerere.rikkahub.ui.pages.orbis

import android.annotation.SuppressLint
import android.webkit.*
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.data.repository.ConversationRepository
import androidx.lifecycle.LifecycleEventObserver
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.tabletop.*
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject

private const val TABLETOP_ORIGIN="https://appassets.androidplatform.net"
private fun tabletopMain(url:String?)=url?.substringBefore('#') in setOf("$TABLETOP_ORIGIN/index.html","$TABLETOP_ORIGIN/werewolf.html","$TABLETOP_ORIGIN/drawguess.html","$TABLETOP_ORIGIN/heartprison.html")

@Composable
internal fun OrbisTabletopPage(assistantId:String,originConversationId:String?=null,gameKind:String="tabletop",close:()->Unit) {
    if(gameKind=="boardgames") { OrbisBoardgamesPage(assistantId,originConversationId,close);return }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val settings=koinInject<SettingsStore>();val chat=koinInject<ChatService>()
    val repo=koinInject<ConversationRepository>()
    val host=remember { TabletopConversationHost.get(context) }
    var preferredChat by remember(assistantId) { mutableStateOf(originConversationId) }
    val lifecycle=LocalLifecycleOwner.current
    val created=remember(assistantId,gameKind) { runCatching { TabletopClient(context,assistantId,gameKind) } }
    val api=created.getOrNull()
    var preparation by remember { mutableStateOf(false) }
    var dressingPerson by remember { mutableStateOf<String?>(null) }
    var quickChatRequest by remember { mutableIntStateOf(0) }
    var quickChatOpen by remember { mutableStateOf(false) }
    val companion by host.state(assistantId).collectAsState()
    val running=companion.enabled
    var localState by remember { mutableStateOf(created.exceptionOrNull()?.message) }
    val state=localState ?: companion.message
    suspend fun connectAssistant() {
        if(api==null || api.room.isBlank())return
        val saved=host.current(assistantId)?.takeIf { it.room==api.room }?.conversationId ?: host.saved(assistantId,api.room)
        val target= listOfNotNull(preferredChat,saved).distinct().firstNotNullOfOrNull { id ->
            repo.getConversationById(Uuid.parse(id))?.takeIf { it.assistantId.toString()==assistantId }
        } ?: repo.getConversationsOfAssistantPage(Uuid.parse(assistantId),0,1).items.firstOrNull()
        if(target==null) { localState="先和这位助手聊一句，再回圆桌一起玩。";return }
        preferredChat=target.id.toString()
        host.bind(api,target.id.toString(),target.title.ifBlank { "未命名聊天" })
        host.setVisible(assistantId,true)
        val name=settings.settingsFlow.value.assistants.firstOrNull { it.id.toString()==assistantId }?.name ?: "你的 AI"
        localState=null
        host.report(assistantId,"本局同伴：$name · 沿用原聊天、记忆和连接；可以自由换窗口。")
        host.listen(assistantId,chat)
    }
    DisposableEffect(lifecycle,assistantId) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME)host.setVisible(assistantId,true)
            if(event==Lifecycle.Event.ON_PAUSE)host.setVisible(assistantId,false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);host.setVisible(assistantId,false) }
    }
    var picker by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var exporter by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picker?.complete(it);picker=null }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { exporter?.complete(it);exporter=null }
    DisposableEffect(Unit) { onDispose { picker?.cancel();exporter?.cancel() } }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        TabletopEdgeToEdgeBars()
        OrbisInterfaceTheme {
            Surface(Modifier.fillMaxSize(), color=androidx.compose.ui.graphics.Color(when(gameKind) {
                "drawguess" -> 0xFF241D2D; "heartprison" -> 0xFF091222
                "werewolf" -> 0xFF20352C; else -> 0xFF1B2521
            })) {
                Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().imePadding()) {
                    if(api==null) Surface(color=OrbisTheme.colors.page, contentColor=OrbisTheme.colors.ink) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick=close) { Text("‹ 返回") }
                            Text("请先在社区完成登录和当前 AI 绑定。已有 Orbis 账号可直接登录，无需再申请邀请码。")
                            OrbisInvitationGuide()
                        }
                    }
                    else TabletopWeb(gameKind,buildJsonObject { put("running",running);put("state",state) },audioBlocked=quickChatOpen || preparation || dressingPerson!=null) { action,args ->
                        when(action) {
                            "host_status"->buildJsonObject { put("running",running);put("state",state) }
                            "quick_chat"->{quickChatRequest++;buildJsonObject { put("opened",true) }}
                            "wardrobe_read"->api.wardrobe(args.ms("person"))
                            "wardrobe_save"->api.wardrobe(args.ms("person"),args["body"]!!.jsonObject)
                            "wardrobe_editor"->{
                                require(args.ms("person") in setOf("human","ai"));api.identities()
                                dressingPerson=args.ms("person");buildJsonObject {put("opened",true)}
                            }
                            "prepare"->{check(api.room.isNotBlank()) { "请先入席" };preparation=true;buildJsonObject { put("opened",true) }}
                            "toggle_ai"->{
                                check(api.room.isNotBlank()) { "请先和你的 AI 入席。" }
                                if(!running && host.current(assistantId)==null)connectAssistant()
                                check(host.current(assistantId)!=null) { "先和这位助手聊一句，再来一起玩。" }
                                host.setAutomatic(assistantId,!running);host.listen(assistantId,chat)
                                buildJsonObject { put("running",host.state(assistantId).value.enabled) }
                            }
                            "reconcile"->{
                                val human=api.reconcile(false)
                                host.confirmHumanLeave(api,human)
                                val ai=if(api.room.isNotBlank())api.reconcile(true) else buildJsonObject {}
                                host.report(assistantId,if(api.room.isBlank())"双方已离席，监听已停止。" else "回执已核对，本局监听继续。")
                                buildJsonObject { put("human",human);put("ai",ai) }
                            }
                            "close"->{host.setVisible(assistantId,false);close();buildJsonObject { put("closed",true) }}
                            "resume"->api.resume().also { if(api.room.isBlank()){if(host.currentGameKind(assistantId)==gameKind)host.clear(assistantId)} else connectAssistant() }
                            "restore_connection"->{
                                val result=api.resume()
                                check(api.room.isNotBlank()) { "原席位未找到，请在大厅确认当前对局。" }
                                connectAssistant()
                                val personal=api.game("script_status",asAi=true)
                                check(personal.ms("id")==api.room && host.current(assistantId)?.room==api.room) { "助手连接尚未恢复，请重试。" }
                                if(gameKind in setOf("werewolf","drawguess","heartprison"))check(host.tools(assistantId,false).any { it.name=="${gameKind}_status" }) { "游戏工具尚未恢复，请重试。" }
                                JsonObject(result+mapOf("assistant_verified" to JsonPrimitive(true)))
                            }
                            "watch"->api.connect(args.ms("room"),watch=true).also { connectAssistant() }
                            "connect"->api.connect(args.ms("room"),aiOnly=args["ai_only"]?.jsonPrimitive?.booleanOrNull==true).also { connectAssistant() }
                            "asset"->api.asset(args.ms("name"),args.ms("observe_room"))
                            "retry_upload"->api.submitUpload()
                            "api"->{
                                val path=args.ms("path");val body=args["body"] as? JsonObject
                                val requestedRoom=api.room
                                try { if(path.startsWith("script_") || path in setOf("waiting","wardrobe"))api.game(path,body,
                                    (args["query"] as? JsonObject).orEmpty().mapValues { (_,v)->v.jsonPrimitive.content }).also {
                                        if(it.mb("left"))host.confirmHumanLeave(api,it)
                                    }
                                else api.publicApi(path,body,(args["query"] as? JsonObject).orEmpty().mapValues { (_,v)->v.jsonPrimitive.content })
                                } catch(e:MarketProblem) {
                                    if(gameKind=="tabletop" && e.details.ms("error") in setOf("not_member","left_table","room_missing","membership_expired"))host.invalidateConnection(api,requestedRoom)
                                    throw e
                                }
                            }
                            "pick_room_image"->{
                                check(picker==null){"文件选择器已打开"};val wait=CompletableDeferred<Uri?>();picker=wait;pick.launch(arrayOf("image/*"))
                                val uri=wait.await() ?: error("已取消选择")
                                api.chatImage(uri,args["phase_version"]!!.jsonPrimitive.int)
                            }
                            "upload"->{
                                check(picker==null){"文件选择器已打开"};val wait=CompletableDeferred<Uri?>();picker=wait;pick.launch(arrayOf("application/zip","application/x-zip-compressed"))
                                val uri=wait.await() ?: error("已取消选择");api.upload(uri)
                            }
                            "template"->{
                                val bytes=api.template();check(exporter==null);val wait=CompletableDeferred<Uri?>();exporter=wait;export.launch("Orbis-剧本杀投稿模板.zip")
                                val uri=wait.await() ?: error("已取消保存")
                                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法保存文件")}
                                buildJsonObject { put("saved",true) }
                            }
                            else->error("不支持的圆桌操作")
                        }
                    }
                }
                OrbisGardenQuickChat(true,assistantId,openRequest=quickChatRequest,
                    onOpenChanged={ quickChatOpen=it },
                    initialConversationId=preferredChat,tabletopPresentation=true,
                    onConversationSelected={ chosen ->
                        preferredChat=chosen.id.toString();host.useConversation(assistantId,chosen.id.toString(),chosen.title)
                    })
                }
            }
        }
    }
    if(preparation && api!=null)TabletopPreparationDialog(assistantId,api.room) { preparation=false }
    dressingPerson?.let { person -> if(api!=null) {
        me.rerere.rikkahub.ui.pages.orbis.community.PixelWardrobeDialog(
            person=(if(person=="human") api.human else api.ai)!!,
            wardrobeScope=if(gameKind in setOf("drawguess","heartprison"))"werewolf" else gameKind,label=if(gameKind=="heartprison") "心牢装扮" else if(gameKind=="drawguess") "画桌装扮" else if(gameKind=="werewolf") "狼人杀装扮" else "剧本杀装扮",
            load={api.wardrobe(person)},
            save={body->api.wardrobe(person,JsonObject(body+mapOf("request_id" to JsonPrimitive(java.util.UUID.randomUUID().toString()))))},
            close={dressingPerson=null},saved={})
    } }

}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabletopWeb(gameKind:String,hostStatus:JsonObject,audioBlocked:Boolean=false, command:suspend (String,JsonObject)->JsonObject) {
    val current by rememberUpdatedState(command);val scope=rememberCoroutineScope()
    val theme by rememberUpdatedState(if(MaterialTheme.colorScheme.background.luminance()<.5f)"dark" else "light")
    var failed by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val lifecycle=LocalLifecycleOwner.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val audioActive by rememberUpdatedState(resumed && !audioBlocked)
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME)resumed=true
            if(event==Lifecycle.Event.ON_PAUSE){resumed=false;web?.evaluateJavascript(tabletopAudioScript(false),null)}
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler { val view=web;if(failed || view==null)scope.launch { current("close",buildJsonObject {}) } else view.evaluateJavascript("Boolean(window.tabletopNavigateBack && (window.tabletopNavigateBack(),true));") { result -> if(result!="true")scope.launch { current("close",buildJsonObject {}) } } }
    if(failed || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        Text("本机 WebView 暂时不能打开圆桌；资料没有重置。",Modifier.padding(20.dp));return
    }
    AndroidView(modifier=Modifier.fillMaxSize(),factory={context ->
        createTabletopWebView(context,scope,{a,b->current(a,b)}, { failed=true }, {theme},gameKind,{audioActive}).also { web=it }
    },update={view -> if(tabletopMain(view.url))view.evaluateJavascript("document.documentElement.dataset.theme="+Json.encodeToString(theme)+";window.tabletopHostStatus="+hostStatus+";"+tabletopAudioScript(audioActive),null)},onRelease={view ->runCatching { view.stopLoading() };runCatching { WebViewCompat.removeWebMessageListener(view,"OrbisTabletopBridge") };runCatching { view.destroy() }})
}

private fun tabletopAudioScript(active:Boolean)="window.tabletopAudioActive=$active;window.dispatchEvent(new Event('tabletop-audio-visibility'));"

@SuppressLint("SetJavaScriptEnabled")
internal fun createTabletopWebView(context:android.content.Context,scope:CoroutineScope,current:suspend (String,JsonObject)->JsonObject,onFailure:()->Unit,theme:()->String={"light"},gameKind:String="tabletop",audioActive:()->Boolean={true}):WebView {
        val loader=WebViewAssetLoader.Builder().addPathHandler("/") { path ->
            if(path.contains("..") || path.startsWith('/') || path.contains('\\'))null
            else runCatching {
                val type=when(path.substringAfterLast('.')){"js"->"text/javascript";"css"->"text/css";"json"->"application/json";"html"->"text/html";"png"->"image/png";"webp"->"image/webp";"woff2"->"font/woff2";"mp3"->"audio/mpeg";"ogg"->"audio/ogg";"wav"->"audio/wav";else->"application/octet-stream"}
                WebResourceResponse(type,"UTF-8",200,"OK",mapOf("Cache-Control" to "no-store"),context.assets.open("tabletop/$path"))
            }.getOrNull()
        }.build()
        return WebView(context).apply {
            // Insets move controls inside the page; the WebView/background stays edge to edge.
            var insetScript = ""
            fun applyPageInsets(insets: WindowInsetsCompat) {
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val density = resources.displayMetrics.density.coerceAtLeast(1f)
                val bottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) 0 else safe.bottom
                insetScript = listOf("top" to safe.top, "right" to safe.right, "bottom" to bottom, "left" to safe.left)
                    .joinToString("") { (side, pixels) ->
                        "document.documentElement.style.setProperty('--orbis-safe-$side','${pixels / density}px');"
                    }
                if (tabletopMain(url)) evaluateJavascript(insetScript, null)
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> applyPageInsets(insets); insets }
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) { ViewCompat.requestApplyInsets(v) }
                override fun onViewDetachedFromWindow(v: android.view.View) = Unit
            })
            configureOrbisHomeLayout()
            // Bundled pages must follow the installed APK, including after an upgrade.
            settings.cacheMode=WebSettings.LOAD_NO_CACHE
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false;settings.blockNetworkLoads=true
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false);settings.mediaPlaybackRequiresUserGesture=true
            webChromeClient=object:WebChromeClient(){override fun onPermissionRequest(request:PermissionRequest)=request.deny()}
            webViewClient=object:WebViewClient(){
                override fun onPageFinished(view:WebView,url:String?) { if(tabletopMain(url))view.evaluateJavascript("document.documentElement.dataset.theme="+Json.encodeToString(theme())+";"+tabletopAudioScript(audioActive())+insetScript,null) }
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                    if(request.method=="GET" && request.url.scheme=="https" && request.url.host=="appassets.androidplatform.net")loader.shouldInterceptRequest(request.url)?.let { return it }
                    return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean { onFailure();return true }
            }
            val active=mutableSetOf<String>()
            WebViewCompat.addWebMessageListener(this,"OrbisTabletopBridge",setOf(TABLETOP_ORIGIN)) { source,message,origin,main,_ ->
                if(!main || origin.toString()!=TABLETOP_ORIGIN || !tabletopMain(source.url))return@addWebMessageListener
                val raw=message.data ?: return@addWebMessageListener
                if(raw.toByteArray().size>(if(gameKind=="drawguess")1650000 else 65536))return@addWebMessageListener
                val m=runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@addWebMessageListener
                val id=m.ms("id");if(!id.matches(Regex("[0-9]{1,12}")) || id in active || active.size>=8)return@addWebMessageListener
                active.add(id)
                scope.launch {
                    try {
                        val result=current(m.ms("action"),m["args"] as? JsonObject ?: buildJsonObject {})
                        if(tabletopMain(source.url))source.evaluateJavascript("window.tabletopNativeResult("+Json.encodeToString(id)+","+result+",null);",null)
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){if(tabletopMain(source.url))source.evaluateJavascript("window.tabletopNativeResult("+Json.encodeToString(id)+",null,"+buildJsonObject { put("message",e.message ?: "请求未完成，请核对回执");if(e is MarketProblem){put("status",e.status);put("code",e.details.ms("error"))} }.toString()+");",null)}
                    finally { active.remove(id) }
                }
            }
            loadUrl("$TABLETOP_ORIGIN/"+(when(gameKind){"werewolf"->"werewolf.html";"drawguess"->"drawguess.html";"heartprison"->"heartprison.html";else->"index.html"}))
        }

}
