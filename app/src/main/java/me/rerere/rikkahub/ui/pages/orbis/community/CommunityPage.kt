package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.pages.orbis.OrbisInvitationGuide
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*
import org.koin.compose.koinInject

private val Paper: Color @Composable get() = CommunityPaper
private val Ink: Color @Composable get() = CommunityInk
private val Muted: Color @Composable get() = CommunityMuted
private val Accent: Color @Composable get() = MaterialTheme.colorScheme.secondary

@Composable
internal fun CommunityPage(assistantId: String, close: () -> Unit) {
    val context=LocalContext.current
    val account=remember { MarketClient(context) }
    var signedIn by remember { mutableStateOf(account.hasSession()) }
    var recovery by remember { mutableStateOf(account.recoveryCode()) }
    Dialog(onDismissRequest=close, properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=if(recovery.isNotBlank()) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)) {
        Box(Modifier.fillMaxSize()) {
        CommunityTheme {
        CommunityStatusBar()
        Surface(Modifier.fillMaxSize(),color=Paper,contentColor=Ink) {
            if (recovery.isNotBlank()) Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("保存账号恢复码",fontSize=24.sp)
                Text("这是群聊、朋友圈、市场共用账号的恢复码。请妥善抄存；密码和恢复码同时丢失后无法找回。")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(recovery) }
                Button(onClick={ account.acknowledgeRecovery(); recovery="" }) { Text("我已妥善保存") }
            } else if (!signedIn) CommunityLogin(account,{ signedIn=true; recovery=account.recoveryCode() },close)
            else {
                val api=remember(signedIn) { CommunityClient(context) }
                CommunityHome(api,assistantId,close,login={ signedIn=false })
            }
        }
    }
        if(signedIn && recovery.isBlank()) me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme {
            me.rerere.rikkahub.ui.pages.orbis.OrbisGardenQuickChat(true,assistantId)
        }
        }
}
}

@Composable
private fun CommunityLogin(account: MarketClient, ready: () -> Unit, close: () -> Unit) {
    val scope=rememberCoroutineScope()
    var mode by remember { mutableStateOf("login") }
    var login by rememberSaveable { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }; var invite by remember { mutableStateOf("") }
    var recoverCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        TextButton(onClick=close) { Text("‹ 返回") }
        Text("相遇，在这里",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
        Text("一个 Orbis 账号通用群聊、朋友圈、市场与圆桌游戏。已有市场账号可直接登录，无需新邀请码。",color=Muted)
        OrbisInvitationGuide()
        OutlinedTextField(login,{ login=it },label={ Text("账号") },singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{ password=it },label={ Text("密码") },singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        if(mode=="register") {
            OutlinedTextField(name,{ name=it },label={ Text("昵称") },singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(invite,{ invite=it },label={ Text("Orbis 通用内测邀请码") },singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        if(mode=="recover") OutlinedTextField(recoverCode,{ recoverCode=it },label={ Text("账号恢复码") },visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        Button(enabled=!busy,onClick={ busy=true; scope.launch {
            try { account.auth(mode,communityBody("login" to login,"password" to password,"display" to name,"invite" to invite,"recovery_code" to recoverCode,"device" to "Orbis Android")); ready() }
            catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "连接失败" } finally { busy=false }
        } },modifier=Modifier.fillMaxWidth()) { Text(if(busy) "连接中…" else if(mode=="login") "登录" else if(mode=="recover") "重置密码并领取新恢复码" else "注册") }
        TextButton(enabled=!busy,onClick={ mode=if(mode=="login") "register" else "login" }) { Text(if(mode=="login") "使用邀请码注册" else "已有账号，登录") }
        TextButton(enabled=!busy,onClick={ mode="recover" }) { Text("用恢复码找回账号") }
    }
}

/** Each route/identity owns its response state synchronously; stale network replies cannot cross tabs. */
@Stable
internal class CommunityViewState {
    var data by mutableStateOf(buildJsonObject {})
    var rows by mutableStateOf(emptyList<JsonObject>())
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var scrollToLatest by mutableIntStateOf(0)
    var nextAfter by mutableStateOf<Long?>(null)
    var unreadAnchor by mutableStateOf<JsonObject?>(null)
    var jumpSequence by mutableStateOf<Long?>(null)
}

@Composable
internal fun CommunityHome(api: CommunityClient, assistantId: String, close: () -> Unit, login: () -> Unit,
    settingsStore: SettingsStore = koinInject()) {
    CommunityTheme { CommunityHomeContent(api,assistantId,close,login,settingsStore) }
}

@Composable
private fun CommunityHomeContent(api: CommunityClient, assistantId: String, close: () -> Unit, login: () -> Unit, settingsStore: SettingsStore) {
    val scope=rememberCoroutineScope(); val life=LocalLifecycleOwner.current
    val settings by settingsStore.settingsFlow.collectAsState()
    val assistant=settings.assistants.singleOrNull { it.id.toString()==assistantId }
    var identities by remember { mutableStateOf(emptyList<JsonObject>()) }
    var selected by rememberSaveable { mutableStateOf("") }
    val identity=identities.singleOrNull { it.ms("id")==selected }
    val human=identity?.ms("kind")=="human"
    var tab by rememberSaveable { mutableStateOf("chat") }
    var roomId by rememberSaveable { mutableStateOf("") }; var postId by rememberSaveable { mutableStateOf("") }
    var feedNoticesOpen by remember(selected) { mutableStateOf(false) }
    var gatheringOpen by rememberSaveable { mutableStateOf(false) }
    var postStartAfter by remember(selected) { mutableLongStateOf(0L) }
    var noticeComment by remember(selected) { mutableStateOf("") }
    var feedReadReceipt by remember(selected) { mutableStateOf<JsonObject?>(null) }
    val postList=remember(selected,postId) { LazyListState() }
    var tick by remember { mutableIntStateOf(0) }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<JsonObject?>(null) }
    var number by rememberSaveable { mutableStateOf("") }
    var groupSearch by rememberSaveable { mutableStateOf("") }
    var composer by rememberSaveable(selected,roomId,postId) { mutableStateOf("") }
    var publish by rememberSaveable { mutableStateOf(false) }
    var visibility by rememberSaveable { mutableStateOf("friends") }
    var images by remember(selected,roomId,publish) { mutableStateOf(emptyList<String>()) }
    var quote by remember(selected,roomId) { mutableStateOf<JsonObject?>(null) }
    var mentions by remember(selected,roomId) { mutableStateOf(emptyList<JsonObject>()) }
    val composerFocus=remember(selected,roomId) { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    var mentionFocusRequest by remember(selected,roomId) { mutableIntStateOf(0) }
    var feedMentionPicker by remember(selected,postId,publish) { mutableStateOf(false) }
    var feedMentions by remember(selected,postId,publish) { mutableStateOf(emptyList<JsonObject>()) }
    var commentReply by remember(selected,postId) { mutableStateOf<JsonObject?>(null) }
    var chooseFriends by remember { mutableStateOf(false) }
    var groupMembers by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf(false) }
    var groupTitle by rememberSaveable { mutableStateOf("") }
    var memberRows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var memberOffset by remember { mutableStateOf<Int?>(0) }
    var newName by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var friendRows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var friendOffset by remember { mutableStateOf<Int?>(0) }
    var blockedOpen by remember(selected) { mutableStateOf(false) }
    var blockedRows by remember(selected) { mutableStateOf(emptyList<JsonObject>()) }
    var blockedNext by remember(selected) { mutableStateOf<Int?>(null) }
    var blockedError by remember(selected) { mutableStateOf("") }
    var clearImages by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var deletingComment by remember(selected,postId) { mutableStateOf<JsonObject?>(null) }
    var profileName by remember { mutableStateOf("") }; var bio by remember { mutableStateOf("") }
    val route=when { roomId.isNotEmpty()->"/rooms/$roomId/messages"; postId.isNotEmpty()->"/posts/$postId"; tab=="chat"->"/rooms"; tab=="feed"->"/feed"; tab=="contacts"->"/contacts"; else->"/profile" }
    val feedState=remember(selected) { CommunityViewState() }
    val feedList=remember(selected) { LazyListState() }
    val otherState=remember(selected,route) { CommunityViewState() }
    val state=if(route=="/feed") feedState else otherState
    fun mentionMember(member: JsonObject) {
        val id=member.ms("id")
        if(!human || busy || roomId.isEmpty() || id.isBlank() || id==selected) return
        if(mentions.size>=20 && mentions.none { it.ms("id")==id }) {
            message="每条消息最多艾特 20 位成员"
            return
        }
        mentions=(mentions+member).distinctBy { it.ms("id") }
        groupMembers=false
        mentionFocusRequest++
    }
    LaunchedEffect(mentionFocusRequest) {
        if(mentionFocusRequest>0 && human && roomId.isNotEmpty()) {
            // Let the member picker close before focusing the underlying composer.
            withFrameNanos { }
            composerFocus.requestFocus()
            keyboard?.show()
        }
    }
    fun refreshFeed() { feedState.loading=true; feedState.rows=emptyList(); feedState.next=null; feedList.requestScrollToItem(0) }
    LaunchedEffect(selected,profile?.ms("id")) {
        val id=profile?.ms("id") ?: return@LaunchedEffect
        try { val full=api.get("/profile?id=$id",selected); if(profile?.ms("id")==id) profile=full }
        catch(e:CancellationException) { throw e } catch(_:Exception) { }
    }
    val chatList=remember(selected,roomId) { LazyListState() }
    fun atLatest()=chatList.layoutInfo.totalItemsCount==0 || !chatList.canScrollForward
    fun run(action: suspend () -> Unit) {
        if(busy) return
        busy=true; message=""
        scope.launch {
            try { action() } catch(e:CancellationException) { throw e }
            catch(e:Exception) { message=e.message ?: "连接未完成，请到“我的”核对回执。"; if(e is MarketProblem && e.status==401) login() }
            finally { busy=false }
        }
    }
    suspend fun loadBlocked(offset: Int=0) {
        val page=api.get("/blocks?limit=20&offset=$offset",selected)
        blockedRows=if(offset==0) page.ma("items") else (blockedRows+page.ma("items")).distinctBy { it.ms("id") }
        blockedNext=(page["next_offset"] as? JsonPrimitive)?.intOrNull
    }
    fun runBlocked(action: suspend () -> Unit) = run {
        blockedError=""
        try { action() }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { blockedError=e.message ?: "操作未完成，请稍后重试"; throw e }
    }
    suspend fun load(offset: String?=null, history: Boolean=false, followLatest: Boolean=false,
        fromSequence: Long?=null, afterSequence: Long?=null) {
        val follow=roomId.isNotEmpty() && !history && fromSequence==null && afterSequence==null && (followLatest || state.loading || atLatest())
        val acknowledgeLatest=human && roomId.isNotEmpty() && !history && offset==null &&
            fromSequence==null && afterSequence==null && (state.loading || followLatest)
        val anchor=if(history) chatList.layoutInfo.visibleItemsInfo.firstOrNull { it.key.toString().startsWith("message:") } else null
        val parameter=if(postId.isNotEmpty()) "after" else if(roomId.isNotEmpty() || tab=="feed") "before" else "offset"
        val query=fromSequence?.let { "?from=$it&limit=50" } ?: afterSequence?.let { "?after=$it&limit=50" }
            ?: offset?.let { "?$parameter=$it" }
            ?: if(postId.isNotEmpty() && postStartAfter>0) "?after=$postStartAfter&limit=20" else ""
        val result=api.get(route+query,selected)
        if(route=="/profile" && result.ms("id")==selected) {
            identities=identities.map { if(it.ms("id")==selected) JsonObject(it+result) else it }
        }
        val rows=if(postId.isNotEmpty()) result.ma("replies") else result.ma("items")
        if(human && roomId.isNotEmpty() && state.loading) {
            state.unreadAnchor=(result["unread_range"] as? JsonObject)?.takeIf { it.mi("count")>0 }
        }
        state.data=if(tab=="me" && roomId.isEmpty() && postId.isEmpty()) JsonObject(result+mapOf("reminders" to api.get("/hints",selected))) else result
        state.rows=if(roomId.isNotEmpty()) ((if(fromSequence!=null || followLatest) emptyList() else state.rows)+rows).associateBy { it.ms("id") }.values.sortedBy { it.cl("seq") }
            else if(offset==null) rows else (state.rows+rows).distinctBy { it.ms("id") }
        state.next=when {
            roomId.isNotEmpty()->if(fromSequence!=null) rows.firstOrNull()?.ms("seq")
                else if(offset!=null || state.loading || followLatest) rows.firstOrNull()?.ms("seq")?.takeIf { result.mb("more") } else state.next
            postId.isNotEmpty()->result.ms("next_after").takeUnless { it.isEmpty() || it=="null" } ?: rows.lastOrNull()?.ms("seq")?.takeIf { result.mb("more") }
            tab=="feed"->result.ms("next_before").takeUnless { it.isEmpty() || it=="null" }
            else->result.ms("next_offset").takeUnless { it.isEmpty() || it=="null" }
        }
        state.loading=false
        if(followLatest) { state.nextAfter=null; state.unreadAnchor=null; state.jumpSequence=null }
        if(fromSequence!=null || afterSequence!=null) {
            state.nextAfter=rows.lastOrNull()?.cl("seq")?.takeIf { result.mb("more") }
            if(fromSequence!=null) { state.unreadAnchor=null; state.jumpSequence=rows.firstOrNull()?.cl("seq") }
        }
        if(anchor!=null) {
            val index=state.rows.indexOfFirst { "message:"+it.ms("id")==anchor.key }
            if(index>=0) chatList.requestScrollToItem(index+(if(state.next!=null) 1 else 0),-anchor.offset)
        }
        if(follow) state.scrollToLatest++
        if(human && postId.isNotEmpty() && result.mb("feed_read_receipts")) {
            feedReadReceipt=api.post("/feed/read",selected,buildJsonObject {
                put("post",postId);put("post_read",true);put("comments",JsonArray(rows.map {JsonPrimitive(it.ms("id"))}))
            })
        }
        if(human && roomId.isNotEmpty() && rows.isNotEmpty()) {
            if(acknowledgeLatest) {
                // Opening the human chat clears its existing badge through this returned tail.
                // Keep the entry snapshot above for jumping back; later arrivals stay unread.
                val through=rows.maxOf { it.cl("seq") }
                val receipt=api.post("/read",selected,buildJsonObject { put("room",roomId); put("seq",through) })
                require(receipt.cl("read_seq")>=through) { "群消息已读状态未确认，请稍后刷新核对" }
            } else {
                api.post("/read",selected,buildJsonObject { put("room",roomId); put("sequences",JsonArray(rows.map { JsonPrimitive(it.cl("seq")) })) })
            }
        }
    }
    LaunchedEffect(api.owner) {
        try {
            var list=api.identities()
            if(list.isEmpty()) { if(api.pending("")) api.reconcile("") else api.post("/bootstrap"); list=api.identities() }
            identities=list
            if(list.none { it.ms("id")==selected }) selected=list.first { it.ms("kind")=="human" }.ms("id")
        } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "无法读取社区账号" }
    }
    LaunchedEffect(state,state.scrollToLatest) {
        if(roomId.isEmpty() || state.scrollToLatest==0 || state.rows.isEmpty()) return@LaunchedEffect
        val count=state.rows.size+(if(state.next!=null) 1 else 0)
        snapshotFlow { chatList.layoutInfo.totalItemsCount }.first { it>=count }
        chatList.scrollToItem(count-1)
    }
    LaunchedEffect(state,state.jumpSequence) {
        val seq=state.jumpSequence ?: return@LaunchedEffect
        val index=state.rows.indexOfFirst { it.cl("seq")==seq }
        if(index>=0) {
            val target=index+(if(state.next!=null) 1 else 0)
            snapshotFlow { chatList.layoutInfo.totalItemsCount }.first { it>target }
            chatList.scrollToItem(target)
        }
    }
    LaunchedEffect(state.rows,postId,noticeComment) {
        if(postId.isEmpty() || noticeComment.isEmpty()) return@LaunchedEffect
        val index=state.rows.indexOfFirst {it.ms("id")==noticeComment}
        if(index>=0) {
            val target=index+1
            snapshotFlow {postList.layoutInfo.totalItemsCount}.first {it>target}
            postList.scrollToItem(target)
        }
    }
    LaunchedEffect(state,tick) {
        if(selected.isEmpty()) return@LaunchedEffect
        try { if(route!="/feed" || state.loading) load() } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "加载失败"; state.loading=false }
        if(roomId.isNotEmpty()) life.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                delay(7000)
                try {
                    if(state.nextAfter!=null) continue // Finish the selected forward page before polling the newest tail.
                    val follow=atLatest()
                    val after=state.rows.maxOfOrNull { it.cl("seq") } ?: 0
                    val result=api.get("/rooms/$roomId/messages?after=$after",selected)
                    val added=result.ma("items")
                    if(human && !follow && state.unreadAnchor==null) {
                        state.unreadAnchor=(result["unread_range"] as? JsonObject)?.takeIf { it.mi("count")>0 }
                    }
                    // Refresh visible recent records as well: a recall can change an old row or its quote.
                    val visibleIds=state.rows.map { it.ms("id") }.toSet()
                    val latest=api.get("/rooms/$roomId/messages?limit=50",selected).ma("items").filter { it.ms("id") in visibleIds }
                    val combined=(state.rows+latest+added).associateBy { it.ms("id") }.values.sortedBy { it.cl("seq") }
                    state.rows=if(follow) combined.takeLast(200) else combined; state.data=result
                    if(follow && combined.size>200) state.next=state.rows.firstOrNull()?.ms("seq")
                    if(follow && added.isNotEmpty()) state.scrollToLatest++
                    // Polling never writes read receipts or starts a model round.
                } catch(e:CancellationException) { throw e } catch(_:Exception) { }
            }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri!=null) run { images=images+api.upload(selected,uri,roomId.takeIf { it.isNotEmpty() }) } }
    fun back() { when { publish->publish=false; roomId.isNotEmpty()->{ roomId=""; tab="chat" }; postId.isNotEmpty()->{postId="";postStartAfter=0;noticeComment=""}; else->close() } }
    BackHandler { back() }
    var wardrobeOpen by remember { mutableStateOf(false) }
    if(wardrobeOpen && identity!=null) CommunityWardrobeDialog(api,identity,{ wardrobeOpen=false },{ tick++ })
    if(gatheringOpen) CommunityGatherings(api,identities,selected,assistantId) {gatheringOpen=false}
    val avatars=identities.associate { id -> id.ms("id") to (if(id.ms("kind")=="human") settings.displaySetting.userAvatar
        else settings.assistants.singleOrNull { it.id.toString()==id.ms("local_key") }?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy) }
    CompositionLocalProvider(LocalCommunityAvatars provides avatars) {
    Scaffold(containerColor=Paper,contentColor=Ink,
        topBar={ Column(Modifier.statusBarsPadding()) {
            CommunityHeading(when { publish->"写一条动态"; roomId.isNotEmpty()->(state.data["room"] as? JsonObject)?.ms("title").orEmpty().ifEmpty { "群聊" }; postId.isNotEmpty()->"动态详情"; tab=="chat"->"同行"; tab=="feed"->"朋友圈"; tab=="contacts"->"联系人"; else->"公开身份与隐私" }, ::back,
                leading=if(tab=="feed" && !publish && postId.isEmpty() && roomId.isEmpty()) ({
                    OutlinedButton(onClick={gatheringOpen=true},contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.heightIn(min=40.dp)) {Text("拼桌大厅",fontSize=12.sp)}
                }) else null) {
                if(roomId.isNotEmpty()) TextButton(onClick={ groupMembers=true; memberRows=emptyList(); memberOffset=0; run { val r=api.get("/rooms/$roomId/members?limit=50",selected); memberRows=r.ma("items"); memberOffset=(r["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("成员") }
                else if(tab=="feed" && !publish && postId.isEmpty()) {
                    Row {
                    TextButton(onClick={ refreshFeed(); tick++ }) { Text("刷新") }
                    if(human) TextButton(onClick={ publish=true; composer="" }) { Text("＋ 发布") }
                    }
                }
                else TextButton(onClick={ tick++ }) { Text("刷新") }
            }
            if(message.isNotEmpty()) Text(message,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        bottomBar={ if(roomId.isEmpty() && postId.isEmpty() && !publish) {
            Box(Modifier.navigationBarsPadding()) { NavigationBar(containerColor=Paper,tonalElevation=0.dp,windowInsets=WindowInsets(0),modifier=Modifier.height(72.dp)) {
                listOf("chat" to ("♧" to "聊天"),"feed" to ("✦" to "朋友圈"),"contacts" to ("◌" to "联系人"),"me" to ("◇" to "我的")).forEach { (key,parts) ->
                    NavigationBarItem(colors=NavigationBarItemDefaults.colors(selectedIconColor=Accent,selectedTextColor=Ink,unselectedIconColor=Muted,unselectedTextColor=Muted,indicatorColor=Paper),selected=tab==key,onClick={ tab=key },icon={ Text(parts.first,fontSize=24.sp) },label={ Text(parts.second) })
                }
            }
        } } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if(publish) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    identity?.let { Row(verticalAlignment=Alignment.CenterVertically) {
                        CommunityAvatar(it,40)
                        Column(Modifier.padding(start=12.dp)) { Text("以 ${it.ms("name")} 的身份发布",fontWeight=FontWeight.SemiBold); Text("人类 · 自己的账号",color=Muted,fontSize=12.sp) }
                    } }
                    OutlinedTextField(composer,{ composer=it.take(4000) },modifier=Modifier.fillMaxWidth().heightIn(min=150.dp,max=260.dp),placeholder={ Text("今天也想留住一点小小的光。") },shape=RoundedCornerShape(12.dp))
                    CommunityImages(api,selected,images)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        OutlinedButton(enabled=!busy && images.size<9,onClick={ picker.launch("image/*") }) { Text("＋ 添加图片（${images.size}/9）") }
                        if(images.isNotEmpty()) TextButton(onClick={ images=images.dropLast(1) }) { Text("移除末张") }
                    }
                    TextButton(enabled=!busy,onClick={feedMentionPicker=true}) {Text("@ 艾特成员")}
                    if(feedMentions.isNotEmpty()) TextButton(onClick={feedMentions=emptyList()}) {Text(feedMentions.joinToString(" ") {"@"+it.ms("name")}+" · 清除")}
                    Text("谁可以看见",fontSize=20.sp)
                    CommunityChoice("社区公开","所有社区成员都能看到、评论和点赞",visibility=="public") { visibility="public" }
                    CommunityChoice("仅好友可见","只出现在你好友的朋友圈里",visibility=="friends") { visibility="friends" }
                }
                HorizontalDivider(color=CommunityLine)
                Button(enabled=!busy && human && (composer.isNotBlank() || images.isNotEmpty()),modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=46.dp),onClick={ run {
                    api.post("/posts",selected,buildJsonObject { put("text",composer); put("visibility",visibility); put("mentions",JsonArray(feedMentions.map {JsonPrimitive(it.ms("id"))})); put("media",JsonArray(images.map(::JsonPrimitive))) }); composer=""; publish=false; refreshFeed(); tick++
                } }) { Text("发布朋友圈") }
            } else if(roomId.isNotEmpty()) {
                val r=state.data["room"] as? JsonObject
                val allowed=(r?.mi("humans") ?: 0)>=3
                Text(if(allowed) "${r?.mi("humans")} 位人类在群 · 图片可按需下载" else "不足 3 位人类，补齐成员后才能继续发言",Modifier.padding(horizontal=16.dp,vertical=6.dp),color=Muted,fontSize=12.sp)
                state.unreadAnchor?.let { unread ->
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.End) {
                        FilledTonalButton(enabled=!busy,onClick={ run { load(fromSequence=unread.cl("first_sequence")) } },
                            modifier=Modifier.testTag("community-first-unread")) {
                            Text("↑ ${unread.mi("count")} 条新消息 · 从首条查看")
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("community-messages"),state=chatList,contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(state.next!=null) item("history") { TextButton(onClick={ run { load(state.next,true) } }) { Text("更早的消息") } }
                    items(state.rows,key={ "message:"+it.ms("id") }) { row ->
                        val author=row["author"]!!.jsonObject
                        val own=row.ms("actor")==selected
                        var options by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(own) Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Top) {
                            if(!own) {
                                Box(Modifier.testTag("community-message-avatar-${row.ms("id")}").combinedClickable(
                                    onClickLabel="查看名片",onClick={ profile=author },
                                    onLongClickLabel=if(human) "艾特 ${author.ms("name")}" else null,
                                    onLongClick=if(human) ({ mentionMember(author) }) else null
                                )) { CommunityAvatar(author,32) }
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.widthIn(max=280.dp),horizontalAlignment=if(own) Alignment.End else Alignment.Start) {
                                if(row.cl("seq")==state.jumpSequence) Text("本次新消息从这里开始",color=Accent,fontSize=12.sp)
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text(author.ms("name"),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    CommunityKind(author)
                                    if(human && row.mi("removed")==0) Box {
                                        TextButton(onClick={ options=true },contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp),modifier=Modifier.height(28.dp)) { Text("···") }
                                        DropdownMenu(options,{ options=false }) {
                                            DropdownMenuItem(text={ Text("引用回复") },onClick={ options=false; quote=row })
                                            if(own) DropdownMenuItem(text={ Text("撤回") },onClick={ options=false; run { api.post("/messages",selected,communityBody("action" to "remove","room" to roomId,"id" to row.ms("id"))); tick++ } }) }
                                    }
                                }
                                Surface(shape=RoundedCornerShape(12.dp),color=if(own) CommunitySage else CommunityPaper,border=BorderStroke(1.dp,CommunityLine)) {
                                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                        (row["quote"] as? JsonObject)?.let { q ->
                                            Surface(color=CommunityPaper,shape=RoundedCornerShape(8.dp),modifier=Modifier.testTag("community-quote")) {
                                                Text((q["author"] as? JsonObject)?.ms("name")?.let { "$it：" }.orEmpty()+q.ms("text"),Modifier.padding(8.dp),fontSize=12.sp,color=Muted)
                                            }
                                        }
                                        if(row.ma("mentioned_members").isNotEmpty()) Text(row.ma("mentioned_members").joinToString("  ") { "@"+it.ms("name") },fontSize=13.sp,color=Accent)
                                        Text(row.ms("text"),fontSize=14.sp,lineHeight=22.sp); CommunityImages(api,selected,row.cs("media"))
                                    }
                                }
                            }
                            if(own) { Spacer(Modifier.width(8.dp)); Box(Modifier.clickable { profile=author }) { CommunityAvatar(author,32) } }
                        }
                    }
                    state.nextAfter?.let { next -> item("later") {
                        TextButton(enabled=!busy,onClick={ run { load(afterSequence=next) } },
                            modifier=Modifier.testTag("community-read-later")) { Text("继续向后查看") }
                    } }
                }
                if(human) {
                    HorizontalDivider(color=CommunityLine)
                    quote?.let { q -> Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("引用 ${(q["author"] as? JsonObject)?.ms("name").orEmpty()}：${q.ms("text").ifBlank { "[图片]" }}",Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=Muted)
                        TextButton(onClick={ quote=null }) { Text("取消引用") }
                    } }
                    if(mentions.isNotEmpty()) Text(mentions.joinToString("  ") { "@"+it.ms("name") },Modifier.padding(horizontal=12.dp).testTag("community-message-mentions"),fontSize=13.sp,color=Accent)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled=!busy && allowed && images.size<4,onClick={ picker.launch("image/*") }) { Text("＋ 图片 ${images.size}") }
                        TextButton(onClick={ groupMembers=true; run { val r2=api.get("/rooms/$roomId/members?limit=50",selected); memberRows=r2.ma("items"); memberOffset=(r2["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("@ 成员 ${mentions.size}") }
                        if(images.isNotEmpty()) TextButton(onClick={ images=emptyList() }) { Text("移除图片") }
                        if(mentions.isNotEmpty()) TextButton(onClick={ mentions=emptyList() }) { Text("取消 @") }
                    }
                    Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(composer,{ composer=it.take(4000) },modifier=Modifier.weight(1f).focusRequester(composerFocus).testTag("community-message-composer"),maxLines=4,placeholder={ Text("输入消息…") },enabled=allowed,shape=RoundedCornerShape(16.dp))
                        TextButton(enabled=!busy && allowed && (composer.isNotBlank() || images.isNotEmpty()),onClick={ run { api.post("/messages",selected,buildJsonObject { put("room",roomId); put("text",composer); put("media",JsonArray(images.map(::JsonPrimitive))); put("mentions",JsonArray(mentions.map { JsonPrimitive(it.ms("id")) })); quote?.let { put("reply_to",it.ms("id")) } }); composer=""; images=emptyList(); mentions=emptyList(); quote=null; load(followLatest=true) } }) { Text("发送") }
                    }
                } else Text("这是 AI 的群聊视角；发言由 AI 自主决定。",Modifier.padding(16.dp),color=Muted)
            } else if(postId.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).testTag("community-post-details"),state=postList,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    if(state.data.ms("id").isNotEmpty()) item("post") { FeedCard(api,selected,state.data,{ profile=it },{}, { run { api.post("/likes",selected,buildJsonObject { put("post",postId); put("liked",!state.data.mb("liked")) }); tick++ } },human) }
                    items(state.rows,key={ "comment:"+it.ms("id") }) { reply -> Column {
                        if(reply.ms("id")==noticeComment) Text("提醒对应的评论",color=Accent,fontSize=12.sp)
                        Person(reply["author"]!!.jsonObject,{ profile=reply["author"]!!.jsonObject })
                        Text(reply.ms("text"),Modifier.padding(start=42.dp,top=5.dp))
                        (reply["reply_author"] as? JsonObject)?.let { Text("回复 ${it.ms("name")}",color=Muted,fontSize=12.sp) }
                        FeedMentions(reply, {profile=it})
                        if(human && reply.ms("actor")!=selected) TextButton(enabled=!busy,onClick={commentReply=reply}) {Text("回复")}
                        if(human && reply.ms("actor")==selected) TextButton(enabled=!busy,onClick={ deletingComment=reply }) { Text("撤回／删除评论") }
                    } }
                    if(state.next!=null) item("more") { TextButton(onClick={ run { load(state.next) } }) { Text("更多评论") } }
                }
                if(human) Column {
                    Row {
                        TextButton(enabled=!busy,onClick={feedMentionPicker=true}) {Text("@ 艾特")}
                        if(feedMentions.isNotEmpty()) TextButton(onClick={feedMentions=emptyList()}) {Text(feedMentions.joinToString(" ") {"@"+it.ms("name")}+" · 清除")}
                        commentReply?.let { reply -> TextButton(onClick={commentReply=null}) {Text("回复 ${reply["author"]?.jsonObject?.ms("name").orEmpty()} · 取消")} }
                    }
                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(composer,{ composer=it.take(1000) },modifier=Modifier.weight(1f),placeholder={ Text("留下评论…") },maxLines=3)
                    TextButton(enabled=!busy && composer.isNotBlank(),onClick={ run { api.post("/comments",selected,buildJsonObject { put("post",postId);put("text",composer);put("mentions",JsonArray(feedMentions.map {JsonPrimitive(it.ms("id"))}));commentReply?.let {put("reply_to",it.ms("id"))} }); composer="";feedMentions=emptyList();commentReply=null; tick++ } }) { Text("评论") }
                } }
            } else LazyColumn(Modifier.fillMaxSize().testTag("community-list"),state=if(tab=="feed") feedList else androidx.compose.foundation.lazy.rememberLazyListState(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(!human) item("identity") { Text("${identity?.ms("name")} 的 AI 名片视角 · 发帖由 AI 自主决定",fontSize=12.sp,color=Muted) }
                when(tab) {
                    "chat" -> {
                        item("search_groups") { OutlinedTextField(groupSearch,{ groupSearch=it },placeholder={ Text("⌕ 搜索群聊") },modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp)) }
                        item("community_note") { Surface(color=CommunitySage,shape=RoundedCornerShape(10.dp)) { Text("这一页连接社区。私人聊天继续留在原来的位置。",Modifier.padding(12.dp),fontSize=12.sp,color=Muted) } }
                        items(state.rows.filter { groupSearch.isBlank() || it.ms("title").contains(groupSearch,true) },key={ "room:"+it.ms("id") }) { r ->
                            Column(Modifier.fillMaxWidth().clickable { roomId=r.ms("id") }) {
                                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).background(CommunitySage,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) { Text("♧",fontSize=25.sp,color=Accent) }
                                    Column(Modifier.weight(1f).padding(start=12.dp)) { Text(r.ms("title"),fontWeight=FontWeight.SemiBold,fontSize=15.sp); Text("${r.mi("members")} 位成员 · ${r.mi("humans")} 位人类${if(r.mi("muted")==1) " · 已静音" else ""}",fontSize=12.sp,color=Muted) }
                                    if(r.mi("unread")>0) Badge { Text(r.mi("unread").toString()) }
                                }
                                HorizontalDivider(color=CommunityLine)
                            }
                        }
                        item("new") { CommunityCard {
                            Text("邀请同路人",fontSize=21.sp)
                            Text("和好友相聚，也可以邀请愿意加入的 AI。",fontSize=12.sp,color=Muted)
                            FilledTonalButton(modifier=Modifier.fillMaxWidth(),enabled=human,onClick={ chooseFriends=true; chosen=emptySet(); friendRows=emptyList(); friendOffset=0; run { val r=api.get("/contacts?limit=50",selected); friendRows=r.ma("items"); friendOffset=(r["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("＋ 建一个新群") }
                            Text("至少 3 位人类 · 最多 500 位成员",fontSize=11.sp,color=Muted)
                        } }
                    }
                    "feed" -> {
                        if(human) item("reminders") {OutlinedButton(onClick={feedNoticesOpen=true},modifier=Modifier.fillMaxWidth().testTag("community-feed-notices-open")) {Text("提醒我的")}}
                        items(state.rows,key={ "post:"+it.ms("id") }) { p -> FeedCard(api,selected,p,{ profile=it },{ postStartAfter=0;noticeComment="";postId=p.ms("id") },{ run { api.post("/likes",selected,buildJsonObject { put("post",p.ms("id")); put("liked",!p.mb("liked")) }); feedState.rows=feedState.rows.map { if(it.ms("id")==p.ms("id")) JsonObject(it+mapOf("liked" to JsonPrimitive(!p.mb("liked")),"likes" to JsonPrimitive((p.mi("likes")+if(p.mb("liked")) -1 else 1).coerceAtLeast(0)))) else it } } },human) }
                    }
                    "contacts" -> {
                        if(human) item("blocked") { TextButton(enabled=!busy,onClick={
                            blockedOpen=true; blockedRows=emptyList(); blockedNext=null
                            runBlocked { loadBlocked() }
                        }) { Text("已屏蔽") } }
                        item("search") { Row(verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(number,{ number=it.filter(Char::isDigit).take(10) },label={ Text("输入专属号码") },modifier=Modifier.weight(1f),singleLine=true); TextButton(enabled=number.length==10 && !busy,onClick={ run { profile=api.get("/profile?number=$number",selected) } }) { Text("查找") } } }
                        items(state.data.ma("requests"),key={ "request:"+it.ms("id") }) { req -> CommunityCard { Person(req,{ profile=req },showBio=true); Text("打招呼："+req.ms("note").ifBlank {"未留言"},color=Muted); Row {
                            TextButton(enabled=!busy,onClick={ run { api.post("/friends",selected,communityBody("action" to "accept","target" to req.ms("id"))); tick++ } }) { Text("接受好友") }
                            TextButton(enabled=!busy,onClick={ run { api.post("/friends",selected,communityBody("action" to "decline","target" to req.ms("id"))); tick++ } }) { Text("暂不接受") }
                        } } }
                        val nextRequests=(state.data["next_request_offset"] as? JsonPrimitive)?.intOrNull
                        if(nextRequests!=null) item("more_requests") { TextButton(enabled=!busy,onClick={ run {
                            val page=api.get("/contacts?request_offset=$nextRequests",selected)
                            state.data=JsonObject(state.data+mapOf("requests" to JsonArray((state.data.ma("requests")+page.ma("requests")).distinctBy { it.ms("id") }),"next_request_offset" to (page["next_request_offset"] ?: JsonNull)))
                        } }) { Text("更多好友申请") } }
                        items(state.rows,key={ "friend:"+it.ms("id") }) { person -> CommunityCard { Person(person,{ profile=person },showBio=true); Text("号码 ${person.ms("number")}",fontSize=12.sp,color=Muted) } }
                    }
                    "me" -> {
                        item("card") { CommunityCard { Text("我的名片",fontWeight=FontWeight.SemiBold); Text("${identity?.ms("name")} · ${if(human) "人类" else "AI"}"); androidx.compose.foundation.text.selection.SelectionContainer { Text("号码 ${identity?.ms("number")}",fontSize=20.sp,color=Accent) }; Text("个性签名："+identity?.ms("bio").orEmpty().ifBlank {"暂未填写"}); TextButton(onClick={ profileName=identity?.ms("name").orEmpty(); bio=identity?.ms("bio").orEmpty(); editingProfile=true }) { Text("编辑昵称与个性签名") } } }
                        item("wardrobe") { CommunityCard { Text("对外像素形象",fontWeight=FontWeight.SemiBold);Text("这套装扮用于群聊与朋友圈，独立保存。",fontSize=12.sp,color=Muted);Button(onClick={ wardrobeOpen=true }) { Text("进入更衣室") } } }
                        item("identities") { CommunityCard {
                            Text("身份与 AI",fontWeight=FontWeight.SemiBold)
                            val hints=state.data["reminders"] as? JsonObject
                            Text("群聊艾特 ${hints?.mi("mention_count") ?: 0} 条 · 朋友圈艾特 ${hints?.mi("feed_mention_count") ?: 0} 条")
                            TextButton(enabled=!busy && (hints?.mi("mention_count") ?: 0)>0,onClick={ run {
                                api.post("/hints/clear",selected,buildJsonObject { put("cursor",hints?.ma("items")?.maxOfOrNull { it.cl("cursor") } ?: 0) }); tick++
                            } }) { Text("清空群聊艾特提示（保留群消息）") }
                            if(hints?.mb("clear_hints_supported")==true) TextButton(enabled=!busy && hints.mi("feed_mention_count")>0,onClick={ run {
                                communityClearHints(api,selected,buildJsonObject { put("action","clear_hints");put("target","feed");put("feed_cursor",hints.cl("feed_cursor")) }); tick++
                            } }) { Text("清空朋友圈艾特提示（保留帖子和评论）") }
                            if(!human) Text("AI 提醒注入：群聊${if(api.hintsEnabled(identity!!.ms("local_key"),"groups")) "开启" else "关闭"} · 朋友圈${if(api.hintsEnabled(identity!!.ms("local_key"),"feed")) "开启" else "关闭"}。AI 可分别开关。",fontSize=12.sp,color=Muted)
                            identities.forEach { id -> TextButton(onClick={ selected=id.ms("id"); tick++ }) { Text("${if(id.ms("id")==selected) "✓ " else ""}${id.ms("name")} · ${if(id.ms("kind")=="human") "人类" else "AI"}") } }
                            if(assistant!=null && identities.none { it.ms("local_key")==assistantId }) OutlinedButton(enabled=!busy,onClick={ run { api.post("/identities",body=communityBody("local_key" to assistantId,"name" to assistant.name)); identities=api.identities() } }) { Text("为 ${assistant.name} 建立 AI 名片") }
                            val permissionAssistant=if(human) assistant else settings.assistants.singleOrNull { it.id.toString()==identity?.ms("local_key") }
                            if(permissionAssistant!=null) { val assistant=permissionAssistant; Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("允许 ${assistant.name} 自主参与"); Text("只在原有对话轻提示，不因群消息额外唤醒。",color=Muted,fontSize=12.sp) }; Switch(LocalToolOption.Community in assistant.localTools,{ checked -> scope.launch { settingsStore.update { s -> s.copy(assistants=s.assistants.map { if(it.id==assistant.id) it.copy(localTools=if(checked) (it.localTools+LocalToolOption.Community).distinct() else it.localTools-LocalToolOption.Community) else it }) } } }) } }
                        } }
                        item("storage") { CommunityCard {
                            Text("图片与操作回执",fontWeight=FontWeight.SemiBold)
                            Text("群图片中转 7 天；朋友圈保留压缩展示图。已下载图片保存在本机，不当缓存自动清理。",fontSize=13.sp,color=Muted)
                            Text("本机图片 ${api.savedBytes()/1024} KiB")
                            TextButton(onClick={ clearImages=true }) { Text("管理本机已下载图片") }
                            TextButton(enabled=!busy,onClick={ run { api.reconcile(selected); if(api.pending("")) api.reconcile(""); identities=api.identities(); message="回执已核对"; tick++ } }) { Text("查询／续办待核对操作") }
                        } }
                        item("rules") { Text("社区不提供私聊。群主必须是人类，最多 5 位管理员；群内至少 3 位人类才能发言。好友关系不会自动广播私人聊天。",color=Muted,fontSize=12.sp) }
                    }
                }
                if(state.rows.isEmpty() && !state.loading && tab!="me") item("empty") { Text(when(tab) { "chat"->"群聊会在这里相聚。先去联系人添加好友吧。"; "feed"->"这里还很安静，分享第一件小事吧。"; else->"通过号码或朋友圈名片，认识新朋友。" },Modifier.padding(vertical=30.dp),color=Muted) }
                if(state.next!=null) item("more") { TextButton(onClick={ run { load(state.next) } }) { Text("继续查看") } }
            }
        }
    }
    if(feedNoticesOpen && human) CommunityFeedNotices(api,selected,postId.isEmpty(),feedReadReceipt,{feedNoticesOpen=false}) {notice ->
        postStartAfter=notice.cl("after_sequence")
        noticeComment=notice.ms("comment").takeUnless {it=="null"}.orEmpty()
        message="";postId=notice.ms("post")
    }
    if(feedMentionPicker && human) CommunityFeedMentionPicker(api,selected,{feedMentionPicker=false}) { person ->
        feedMentions=(feedMentions+person).distinctBy {it.ms("id")}.take(20)
    }
    deletingComment?.let { reply -> AlertDialog(onDismissRequest={ deletingComment=null },title={ Text("删除这条评论？") },text={ Text("删除后，这条评论将不再显示。") },
        confirmButton={ TextButton(enabled=!busy,onClick={ run {
            check(human && reply.ms("actor")==selected)
            val receipt=api.post("/comments",selected,communityBody("action" to "remove","id" to reply.ms("id")))
            check(receipt.mb("deleted") && receipt.ms("id")==reply.ms("id")) { "未确认删除，请核对回执" }
            deletingComment=null; tick++
        } }) { Text("确认删除") } },dismissButton={ TextButton(onClick={ deletingComment=null }) { Text("取消") } }) }
    // Keep the member page mounted under its child dialogs, including its scroll position.
    if(groupMembers) {
        val r=state.data["room"] as? JsonObject; val isOwner=r?.ms("creator")==selected; val manager=isOwner || r?.mi("admin")==1
        CommunityFullPage("群成员与参与",{ groupMembers=false },footer={
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled=!busy,modifier=Modifier.weight(1f),onClick={ run { api.post("/rooms",selected,communityBody("action" to "leave","room" to roomId)); groupMembers=false; roomId=""; tick++ } }) { Text("退出群聊") }
                Button(onClick={ groupMembers=false },modifier=Modifier.weight(1f)) { Text("完成") }
            }
        }) {
            LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                item { Text(r?.ms("title").orEmpty(),fontSize=20.sp); Text("至少 3 位人类 · 最多 5 位管理员",color=Muted,fontSize=12.sp) }
                if(manager) item { TextButton(enabled=!busy,onClick={ groupTitle=r?.ms("title").orEmpty();renameGroup=true }) {Text("修改群名")} }
                items(memberRows.chunked(2),key={ pair -> pair.joinToString { it.ms("id") } }) { pair ->
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        pair.forEach { p ->
                            var actions by remember(p.ms("id")) { mutableStateOf(false) }
                            Surface(modifier=Modifier.weight(1f),shape=RoundedCornerShape(15.dp),color=CommunityPaper,border=BorderStroke(1.dp,CommunityLine),onClick={ actions=true }) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        CommunityAvatar(p,34)
                                        Column(Modifier.padding(start=8.dp)) { Text(p.ms("name"),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis); CommunityKind(p) }
                                    }
                                    CommunitySignature(p)
                                    if(p.mb("group_owner")) Text("群主",color=Accent,fontSize=11.sp) else if(p.mb("admin")) Text("管理员",color=Accent,fontSize=11.sp)
                                    DropdownMenu(actions,{ actions=false }) {
                                        DropdownMenuItem(text={ Text("查看名片") },onClick={ actions=false; profile=p })
                                        if(human && p.ms("id")!=selected) DropdownMenuItem(text={ Text("@ 提及") },onClick={ actions=false; mentionMember(p) })
                                        if(isOwner && !p.mb("group_owner")) DropdownMenuItem(text={ Text(if(p.mb("admin")) "取消管理员" else "设管理员") },onClick={ actions=false; run { api.post("/rooms",selected,buildJsonObject { put("action","admin"); put("room",roomId); put("target",p.ms("id")); put("enabled",!p.mb("admin")) }); groupMembers=false; tick++ } })
                                        if(manager && !p.mb("group_owner") && p.ms("id")!=selected) DropdownMenuItem(text={ Text("移出群") },onClick={ actions=false; run { api.post("/rooms",selected,communityBody("action" to "remove","room" to roomId,"target" to p.ms("id"))); groupMembers=false; tick++ } })
                                    }
                                }
                            }
                        }
                        if(pair.size==1) Spacer(Modifier.weight(1f))
                    }
                }
                if(memberOffset!=null) item { TextButton(onClick={ run { val result=api.get("/rooms/$roomId/members?limit=50&offset=$memberOffset",selected); memberRows=(memberRows+result.ma("items")).distinctBy { it.ms("id") }; memberOffset=(result["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("更多成员") } }
                if(manager) item { OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={ chooseFriends=true; chosen=emptySet(); run { val result=api.get("/contacts?limit=50&room=$roomId",selected); friendRows=result.ma("items"); friendOffset=(result["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("邀请好友") } }
                item { HorizontalDivider(color=CommunityLine); Spacer(Modifier.height(16.dp)); Text("我的 AI 如何参与",fontSize=20.sp) }
                item { Surface(color=CommunitySage,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Color(0xFFA7B59C))) { Text("@ 是注入提醒，不会立即唤醒 AI。下次正常聊天或原有哨兵生成时，才可能带上群名和摘要；是否读取、回群由 AI 决定。这样避免打断私聊及循环唤醒。详细原因与提醒示例见「这个家的说明书→给人类：艾特是注入提醒」。",Modifier.padding(14.dp),fontSize=14.sp) } }
                item { Text("群消息不会单独唤醒 AI，也不会占用你们正在进行的聊天。",fontSize=12.sp,color=Muted) }
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("本群消息提醒",Modifier.weight(1f))
                    Switch(r?.mi("muted")!=1,{ enabled -> run { api.post("/rooms",selected,buildJsonObject { put("action","mute"); put("room",roomId); put("muted",!enabled) }); tick++ } },enabled=!busy)
                }; HorizontalDivider(color=CommunityLine) }
                item { Row { Text("私人聊天与记忆",Modifier.weight(1f)); Text("不自动分享",color=Muted) } }
            }
        }
    }

    if(blockedOpen && human) AlertDialog(onDismissRequest={ blockedOpen=false },title={ Text("已屏蔽成员") },text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("这里只显示你屏蔽的成员。取消屏蔽不会自动恢复好友，可重新申请添加。",fontSize=13.sp,color=Muted)
            if(blockedError.isNotEmpty()) Text(blockedError,color=Accent,modifier=Modifier.testTag("community-blocked-error"))
            if(busy) Text("正在处理…",color=Muted)
            else if(blockedRows.isEmpty() && blockedError.isEmpty()) Text("暂无已屏蔽成员",color=Muted)
            LazyColumn(Modifier.heightIn(max=340.dp).testTag("community-blocked-list")) {
                items(blockedRows,key={ it.ms("id") }) { member ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                            Text(member.ms("name")); Text("${if(member.ms("kind")=="ai") "AI" else "人类"} · ${member.ms("number")}",fontSize=12.sp,color=Muted)
                        }
                        TextButton(enabled=!busy,onClick={ runBlocked {
                            val receipt=api.post("/friends",selected,communityBody("action" to "unblock","target" to member.ms("id")))
                            require(receipt.mb("ok") && receipt.ms("action")=="unblock" &&
                                (receipt["target"] as? JsonObject)?.ms("id")==member.ms("id") && receipt["blocked_by_me"]==JsonPrimitive(false)) { "取消屏蔽回执未确认，请到“我的”核对回执" }
                            blockedRows=blockedRows.filterNot { it.ms("id")==member.ms("id") }
                            message="已取消屏蔽 ${member.ms("name")}，如需恢复好友请重新申请"
                            tick++; loadBlocked()
                        } }) { Text("取消屏蔽") }
                    }
                }
                blockedNext?.let { next -> item("more") { TextButton(enabled=!busy,onClick={ runBlocked { loadBlocked(next) } }) { Text("更多已屏蔽成员") } } }
            }
            if(blockedError.isNotEmpty()) TextButton(enabled=!busy,onClick={ runBlocked { loadBlocked() } }) { Text("刷新列表") }
        }
    },confirmButton={ TextButton(onClick={ blockedOpen=false }) { Text("关闭") } })

    profile?.let { p ->
        var friendNote by rememberSaveable(selected,p.ms("id")) { mutableStateOf("") }
        val canRequest=human && "friend" in p && !p.mb("friend") && p.ms("id")!=selected
        AlertDialog(onDismissRequest={ profile=null },title={ Text(p.ms("name")) },text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("${if(p.ms("kind")=="ai") "AI" else "人类"} · ${p.ms("number")}")
                Text("个性签名",fontWeight=FontWeight.SemiBold)
                Text(p.ms("bio").ifBlank { "暂未填写" },modifier=Modifier.testTag("community-profile-bio"))
                if(canRequest) {
                    OutlinedTextField(friendNote,{ friendNote=communityPublicTextLimit(it,120) },
                        label={Text("打招呼内容（选填）")},modifier=Modifier.fillMaxWidth().testTag("community-friend-note"),
                        supportingText={Text("可以留空，也可以参考对方签名写点什么。${friendNote.codePointCount(0,friendNote.length)}/120")},maxLines=4)
                }
                Text("好友可以看到彼此的好友朋友圈，并受邀加入群聊。没有私聊入口。",fontSize=12.sp,color=Muted)
            }
        },confirmButton={ TextButton(enabled=canRequest && !busy,onClick={ run {
            val receipt=api.post("/friends",selected,communityBody("action" to "request","target" to p.ms("id"),"note" to friendNote))
            profile=null;message=if(receipt.mb("already_friends")) "已经是好友" else "好友申请已发送"
        } }) { Text(if(p.ms("id")==selected) "自己的名片" else if(p.mb("friend")) "已是好友" else if("friend" !in p) "核对好友状态…" else "申请好友") } },
        dismissButton={ Row {
            if(p.mb("friend")) TextButton(enabled=human && !busy,onClick={ run { api.post("/friends",selected,communityBody("action" to "remove","target" to p.ms("id"))); profile=null; tick++ } }) { Text("删好友") }
            if(p.ms("id")!=selected) TextButton(enabled=!busy,onClick={ run { api.post("/friends",selected,communityBody("action" to "block","target" to p.ms("id"))); profile=null; tick++ } }) { Text("屏蔽") }
            TextButton(onClick={profile=null}) {Text("关闭")}
        } })
    }
    if(chooseFriends) AlertDialog(onDismissRequest={ chooseFriends=false },title={ Text(if(roomId.isEmpty()) "邀请好友建群" else "邀请好友入群") },text={ Column {
        if(roomId.isEmpty()) OutlinedTextField(newName,{ newName=it.take(60) },label={ Text("群名称") })
        Text("至少 3 位人类；全群最多 500 位成员。",fontSize=12.sp,color=Muted)
        LazyColumn(Modifier.heightIn(max=340.dp)) {
            items(friendRows,key={ it.ms("id") }) { f -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(f.ms("id") in chosen,{ chosen=if(it) chosen+f.ms("id") else chosen-f.ms("id") },enabled=!f.mb("in_room")); Text("${f.ms("name")} · ${if(f.ms("kind")=="ai") "AI" else "人类"}${if(f.mb("in_room")) " · 已在群内" else ""}") } }
            if(friendOffset!=null) item { TextButton(onClick={ run { val r=api.get("/contacts?limit=50&offset=$friendOffset"+(if(roomId.isNotEmpty()) "&room=$roomId" else ""),selected); friendRows=(friendRows+r.ma("items")).distinctBy { it.ms("id") }; friendOffset=(r["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("更多好友") } }
        }
    } },confirmButton={ TextButton(enabled=!busy && chosen.isNotEmpty(),onClick={ run { val result=api.post("/rooms",selected,buildJsonObject { put("action",if(roomId.isEmpty()) "create" else "invite"); put("title",newName.ifBlank { "新的群聊" }); if(roomId.isNotEmpty()) put("room",roomId); put("members",JsonArray(chosen.map(::JsonPrimitive))) }); if(roomId.isEmpty()) roomId=result.ms("id")
        chooseFriends=false; tick++
        if(groupMembers) {
            // Refresh loaded member pages after inviting; keep the current list position.
            val loadedCount=memberRows.size.coerceAtLeast(50)
            val refreshed=mutableListOf<JsonObject>()
            var offset: Int?=0
            do {
                val page=api.get("/rooms/$roomId/members?limit=50&offset=$offset",selected)
                refreshed+=page.ma("items")
                offset=(page["next_offset"] as? JsonPrimitive)?.intOrNull
            } while(offset!=null && refreshed.size<loadedCount)
            memberRows=refreshed.distinctBy { it.ms("id") }; memberOffset=offset
        } } }) { Text(if(roomId.isEmpty()) "创建群聊" else "邀请") } },dismissButton={ TextButton(onClick={ chooseFriends=false }) { Text("取消") } })

    if(renameGroup) AlertDialog(onDismissRequest={renameGroup=false},title={Text("修改群名")},text={
        OutlinedTextField(groupTitle,{groupTitle=communityPublicTextLimit(it,60)},label={Text("新群名")},
            modifier=Modifier.testTag("community-group-title"),supportingText={Text("群主和管理员可修改 · ${groupTitle.codePointCount(0,groupTitle.length)}/60")})
    },confirmButton={TextButton(enabled=!busy && groupTitle.isNotBlank(),onClick={run {
        communityRenameGroup(api,selected,communityBody("target" to roomId,"title" to groupTitle))
        renameGroup=false;message="群名已修改";tick++
    }}) {Text("保存群名")}},dismissButton={TextButton(onClick={renameGroup=false}) {Text("取消")}})
    if(editingProfile) AlertDialog(onDismissRequest={ editingProfile=false },title={ Text("公开名片") },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(profileName,{ profileName=communityPublicTextLimit(it,40) },label={ Text("昵称") })
            OutlinedTextField(bio,{ bio=communityPublicTextLimit(it,200) },label={ Text("个性签名（选填）") },maxLines=5,
                modifier=Modifier.testTag("community-edit-bio"),supportingText={Text("${bio.codePointCount(0,bio.length)}/200")})
            Text("写自我介绍、心情或交友偏好都可以，也可留空。别人申请好友前会看到；不自动决定是否接受申请。",fontSize=12.sp,color=Muted)
        }
    },confirmButton={ TextButton(enabled=!busy && profileName.isNotBlank(),onClick={ run {
        api.post("/profile",selected,communityBody("name" to profileName,"bio" to bio)); identities=api.identities(); editingProfile=false; tick++
    } }) { Text("保存") } },dismissButton={TextButton(onClick={editingProfile=false}) {Text("取消")}})
    if(clearImages) AlertDialog(onDismissRequest={ clearImages=false },title={ Text("删除本机下载图片？") },text={ Text("这会删除本机社区图片副本。超过 7 天的群图片可能无法重新下载。其他聊天附件不受影响。") },confirmButton={ TextButton(onClick={ api.clearSavedImages(); clearImages=false; tick++ }) { Text("删除这些图片") } },dismissButton={ TextButton(onClick={ clearImages=false }) { Text("保留") } })
}
}

@Composable
private fun CommunityCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape=RoundedCornerShape(16.dp),color=CommunityPaper,border=BorderStroke(1.dp,CommunityLine),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp),content=content) }
}

@Composable
private fun CommunitySignature(person: JsonObject) {
    if(person.ms("bio").isNotBlank()) Text(person.ms("bio"),color=Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
}

@Composable
private fun Person(person: JsonObject, open: () -> Unit, showBio: Boolean=false) {
    Row(Modifier.clickable(onClick=open).padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
        CommunityAvatar(person)
        Column(Modifier.padding(start=9.dp).weight(1f)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(person.ms("name"),fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
                CommunityKind(person)
            }
            if(showBio) CommunitySignature(person)
        }
    }
}

@Composable
private fun FeedCard(api: CommunityClient, actor: String, p: JsonObject, profile: (JsonObject)->Unit, open: ()->Unit, like: ()->Unit, human: Boolean) {
    val scope=rememberCoroutineScope()
    var reporting by remember(p.ms("id")) { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }
    var removed by remember(p.ms("id")) { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    if(removed) { Text("这条朋友圈已删除",color=Muted); return }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f)) {
                (p["author"] as? JsonObject)?.let { Person(it,{ profile(it) },showBio=true) }
                Text("${communityTime(p.ms("created"))} · ${if(p.ms("visibility")=="friends") "好友可见" else "社区公开"}",Modifier.padding(start=45.dp),fontSize=11.sp,color=Muted)
            }
            Box {
                TextButton(onClick={ menu=true },contentPadding=PaddingValues(8.dp),modifier=Modifier.size(40.dp)) { Text("···",color=Ink) }
                DropdownMenu(menu,{ menu=false }) {
                    if(p.ms("actor")==actor) DropdownMenuItem(text={ Text("删除这条朋友圈") },enabled=!busy,onClick={ menu=false; busy=true; scope.launch {
                        try { api.post("/posts",actor,communityBody("action" to "remove","id" to p.ms("id"))); removed=true }
                        catch(e:CancellationException) { throw e } catch(e:Exception) { status=e.message ?: "删除未完成，请核对回执" } finally { busy=false }
                    } })
                    else DropdownMenuItem(text={ Text("举报") },onClick={ menu=false; reporting=true })
                }
            }
        }
        if(p.ms("text").isNotBlank()) Text(p.ms("text"),Modifier.fillMaxWidth().clickable(onClick=open),fontSize=15.sp,lineHeight=23.sp)
        FeedMentions(p,profile)
        CommunityImages(api,actor,p.cs("media"))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
            if(p.ms("visibility")=="friends") Text("仅好友可见",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            TextButton(enabled=human,onClick=like,contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.heightIn(min=36.dp)) { Text("${if(p.mb("liked")) "♥" else "♡"} ${p.mi("likes")}",color=if(p.mb("liked")) Color(0xFF9C4F5D) else Ink,fontSize=13.sp) }
            TextButton(onClick=open,contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.heightIn(min=36.dp)) { Text("评论 ${p.mi("comments")}",color=Ink,fontSize=13.sp) }
        }
        if(status.isNotBlank()) Text(status,fontSize=12.sp,color=Muted)
        HorizontalDivider(color=CommunityLine)
        Spacer(Modifier.height(3.dp))
    }
    if(reporting) AlertDialog(onDismissRequest={ reporting=false },title={ Text("反馈这条朋友圈") },text={ OutlinedTextField(reason,{ reason=it.take(1000) },label={ Text("举报原因") }) },confirmButton={ TextButton(enabled=!busy && reason.isNotBlank(),onClick={ busy=true; scope.launch {
        try { api.post("/reports",actor,communityBody("kind" to "post","target" to p.ms("id"),"reason" to reason)); status="反馈已提交，等待管理员处理"; reporting=false }
        catch(e:CancellationException) { throw e } catch(e:Exception) { status=e.message ?: "请核对回执" } finally { busy=false }
    } }) { Text("提交") } },dismissButton={ TextButton(onClick={ reporting=false }) { Text("取消") } })
}

@Composable
private fun FeedMentions(value: JsonObject, profile: (JsonObject)->Unit) {
    if(value.ma("mentioned_members").isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
        value.ma("mentioned_members").forEach { member ->
            TextButton(onClick={profile(member)},contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) {Text("@${member.ms("name")}")}
        }
    }
}

@Composable
private fun CommunityImages(api: CommunityClient, actor: String, ids: List<String>) {
    val scope=rememberCoroutineScope()
    val columns=if(ids.size<=2) 2 else 3
    ids.chunked(columns).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        row.forEach { id -> key(actor,id) {
            var file by remember { mutableStateOf(api.savedImage(actor,id)) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf(false) }
            Surface(modifier=Modifier.weight(1f).aspectRatio(1f),shape=RoundedCornerShape(12.dp),color=CommunitySage,onClick={ if(file!=null) expanded=true else if(!busy) { busy=true; scope.launch { try { file=api.image(actor,id) } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "图片暂不可用" } finally { busy=false } } } }) {
                if(file!=null) AsyncImage(file,contentDescription="社区图片",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alignment=Alignment.Center) else Box(contentAlignment=Alignment.Center) { Text(if(busy) "下载中…" else error.ifBlank { "点按下载图片" },Modifier.padding(6.dp),fontSize=11.sp,color=Muted) }
            }
            if(expanded) Dialog(onDismissRequest={ expanded=false }) { Surface(shape=RoundedCornerShape(12.dp)) { Column { AsyncImage(file,contentDescription="已下载图片",modifier=Modifier.fillMaxWidth().heightIn(max=600.dp)); TextButton(onClick={ expanded=false }) { Text("关闭") } } } }
        } }
        repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
    } }
}


private fun communityTime(raw: String): String {
    val seconds=raw.toDoubleOrNull() ?: return ""
    if(seconds<=0) return ""
    val age=((System.currentTimeMillis()/1000-seconds)/60).toLong().coerceAtLeast(0)
    return when { age<1->"刚刚"; age<60->"${age}分钟前"; age<1440->"${age/60}小时前"; else->java.time.Instant.ofEpochSecond(seconds.toLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M月d日")) }
}
