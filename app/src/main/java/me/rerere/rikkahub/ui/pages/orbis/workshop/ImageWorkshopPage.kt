package me.rerere.rikkahub.ui.pages.orbis.workshop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.workshop.*
import me.rerere.rikkahub.ui.pages.orbis.orbisWorkshopAssistant
import org.koin.compose.koinInject
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageWorkshopPage(assistantId: String? = null, conversationId: String? = null,
    onBack: () -> Unit, onConfigureProviders: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val opened by produceState<Result<ImageWorkshop>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { ImageWorkshop.open(context) } }
    }
    val workshop = opened?.getOrNull()
    if (settings.init || opened == null || workshop == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("AI 生图工坊") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        }) }) { padding -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (opened?.isFailure == true) {
                Text("工坊记录暂时无法安全读取", style = MaterialTheme.typography.titleLarge)
                Text("已保留原始记录和图片，没有清空或重新制作。请先返回并检查本机存储；不要反复提交同一个任务。")
            } else { CircularProgressIndicator(); Text("正在读取工坊…") }
        } }
        return
    }
    val state by workshop.state.collectAsStateWithLifecycle()
    // Pin this page to its opening owner; another visible chat must not retarget imports/retries.
    val owner = rememberSaveable(assistantId) { assistantId ?: settings.assistantId.toString() }
    val ownerAssistant = orbisWorkshopAssistant(settings, owner, conversationId)
    if (ownerAssistant == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("AI 生图工坊") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        }) }) { padding -> Column(Modifier.padding(padding).padding(24.dp)) {
            Text("原助手或会话配置不可用", style = MaterialTheme.typography.titleLarge)
            Text("没有切换到其他助手，也没有提交制作任务。请返回原对话核对所属助手。")
        } }
        return
    }
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable(owner) { mutableStateOf("") }
    var requestId by rememberSaveable(owner) { mutableStateOf(UUID.randomUUID().toString()) }
    var chosen by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val modelId = chosen ?: state.defaultModel ?: settings.imageGenerationModelId.toString()
    val makers = settings.providers.filter { it.enabled }.flatMap { provider -> provider.models.filter {
        ImageWorkshop.supports(it.providerOverwrite ?: provider, it)
    }.map { it.id.toString() to workshopMakerLabel(provider, it) } }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(makers.isEmpty()) { if (makers.isEmpty()) expanded = false }
    var references by rememberSaveable(owner) { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var kitDialog by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<WorkshopJob?>(null) }
    var exportAssetJson by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var exportAssetsJson by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    fun launchTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = workshopError(error.message) }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val asset = exportAssetJson?.let { runCatching { Json.decodeFromString<WorkshopAsset>(it) }.getOrNull() }
        if (uri != null && asset == null) notice = "导出选择未能恢复，请重新选择原图导出。"
        if (uri != null && asset != null) launchTask {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output ->
                workshop.assetFile(asset.file).inputStream().use { it.copyTo(output) }
            } ?: error("workshop_export") }
            notice = "原图已导出（未重压缩）"
        }
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val assets = exportAssetsJson?.let { runCatching { Json.decodeFromString<List<WorkshopAsset>>(it) }.getOrNull() }
        if (uri != null && assets == null) notice = "导出选择未能恢复，请重新选择打包导出。"
        if (uri != null && assets != null) launchTask {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output ->
                java.util.zip.ZipOutputStream(output).use { zip -> assets.forEachIndexed { index, asset ->
                    zip.putNextEntry(java.util.zip.ZipEntry("${index + 1}-${asset.label}.${asset.file.substringAfterLast('.')}"))
                    workshop.assetFile(asset.file).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                } }
            } ?: error("workshop_export") }
            notice = "全部原图已打包导出（未重压缩）"
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        launchTask {
            val imported = withContext(Dispatchers.IO) { uris.take((4 - references.size).coerceAtLeast(0)).map { workshop.importReference(it).file } }
            references = (references + imported).distinct().take(4)
        }
    }
    fun submit(kit: Boolean) = launchTask {
        val accepted = workshop.submit(owner, conversationId, false, modelId, prompt, kit, references, requestId)
        notice = if (accepted.error == "workshop_schedule_pending") "任务已保存，正在等待后台调度。无需重复提交，下次启动会继续安排。"
            else "已交给后台制作，可以离开本页继续聊天。"
        prompt = ""; references = emptyList(); requestId = UUID.randomUUID().toString()
    }
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text("AI 生图工坊")
        Text("内容归属：${ownerAssistant.name.ifBlank { "未命名助手" }}", style = MaterialTheme.typography.labelSmall)
    } }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("把想象交给工坊", style = MaterialTheme.typography.headlineSmall)
                Text("你和伙伴都能创作。制作在后台进行，原图保存在这里。", style = MaterialTheme.typography.bodyMedium)
                Text("工坊原图暂不包含在常规聊天备份中。重要作品请使用下方“导出原图”或“打包导出全部原图”另存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            notice?.let { message -> item { Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f)); TextButton(onClick = { notice = null }) { Text("知道了") }
            } } } }
            item {
                Card(shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("由谁制作", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { expanded = makers.isNotEmpty() },
                            enabled = makers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (makers.isEmpty()) "尚未配置可用生图模型"
                                else makers.firstOrNull { it.first == modelId }?.second ?: "选择已配置的生图模型")
                        }
                        DropdownMenu(expanded && makers.isNotEmpty(), onDismissRequest = { expanded = false }) { makers.forEach { maker ->
                            DropdownMenuItem(text = { Text(maker.second) }, onClick = {
                                chosen = maker.first; expanded = false
                                launchTask { withContext(Dispatchers.IO) { workshop.ledger.preferences(defaultModel = maker.first) } }
                            })
                        } }
                    }
                    if (makers.isEmpty()) Text("请先点下方“添加 / 管理生图 AI 和密钥”配置图片接口，再回到这里选择制作者。不会自动新增或选择付费接口。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onConfigureProviders) { Text("添加 / 管理生图 AI 和密钥") }
                    Text("支持多个 OpenAI 兼容生图接口，及可输出图片的 Gemini 模型。请在模型配置中设为图片模型；密钥只在提供方配置中管理。", style = MaterialTheme.typography.bodySmall)
                    Text("豆包生图请选 Seedream（例如 doubao-seedream-4-0-20260415）；Seedance 是视频模型，不能在这里生图。请核对完整模型 ID 和版本日期：旧版本即使仍列在模型列表中，也可能已停止新开通或不可调用。方舟连接使用 OpenAI 兼容格式，Base URL 填 https://ark.cn-beijing.volces.com/api/v3，不要把 /images/generations 拼进 Base URL。工坊会自行选用图片路径，不使用聊天 API 路径。先在方舟开通对应图片模型，再在连接的“模型”页添加；已有 Seedream 4/5 会在工坊自动识别。", style = MaterialTheme.typography.bodySmall)
                    Text(WORKSHOP_ACCESS_NOTICE, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it.take(12000) },
                        label = { Text("描述想画的内容，或填写 Q 版人物外貌") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    if (references.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(references) { file -> Column {
                            AsyncImage(model = workshop.assetFile(file), contentDescription = "参考图", modifier = Modifier.size(84.dp), contentScale = ContentScale.Fit)
                            TextButton(onClick = { references = references - file }) { Text("移除") }
                        } }
                    }
                    TextButton(onClick = { pick.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = references.size < 4 && !busy) { Text("添加参考图 / 修改图片（最多 4 张）") }
                    Button(onClick = { submit(false) }, enabled = !busy && prompt.isNotBlank() && makers.any { it.first == modelId }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (references.isEmpty()) "开始生图" else "按描述修改参考图")
                    }
                    OutlinedButton(onClick = { kitDialog = true }, enabled = !busy && prompt.isNotBlank() && makers.any { it.first == modelId }, modifier = Modifier.fillMaxWidth()) {
                        Text("生成小部件所需图片")
                    }
                    Text("Q 版套组：7 个动作 + 7 种表情，独立透明素材。真实透明效果取决于所选模型，成图后会检查并提示；不会把棋盘背景当作透明。", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item {
                OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("允许伙伴调用工坊"); Text("全局开关 · 默认开启 · 不设每日次数上限", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = state.allowAi, onCheckedChange = { enabled -> launchTask {
                            withContext(Dispatchers.IO) { workshop.ledger.preferences(allowAi = enabled) }
                        } }, enabled = !busy)
                    }
                    Text("伙伴提交后即可继续聊天，完成通知和图片只回到发起任务的原对话。全局哨兵暂停时不会唤醒；图片仍保存在这里。关闭开关后未开始的 AI 任务会停止，正在请求的接口可能已计费。", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { Text("制作记录", style = MaterialTheme.typography.titleLarge) }
            val ownedJobs = state.jobs.filter { it.assistantId == owner }.asReversed()
            items(if (showAll) ownedJobs else ownedJobs.take(20), key = { it.id }) { job ->
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (job.byAi) "伙伴创作" else "我的创作"} · ${workshopStatus(job.status)}", style = MaterialTheme.typography.titleMedium)
                    Text(job.maker, style = MaterialTheme.typography.bodySmall)
                    Text(job.prompt.take(180), maxLines = 4)
                    job.error?.let { Text(workshopError(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (job.assets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(job.assets) { asset -> Column(Modifier.width(144.dp)) {
                            AsyncImage(model = workshop.assetFile(asset.file), contentDescription = asset.label, modifier = Modifier.size(144.dp), contentScale = ContentScale.Fit)
                            Text(asset.label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                            if (job.widgetKit && !asset.transparent) Text("未检测到透明背景", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { exportAssetJson = Json.encodeToString(asset); export.launch("${asset.label}.${asset.file.substringAfterLast('.')}") }, enabled = !busy) { Text("导出原图") }
                            TextButton(onClick = { references = listOf(asset.file); notice = "已作为参考图，请在上方填写修改要求。" }) { Text("继续修改") }
                        } }
                    }
                    if (job.assets.size > 1) TextButton(onClick = { exportAssetsJson = Json.encodeToString(job.assets); exportZip.launch("orbis-images-${job.id.take(8)}.zip") }, enabled = !busy) { Text("打包导出全部原图") }
                    if (job.status in setOf(WorkshopStatus.FAILED, WorkshopStatus.INTERRUPTED)) TextButton(onClick = { retry = job }, enabled = !busy) { Text("重新制作（会再次调用接口）") }
                    if (job.status == WorkshopStatus.QUEUED) TextButton(onClick = { launchTask {
                        withContext(Dispatchers.IO) { workshop.ledger.update(job.id) { if (it.status == WorkshopStatus.QUEUED) it.copy(status = WorkshopStatus.CANCELLED) else it } }
                    } }) { Text("取消排队") }
                    if (job.status !in setOf(WorkshopStatus.QUEUED, WorkshopStatus.RUNNING) && job.notifyConversation && !job.delivered) TextButton(onClick = { launchTask { workshop.deliver(job); notice = "已尝试投递原对话；不会重新生图。" } }) { Text("重投结果通知（不重新生图）") }
                } }
            }
            if (ownedJobs.isEmpty()) item { Text("还没有作品，第一张从你的描述开始。") }
            if (!showAll && ownedJobs.size > 20) item { TextButton(onClick = { showAll = true }) { Text("显示全部记录") } }
        }
    }
    if (kitDialog) AlertDialog(onDismissRequest = { kitDialog = false }, title = { Text("制作整套小部件素材") },
        text = { Text("将把你填写的外貌与内置提示词组合，依次请求 14 张原图（7 个动作、7 种表情），会多次调用所选服务并按所选接口计费。会使用首张成图保持人物一致；接口须支持改图。可以离开本页，后台继续制作。") },
        confirmButton = { TextButton(onClick = { kitDialog = false; submit(true) }) { Text("开始整套制作") } },
        dismissButton = { TextButton(onClick = { kitDialog = false }) { Text("取消") } })
    retry?.let { old -> AlertDialog(onDismissRequest = { retry = null }, title = { Text("确认重新制作？") },
        text = { Text("上次请求可能已被接口计费，重新制作会发起新的请求。已有原图不会删除。") },
        confirmButton = { TextButton(onClick = { retry = null; launchTask {
            workshop.submit(old.assistantId, old.conversationId, false, old.modelId, old.prompt, old.widgetKit, old.references,
                "retry-${old.id}", notifyConversation = old.notifyConversation)
            notice = "重做任务已接收。对同一记录重复点击不会重复制作；若重做仍失败，请在新的记录中确认重试。"
        } }) { Text("确认重新制作") } }, dismissButton = { TextButton(onClick = { retry = null }) { Text("取消") } }) }
}

private fun workshopStatus(status: WorkshopStatus) = when (status) {
    WorkshopStatus.QUEUED -> "等待制作"; WorkshopStatus.RUNNING -> "后台制作中"; WorkshopStatus.COMPLETE -> "已完成"
    WorkshopStatus.FAILED -> "制作未完成"; WorkshopStatus.INTERRUPTED -> "已中断，未自动重试"; WorkshopStatus.CANCELLED -> "已取消"
}
private fun workshopError(code: String?) = when (code) {
    "workshop_video_model" -> "Seedance 是视频模型，请改选已开通的 Seedream 图片模型。"
    "workshop_single_image_only", "workshop_seedream_parameters" -> "方舟图片接口当前每次制作一张。请移除 n、stream、组图或图层拆分等自定义参数后重试；不会自动重做。"
    "workshop_image_auth" -> "图片接口鉴权失败，请检查这个连接的 API Key；不要把 ChatGPT 套餐授权填在这里。"
    "workshop_image_access", "workshop_image_model" -> "图片模型没有访问权限或不存在，请核对方舟已开通的 Seedream 模型 ID。模型列表可见不代表已开通。"
    "workshop_image_limit" -> "图片接口达到用量或速率限制，请检查额度；没有自动重试。"
    "workshop_configuration_changed" -> "提供方配置已变化，未继续调用。请检查模型后重新提交。"
    "workshop_ai_disabled" -> "人类已关闭伙伴调用工坊。"
    "workshop_schedule_pending" -> "任务已保存，等待后台调度。请勿重复提交；下次启动会继续安排。"
    "workshop_request_conflict" -> "这次提交标识已有不同内容的已接收任务，请先检查制作记录，避免重复制作。"
    "workshop_interrupted_no_recharge" -> "制作被中断；为避免重复收费，不会自动重做。已收到的图片仍可导出。"
    "workshop_timeout" -> "本次制作等待超过 5 分钟，已停止等待且不会自动重复调用。接口可能已经计费，已保存的原图仍可导出。"
    "workshop_model_missing" -> "请先选择支持生图的模型并配置接口与密钥。"
    "workshop_provider_unsupported" -> "所选连接不能作为工坊生图后端。ChatGPT 套餐可用于下单，但制作图片需要独立图片 API，费用不包含在订阅中；没有自动切换到其他接口。"
    "workshop_target_changed", "workshop_owner_missing" -> "原助手或原对话已不存在，未转发到其他对话。"
    "workshop_image_large", "workshop_image_invalid", "workshop_image_format" -> "图片格式或大小不适合导入，请使用 PNG、JPEG、WebP（每张不超过24MB）。"
    "workshop_export" -> "无法写入所选文件，请重新选择导出位置。"
    "workshop_storage_unverified", "workshop_storage_large" -> "工坊记录暂时无法安全写入，已停止提交，避免重复调用。请先导出现有原图并检查本机存储。"
    else -> "操作未完成，请检查接口、模型和网络后手动重试。没有自动重复调用。"
}
