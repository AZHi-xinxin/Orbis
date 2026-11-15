package me.rerere.rikkahub.ui.pages.orbis.market

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private val Ink = Color(0xFF344154)
private val Paper = Color(0xFFFFFCF6)
private val Lavender = Color(0xFFEDE7F5)
private fun marketStateLabel(value: String) = when(value) {
    "published" -> "已发布"; "draft" -> "草稿"; "withdrawn" -> "已撤回"; "removed" -> "已下架"
    "open" -> "待处理"; "closed" -> "已关闭"; "resolved" -> "已处理"; else -> value
}

/** Human market UI; no market posting tool is added to an AI catalogue. */
@Composable
internal fun OrbisMarketPage(owner: String, conversation: String?, close: () -> Unit) {
    val context = LocalContext.current
    val client = remember { MarketClient(context) }
    val deliveries = remember { MarketDeliveries(context) }
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val chats = koinInject<ConversationRepository>()
    val service = koinInject<ChatService>()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(if(client.hasSession()) "discover" else "account") }
    var previous by remember { mutableStateOf("discover") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var dataTick by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf("") }
    var mineWishes by rememberSaveable { mutableStateOf(false) }
    val listState = rememberMarketListState(MarketListRequest(page, category, mineWishes, tick))
    var rows by listState::rows
    var cases by listState::cases
    var next by listState::next
    var loading by listState::loading
    var rules by remember { mutableStateOf<JsonObject?>(null) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var edit by remember { mutableStateOf<JsonObject?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var editCategory by rememberSaveable { mutableStateOf("") }
    var anonymous by rememberSaveable { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<JsonObject?>(null) }
    var reportText by rememberSaveable { mutableStateOf("") }
    var targetOwner by rememberSaveable { mutableStateOf(owner) }
    var targetChat by rememberSaveable { mutableStateOf(conversation.orEmpty()) }
    var confirmationId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var targetChats by remember { mutableStateOf(emptyList<Conversation>()) }
    var local by remember { mutableStateOf(emptyList<Pair<MarketDelivery, String>>()) }
    var recovery by remember { mutableStateOf(runCatching { client.recoveryCode() }.getOrDefault("")) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                message = when(failure) {
                    is MarketProblem -> failure.message.orEmpty() + failure.details.ma("matches").joinToString("", prefix = "") {
                        val field = when(it.ms("field")) { "title" -> "标题"; "summary" -> "介绍"; "prompt" -> "设定原文"; "description" -> "愿望描述"; "display" -> "署名"; else -> "内容" }
                        "\n$field 第${it.mi("start") + 1}–${it.mi("end")}字：${it.ms("phrase")}" }
                    is java.io.IOException -> "连接未完成；已提交的操作请在消息页核对回执，不要重新创建。"
                    else -> failure.message ?: "操作未完成，原内容已保留。"
                }
            } finally { busy = false }
        }
    }
    fun openEditor(value: JsonObject? = null) {
        edit = value; title = value?.ms("title").orEmpty(); summary = value?.ms("summary").orEmpty()
        prompt = value?.ms("prompt").orEmpty(); editCategory = marketEditorCategory(value?.ms("category"), category)
        anonymous = value?.mb("anonymous") == true; confirmed = false; page = "editor"
    }
    fun draft(): JsonObject = buildJsonObject {
        edit?.get("id")?.let { put("id", it) }; edit?.get("revision")?.let { put("revision", it) }
        edit?.get("wish_id")?.let { put("wish_id", it) }
        put("title", title); put("summary", summary); put("prompt", prompt); put("category", editCategory); put("anonymous", anonymous)
    }
    fun path(offset: Int) = listState.request.path(offset)
    LaunchedEffect(listState, dataTick) {
        if (listState.request.loadsRows && (!listState.loaded || listState.dataRevision != dataTick)) {
            loading = true
            try {
                // After a write, refresh the already loaded pages without discarding the
                // list or its scroll anchor. Only the explicit refresh/re-entry clears it.
                val previousSize = rows.size
                val updated = mutableListOf<JsonObject>()
                val visited = mutableSetOf<Int>()
                var offset: Int? = 0
                var updatedCases = emptyList<JsonObject>()
                do {
                    val start = offset ?: break
                    check(visited.add(start)) { "重复的市场分页位置" }
                    val data = client.get(path(start))
                    updated += data.ma("items")
                    updatedCases = data.ma("cases")
                    offset = (data["next_offset"] as? JsonPrimitive)?.intOrNull
                } while (updated.size < previousSize && offset != null)
                rows = updated.distinctBy { if (listState.request.page == "messages") it.toString() else it.ms("id") }
                cases = updatedCases; next = offset
                listState.loaded = true; listState.dataRevision = dataTick
            }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { message = if(failure is MarketProblem) failure.message.orEmpty() else "暂时无法读取市场，请稍后再试。" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { runCatching { client.get("/rules") }.onSuccess { rules = it } }
    LaunchedEffect(page, title, summary, prompt, editCategory, anonymous, edit) {
        if(page == "editor") { delay(400); withContext(Dispatchers.IO) { client.saveEditor(draft()) } }
    }
    LaunchedEffect(page, targetOwner) {
        if(page == "send") {
            targetChats = withContext(Dispatchers.IO) { chats.getRecentConversations(Uuid.parse(targetOwner), 50) }
            if(targetChats.none { it.id.toString() == targetChat }) targetChat = ""
        }
    }
    LaunchedEffect(page) {
        if(page == "deliveries") while(true) {
            local = withContext(Dispatchers.IO) { deliveries.list().map { value ->
                val event = service.orbisEvents.inbox.receipt(value.source, value.id)
                value to when {
                    value.savedRevision != null -> "作品已保存 · 第${value.savedRevision}版"
                    event == null -> "投递结果待核对；不会自动再发"
                    event.state in setOf("accepted", "queued") -> "已排队，等待当前聊天空闲"
                    event.state == "generating" -> "AI 已开始 · 尚无作品保存回执"
                    event.state == "pending_tool" -> "等待工具确认 · 尚无保存回执"
                    event.state == "replied" -> "本轮已结束，尚无作品保存回执"
                    event.state == "suppressed" -> "未发送 · 自动事件权限已关闭或目标已变更"
                    event.state == "target_invalid" -> "未发送 · 原助手或聊天已不可用"
                    event.state == "unknown" -> "投递结果未确认，请先查看原聊天；不会自动再发"
                    else -> "本次尚未完成，请查看原聊天的提示"
                }
            } }; delay(2500)
        }
    }
    fun back() {
        if(page in setOf("discover", "account")) close()
        else { page = when(page) { "detail" -> previous; "send" -> "detail"; else -> "discover" }; message = "" }
    }
    val protectText = page in setOf("detail", "send", "editor") || recovery.isNotBlank()
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        securePolicy = if(protectText) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)) {
        BackHandler { back() }
        MarketLetterTheme {
            val columns = if(LocalDensity.current.fontScale > 1.25f || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360) 1 else 2
            Column(Modifier.fillMaxSize().background(LetterPaper).letterGrain().safeDrawingPadding().imePadding()) {
                LetterHeading(page, ::back) { page = "messages" }
                if(busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(message.isNotBlank()) Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if(listState.request.loadsRows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy && !loading, onClick = { tick++ }) { Text("刷新") }
                }
                LazyColumn(Modifier.weight(1f), state = listState.scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when(page) {
                        "account" -> item { MarketAccount(client, busy, recovery, onRecoverySeen = { run { withContext(Dispatchers.IO) { client.acknowledgeRecovery() }; recovery = "" } }, onRun = ::run,
                            loggedIn = { value -> tick++; recovery = value.ms("recovery_code"); if(recovery.isBlank()) page = "discover" },
                            logout = { tick++; page = "account"; rows = emptyList(); message = "已退出" })
                            TextButton(onClick = { page = "deliveries" }) { Text("纸飞机 · 查看创作进度") }
                            TextButton(onClick = { page = "rules" }) { Text("发布约定与内容申诉") }
                        }
                        "rules" -> item { LetterCard { Text("一起守护这张信纸", fontWeight = FontWeight.Bold); Text(rules?.ms("text") ?: "约定尚未加载，请稍后重试。")
                            Text(rules?.ms("privacy").orEmpty()); Text("当前为邀请试用。注册时请妥善保存恢复码；丢失密码和恢复码后无法找回账号。")
                            TextButton(onClick = { report = marketObject("kind" to "appeal", "object" to "general") }) { Text("误判申诉 / 联系审核") } } }
                        "discover", "favorites", "mine" -> {
                            item { LetterDivider(if(page == "discover") "由人类写下设定，让故事在你们之间发生" else if(page == "favorites") "喜欢这份设定，就让它成为你们的故事" else "每一个灵感，都值得被好好珍藏") }
                            if(page != "favorites") item { LetterComposeActions(!busy,
                                wish = { title = ""; prompt = ""; editCategory = ""; page = "wishEditor" }, create = { openEditor() }) }
                            item { LetterFilters(category) { category = it } }
                            if(page == "mine") item { TextButton(enabled = !busy, onClick = { run { val value = withContext(Dispatchers.IO) { client.readEditor() }; if(value != null) openEditor(value) else message = "暂无本机未完成草稿" } }) { Text("继续本机未完成的草稿") } }
                            if(rows.isEmpty() && !busy && !loading) item { EmptyLetter("这里还没有信件", "写下第一份原创设定，或去愿望墙看看。") }
                            items(rows, key = { it.ms("id") }) { row -> LetterFeedCard(row, !busy,
                                open = { run { previous = page; detail = client.get("/items/${row.ms("id")}"); page = "detail" } },
                                edit = if(page == "mine" && row.mb("mine")) ({ run { openEditor(client.get("/items/${row.ms("id")}")) } }) else null) }
                        }
                        "detail" -> detail?.let { entry ->
                            item { LetterCover(entry, Modifier.fillMaxWidth().height(188.dp)) }
                            item { LetterCard {
                            Text(entry.ms("title"), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Text("${entry.ms("author")} · 第${entry.mi("version")}版 · ${if(entry.ms("category") == "extra") "番外" else "格子"}", fontSize = 12.sp)
                            Text(entry.ms("summary")); HorizontalDivider(); Text(entry.ms("prompt"), lineHeight = 26.sp)
                            Text("供你自己的 AI 创作私人作品。未经作者同意，请勿转载设定原文。", fontSize = 12.sp)
                            if(entry.mb("mine")) TextButton(enabled = !busy, onClick = { run { openEditor(client.get("/items/${entry.ms("id")}")) } }) { Text("编辑草稿") }
                            TextButton(onClick = { report = marketObject("kind" to "item", "object" to entry.ms("id")) }) { Text("举报内容") }
                            TextButton(onClick = { page = previous }) { Text("收起信纸") }
                        } } }
                        "editor", "wishEditor" -> item { LetterCard {
                            Text(if(page == "wishEditor") "把愿望挂在这里" else "写给同路人的一份设定", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            MarketField(title, { title = it }, "标题（60字内）")
                            Text("作品类型（必选）", fontWeight = FontWeight.SemiBold)
                            Row { listOf("extra" to "番外", "grid" to "格子").forEach { (id, name) -> FilterChip(editCategory == id, { editCategory = id; confirmed = false }, label = { Text(name) }) } }
                            Text(when(editCategory) {
                                "extra" -> "番外：纯文字故事，生成后保存在秘密基地。"
                                "grid" -> "格子：HTML页面、问卷或文字作品，生成后保存在格子。"
                                else -> "请先选择番外或格子，系统不会替你决定保存位置。"
                            }, fontSize = 13.sp)
                            if(page == "editor") MarketField(summary, { summary = it }, "一句介绍（160字内）")
                            MarketField(prompt, { prompt = it }, if(page == "editor") "设定原文（2000字内）" else "愿望描述（600字内）", 7)
                            Text("${prompt.codePointCount(0, prompt.length)}字 · 不上传图片、附件或生成作品正文", fontSize = 12.sp)
                            if(page == "editor") {
                                Row { Checkbox(anonymous, { anonymous = it }); Text("公开时不展示署名", Modifier.padding(top = 12.dp)) }
                                Row { Checkbox(confirmed, { confirmed = it }); Text("这是我有权分享的原创设定，我已阅读市场约定。", Modifier.padding(top = 12.dp)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("save" to "存草稿", "publish" to "发布版本").forEach { (action, label) ->
                                        Button(enabled = !busy && editCategory in setOf("extra", "grid") && (action == "save" || confirmed && rules != null), onClick = { run {
                                            val body = JsonObject(draft().toMutableMap().apply {
                                                remove("revision"); edit?.get("revision")?.let { put("expected_revision", it) }
                                                put("action", JsonPrimitive(action)); put("original_confirmed", JsonPrimitive(confirmed)); put("rules_version", JsonPrimitive(rules?.ms("version").orEmpty()))
                                            }); val saved = client.post("/items", body); dataTick++; openEditor(saved); message = if(action == "publish") "第${saved.mi("version")}版已发布" else "云端草稿已保存"
                                        } }) { Text(label) }
                                    }
                                }
                                if(edit?.ms("state") == "published") TextButton(enabled = !busy, onClick = { run {
                                    client.post("/items", buildJsonObject { put("action", "withdraw"); put("id", edit!!.ms("id")); put("expected_revision", edit!!.mi("revision")) }); page = "mine"; dataTick++
                                } }) { Text("撤回公开版本（保留已有私人副本）") }
                            } else Button(enabled = !busy && editCategory in setOf("extra", "grid"), onClick = { run { client.post("/wishes", marketObject("title" to title, "description" to prompt, "category" to editCategory)); page = "wishes"; dataTick++ } }) { Text("发布愿望 · 最多3人领取") }
                        } }
                        "wishes" -> {
                            item { LetterDivider("把想看的故事，留给愿意书写的人") }
                            item { Row { TextButton(onClick = { mineWishes = !mineWishes }) { Text(if(mineWishes) "看所有愿望" else "我的愿望") } } }
                            if(rows.isEmpty() && !busy && !loading) item { EmptyLetter("愿望墙还是空的", "描述你想要的设定，等待同路人动笔。") }
                            items(rows.chunked(columns), key = { it.first().ms("id") }) { group -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                group.forEach { row -> LetterWishNote(row, rows.indexOf(row), Modifier.weight(1f)) {
                                val claim = row["my_claim"] as? JsonObject
                                    Button(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), enabled = !busy && (claim != null || row.mi("slots_used") < 3 && row.ms("state") == "open"), onClick = { run {
                                        val response = client.post("/claims", marketObject("wish_id" to row.ms("id"))); dataTick++; openEditor(client.get("/items/${response.ms("item_id")}"))
                                    } }) { Text(if(claim != null) "继续创作" else "领取创作", fontSize = 13.sp) }
                                    if(claim?.ms("state") == "creating") TextButton(enabled = !busy, onClick = { run { client.post("/claims", marketObject("wish_id" to row.ms("id"), "action" to "cancel")); dataTick++ } }) { Text("归还名额") }
                                (row["submitted"] as? JsonArray).orEmpty().forEach { id -> TextButton(enabled = !busy, onClick = { run { previous = "wishes"; detail = client.get("/items/${id.jsonPrimitive.content}"); page = "detail" } }) { Text("查看一份已提交的设定") } }
                                    if(row.mb("mine") && row.ms("state") == "open") TextButton(enabled = !busy, onClick = { run { client.post("/wishes/close", marketObject("wish_id" to row.ms("id"))); dataTick++ } }) { Text("关闭愿望") }
                                    TextButton(onClick = { report = marketObject("kind" to "wish", "object" to row.ms("id")) }, contentPadding = PaddingValues(0.dp)) { Text("举报", fontSize = 11.sp) }
                                } }
                                if(group.size < columns) Spacer(Modifier.weight(1f))
                            } }
                            item { Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { title = ""; prompt = ""; editCategory = ""; page = "wishEditor" }) { Text("＋ 写个愿望") }
                                LetterDivider("每个愿望最多由 3 位创作者领取") }
                        }
                        "messages" -> {
                            item { LetterCard { Text("消息合并在这里，不打断聊天。", fontWeight = FontWeight.Bold)
                                TextButton(enabled = !busy, onClick = { run { client.post("/notifications/read", buildJsonObject {}); dataTick++ } }) { Text("全部标为已读") }
                                TextButton(enabled = !busy, onClick = { run { val result = client.reconcile(); message = if(result != null) "已查到成功回执，没有重复提交。请从我的创作或愿望墙继续；设定草稿也已同步到本机。" else if(client.pendingRequest() != null) "暂未查到回执，可继续查询，或用原请求编号重试。" else "没有待核对操作" } }) { Text("核对未完成操作") }
                                if(client.pendingRequest() != null) TextButton(enabled = !busy, onClick = { run { client.retryPending(); message = "原请求已核对完成"; dataTick++ } }) { Text("用原编号重试（不会重复创建）") }
                                TextButton(onClick = { report = marketObject("kind" to "appeal", "object" to "general") }) { Text("提交申诉") }
                            } }
                            items(rows) { row -> LetterCard { Text(row.ms("title")); Text("${row.mi("count")} 次 · ${if(row.mi("unread") == 1) "未读" else "已读"}") } }
                            items(cases) { row -> LetterCard { Text("反馈 ${row.ms("id").take(8)} · ${marketStateLabel(row.ms("state"))}"); Text(row.ms("decision")) } }
                        }
                        "send" -> detail?.let { entry -> item { LetterCard {
                            LetterDivider("一张信笺，一次新的相遇")
                            Text("发送给你的 AI？", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            LetterCover(entry, Modifier.fillMaxWidth().height(110.dp))
                            Text("${entry.ms("title")} · 固定第${entry.mi("version")}版")
                            MarketChoice("交给哪位 AI", settings.assistants.map { it.id.toString() to it.name }, targetOwner) { targetOwner = it; targetChat = ""; confirmed = false; confirmationId = java.util.UUID.randomUUID().toString() }
                            MarketChoice("在哪个已有聊天中创作（最近50个）", targetChats.map { it.id.toString() to it.title.ifBlank { "未命名聊天" } }, targetChat) { targetChat = it; confirmed = false; confirmationId = java.util.UUID.randomUUID().toString() }
                            val assistant = settings.assistants.firstOrNull { it.id.toString() == targetOwner }
                            val valid = assistant != null && targetChats.any { it.id.toString() == targetChat } &&
                                (entry.ms("category") != "grid" || LocalToolOption.LocalGallery in assistant.localTools)
                            Text("保存到：${if(entry.ms("category") == "extra") "该 AI 的秘密基地" else "该 AI 的格子"}。正在生成时先排队。使用所选聊天的模型，会消耗该模型额度；写入仍遵守原工具确认。", lineHeight = 23.sp)
                            if(!valid) Text("请选择有效聊天；格子创作还需要该 AI 已开启本地格子写入。")
                            Row { Checkbox(confirmed, { confirmed = it }); Text("确认版本、AI、聊天和模型用量", Modifier.padding(top = 12.dp)) }
                            Button(enabled = !busy && confirmed && valid, onClick = { run {
                                check(OrbisSentinels.open(context).rules.refresh().enabled) { "当前自动事件总开关已关闭。请按自己的意愿在后台与权限中开启后再发送。" }
                                val chat = chats.getConversationById(Uuid.parse(targetChat)); check(chat?.assistantId?.toString() == targetOwner) { "聊天归属已改变，请重新选择" }
                                val latest = client.get("/items/${entry.ms("id")}?version=${entry.mi("version")}")
                                check(latest.ms("state") == "published" && latest.ms("prompt") == entry.ms("prompt")) { "此版本已不可用，请重新查看" }
                                val value = withContext(Dispatchers.IO) { deliveries.create(latest, targetOwner, targetChat, confirmationId) }
                                page = "deliveries"
                                service.bindOrbisEvents(setOf(value.source), OrbisEventBinding(value.assistant, value.conversation))
                                service.acceptOrbisEvent(value.input())
                            } }) { Text("确认，交给 AI") }
                            TextButton(onClick = { page = "detail" }) { Text("先不发送") }
                        } } }
                        "deliveries" -> {
                            if(local.isEmpty()) item { EmptyLetter("还没有寄出的设定", "确认后才会交给所选 AI；浏览市场不会自动生成。") }
                            items(local, key = { it.first.id }) { (value, state) -> LetterCard {
                                Text(value.title, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text(state)
                                Text("市场第${value.version}版 · ${settings.assistants.firstOrNull { it.id.toString() == value.assistant }?.name ?: "原助手已不存在"} · ${if(value.category == "extra") "秘密基地" else "格子"}", fontSize = 12.sp)
                                Text("请求 ${value.id.take(8)} · 原设定和版本已保留在本机", fontSize = 11.sp)
                            } }
                        }
                    }
                    if(next != null && page in setOf("discover", "favorites", "mine", "wishes")) item {
                        OutlinedButton(enabled = !busy, onClick = { run { val data = client.get(path(next!!)); rows = rows + data.ma("items").filter { fresh -> rows.none { it.ms("id") == fresh.ms("id") } }; next = (data["next_offset"] as? JsonPrimitive)?.intOrNull } }) { Text("再展开一些") }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                if(page == "detail") detail?.let { entry ->
                    Surface(color = LetterPaper, shadowElevation = 3.dp) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f), enabled = !busy && entry.ms("state") == "published", onClick = { run {
                                client.post("/favorites", buildJsonObject { put("item_id", entry.ms("id")); put("favorite", !entry.mb("favorite")) }); dataTick++; detail = client.get("/items/${entry.ms("id")}")
                            } }) { Text(if(entry.mb("favorite")) "♥ 已收藏" else "♡ 收藏") }
                            Button(modifier = Modifier.weight(1.3f), enabled = !busy && entry.ms("state") == "published", onClick = { run {
                                detail = client.get("/items/${entry.ms("id")}?version=${entry.mi("version")}"); confirmed = false; confirmationId = java.util.UUID.randomUUID().toString(); page = "send"
                            } }) { Text("发送给 AI") }
                        }
                    }
                }
                if(page in setOf("discover", "favorites", "mine", "wishes", "account", "messages", "deliveries", "rules"))
                    LetterNavigation(page, !busy) { page = it; message = "" }
            }
            report?.let { target -> AlertDialog(onDismissRequest = { report = null }, title = { Text(if(target.ms("kind") == "appeal") "提交申诉" else "举报内容") },
                text = { Column { Text("请说明具体问题，不要填写私人聊天或身份信息。"); MarketField(reportText, { reportText = it }, "说明（1000字内）", 4) } },
                confirmButton = { TextButton(enabled = !busy && reportText.isNotBlank(), onClick = { run {
                    val result = client.post("/reports", JsonObject(target + ("text" to JsonPrimitive(reportText)))); message = "已提交，编号 ${result.ms("case_id").take(8)}"; reportText = ""; report = null
                } }) { Text("提交") } }, dismissButton = { TextButton(onClick = { report = null }) { Text("取消") } }) }
        }
    }
}

@Composable private fun LetterCard(content: @Composable ColumnScope.() -> Unit) = LetterSheet(flowers = true, content = content)
@Composable private fun EmptyLetter(title: String, text: String) = LetterCard { Text("✦", fontSize = 28.sp, color = Color(0xFFB89B67)); Text(title, fontSize = 20.sp); Text(text, fontSize = 13.sp) }
@Composable private fun MarketField(value: String, change: (String) -> Unit, label: String, lines: Int = 1) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = if(lines == 1) 2 else 12)
}
@Composable private fun MarketChoice(label: String, options: List<Pair<String, String>>, value: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：${options.firstOrNull { it.first == value }?.second ?: "请选择"}") }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(id); expanded = false }) } }
    }
}
@Composable private fun MarketAccount(client: MarketClient, busy: Boolean, recovery: String, onRecoverySeen: () -> Unit,
    onRun: (suspend () -> Unit) -> Unit, loggedIn: (JsonObject) -> Unit, logout: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("login") }
    var login by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var display by rememberSaveable { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    LetterCard {
        Text("你的市场账号", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if(recovery.isNotEmpty()) {
            Text("请把恢复码抄到安全的地方；它仅在此展示一次。遗失密码和恢复码后无法找回账号。")
            Text(recovery, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                val clip = android.content.ClipData.newPlainText("市场账号恢复码", recovery)
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            }) { Text("复制恢复码") }
            Button(onClick = onRecoverySeen) { Text("已妥善保存") }
        } else if(client.hasSession()) {
            Text("账号已连接。设定、收藏与愿望由市场保存；模型密钥和私人作品留在手机。")
            TextButton(enabled = !busy, onClick = { onRun { client.logout(); logout() } }) { Text("退出当前设备") }
        } else {
            Text("Orbis 通用账号：群聊、朋友圈、市场与圆桌游戏共用。已有账号直接登录，邀请码仅用于首次注册。")
            me.rerere.rikkahub.ui.pages.orbis.OrbisInvitationGuide()
            Row { listOf("login" to "登录", "register" to "注册", "recover" to "找回").forEach { (id, label) -> TextButton(onClick = { mode = id }) { Text(label) } } }
            MarketField(login, { login = it }, "账号（小写字母、数字、下划线）")
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(if(mode == "recover") "新密码（至少10位）" else "密码（至少10位）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if(mode == "register") { MarketField(display, { display = it }, "公开署名"); MarketField(invite, { invite = it }, "邀请码") }
            if(mode == "recover") MarketField(code, { code = it }, "账号恢复码")
            Button(enabled = !busy, onClick = { onRun {
                val result = client.auth(mode, marketObject("login" to login, "password" to password, "display" to display, "invite" to invite, "recovery_code" to code))
                password = ""; invite = ""; code = ""; loggedIn(result)
            } }) { Text(when(mode) { "register" -> "注册并领取恢复码"; "recover" -> "重置密码"; else -> "登录" }) }
        }
    }
}
