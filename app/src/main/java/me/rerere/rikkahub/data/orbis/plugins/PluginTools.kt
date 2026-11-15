package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val PERSONAL_PLUGIN_GUIDE = """个人插件，仅当前助手自己的本机脚本，不是市场。action=guide读取编写契约与示例（含日程/闹钟/通知/本机记忆/哨兵/状态/后台任务/交稿检查）；list列出插件及真实统计；read(id,include_code可选)读取详情、版本和记录；propose(manifest,id可选,expected_revision)创建/修改待确认版本，成功保存不等于获准运行。新建expected_revision=0，更新先read，必须保留name；插件ID和版本由宿主生成。manifest的title/summary/introduction全部由你写给人类，Orbis不改写；icon与palette控制卡片外观。人类在工具与娱乐→插件区批准版本与权限后才可运行，你不能自行授权。已启用版本注册成orbis_plugin_<name>工具，新增工具下轮出现；也可run(id,hash,arguments)调用已批准的确切版本。脚本是同步JavaScript函数体，入口args、orbis，return返回JSON结果；无DOM/Node、无后台常驻。超时5秒、内存24MiB、返回及日志合计32KiB，每次最多32个宿主调用；失败不自动重试。文件仅限此插件目录；网络仅声明且获准的公开HTTPS域名；终端还要求现有工作区授权，不绕过MCP或宿主许可。统计由宿主记账，不能伪造次数。更改版本不覆盖正在使用的版本，需人类确认；详情可停用、删除或回退。不要将人类私有工坊、未分享聊天或其他助手资料带入插件。"""

internal fun pluginSummary(entry: PluginEntry, host: PluginHost): JsonObject = buildJsonObject {
    val body=entry.latest().body
    put("id",entry.id); put("revision",entry.revision); put("name",body.name); put("title",body.title); put("summary",body.summary)
    put("icon",body.icon); put("palette",body.palette)
    put("latest_version",entry.latest().number); put("active_version",entry.activeVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("active_hash",entry.active()?.hash?.let(::JsonPrimitive) ?: JsonNull)
    put("enabled",entry.enabled); put("approved",host.approved(entry)); put("total_uses",entry.totalUses)
    put("uses_last_30_days",entry.recentUses(System.currentTimeMillis())); put("updated_at",entry.updatedAt)
    put("pending_review",entry.latest().number!=entry.activeVersion)
}

internal suspend fun personalPluginTools(host: PluginHost): List<Tool> = withContext(Dispatchers.IO) {
    if(!host.enabled()) return@withContext emptyList()
    // Keep management available if a stored file is damaged so the model gets an honest error.
    val entries=runCatching { host.repository.snapshot().entries }.getOrDefault(emptyList())
    listOf(Tool(name="orbis_personal_plugins",description=PERSONAL_PLUGIN_GUIDE,
        parameters={ InputSchema.Obj(buildJsonObject {
            put("action",buildJsonObject { put("type","string"); put("enum",JsonArray(listOf("guide","list","read","propose","run").map(::JsonPrimitive))) })
            put("id",buildJsonObject { put("type","string") })
            put("expected_revision",buildJsonObject { put("type","integer") })
            put("hash",buildJsonObject { put("type","string") })
            put("include_code",buildJsonObject { put("type","boolean") })
            put("version",buildJsonObject { put("type","integer"); put("description","read可指定单个版本，缺省最新。") })
            put("manifest",buildJsonObject { put("type","object"); put("description","完整插件定义，先调用guide读取字段和宿主API。") })
            put("arguments",buildJsonObject { put("type","object") })
        },required=listOf("action")) },isSelected=host::enabled,execute={ args -> pluginToolResult {
            check(host.enabled()) { "plugin_disabled" }
            val p=args.jsonObject
            fun id()=p["id"]?.jsonPrimitive?.content ?: error("plugin_id_required")
            when(p["action"]?.jsonPrimitive?.content) {
                "guide" -> buildJsonObject { put("guide",PLUGIN_API_GUIDE); put("example",PLUGIN_EXAMPLE) }
                "list" -> buildJsonObject { put("plugins",JsonArray(host.repository.snapshot().entries.map { pluginSummary(it,host) })) }
                "read" -> {
                    val entry=host.repository.snapshot().entries.firstOrNull { it.id==id() } ?: error("plugin_not_found")
                    val version=p["version"]?.jsonPrimitive?.intOrNull?.let { n -> entry.versions.firstOrNull { it.number==n } ?: error("plugin_version_missing") } ?: entry.latest()
                    val details=buildJsonObject {
                        put("introduction",version.body.introduction)
                        put("permissions",pluginJson.encodeToJsonElement(version.body.permissions))
                        put("network_hosts",pluginJson.encodeToJsonElement(version.body.networkHosts))
                        put("versions",JsonArray(entry.versions.map { buildJsonObject {put("number",it.number);put("hash",it.hash);put("created_at",it.createdAt)} }))
                        put("recent_runs",pluginJson.encodeToJsonElement(entry.runs.takeLast(20)))
                        if(p["include_code"]?.jsonPrimitive?.booleanOrNull==true) put("manifest",pluginJson.encodeToJsonElement(version.body))
                    }
                    JsonObject(pluginSummary(entry,host)+details)
                }
                "propose" -> {
                    val raw=p["manifest"] ?: error("plugin_manifest_required")
                    check(raw.toString().toByteArray().size<=160*1024) { "plugin_request_size" }
                    val body=pluginJson.decodeFromJsonElement<PluginManifest>(raw)
                    val entry=host.repository.propose(body,p["id"]?.jsonPrimitive?.contentOrNull,
                        p["expected_revision"]?.jsonPrimitive?.intOrNull ?: error("plugin_revision_required"))
                    JsonObject(pluginSummary(entry,host)+mapOf("status" to JsonPrimitive("awaiting_human_review"),
                        "open_in" to JsonPrimitive("工具与娱乐 → 插件区 → ${body.title} → 查看新版本")))
                }
                "run" -> host.execute(id(),p["hash"]?.jsonPrimitive?.content ?: error("plugin_hash_required"),
                    p["arguments"] ?: JsonObject(emptyMap()))
                else -> error("plugin_action_invalid")
            }
        } })) + entries.filter { it.enabled && host.approved(it) }.map { entry ->
        val version=entry.active()!!
        Tool(name="orbis_plugin_${version.body.name}",description="${version.body.title}：${version.body.summary}\n个人插件，第${version.number}版。权限和次数由Orbis管理。",
            parameters={ InputSchema.Obj(version.body.parameters["properties"] as? JsonObject ?: JsonObject(emptyMap()),
                (version.body.parameters["required"] as? JsonArray)?.map { it.jsonPrimitive.content }) },
            isSelected={ host.runnable(entry.id,version.hash) },execute={ args -> pluginToolResult { host.execute(entry.id,version.hash,args) } })
    }
}

private suspend fun pluginToolResult(block: suspend () -> JsonObject): List<UIMessagePart> = withContext(Dispatchers.IO) {
    val result=try { block() } catch(cancelled: CancellationException) { throw cancelled }
    catch(failure: Exception) { val code=pluginError(failure); buildJsonObject { put("ok",false); put("error",code); put("message",pluginErrorText(code)) } }
    listOf(UIMessagePart.Text(result.toString()))
}

internal const val PLUGIN_API_GUIDE = """manifest: {name:英文稳定ID(小写字母开头，字母数字下划线，最多40),title:名称<=80字,summary:简介<=240字,introduction:给人类的介绍<=12000字,code:同步JS函数体<=96KiB,parameters:JSON Schema,icon:moon|book|letter|leaf|star|music|sparkles,palette:lavender|blue|rose|mint|gold,permissions:[],networkHosts:[],beforeResponse?:false}。
parameters根为object，支持type/properties/required/items/enum/additionalProperties(boolean)/minimum/maximum/minLength/maxLength/minItems/maxItems/description/title/default；不支持ref、pattern、组合Schema，不会默默忽略未支持规则。
code以args读入参数，return JSON可序列化的值；不要export/module.exports/async/Promise。console输出最多4096字。无DOM、Node、任意Java桥、定时器或后台任务。外部操作按实际回执判断，不自动重试可能已执行的操作。
orbis.files.read(name)返回文字或null；list()返回文件名；write(name,text)原子保存UTF-8文字，单文件64KiB、每插件合计1MiB/100文件，文件名仅英文数字点下划线短横线。需声明并批准filesystem.read或filesystem.write；不能访问聊天/其他插件/应用密钥/根目录。
orbis.network.request({url,method:'GET'|'POST',body?:字符串})返回{status,body}；需network权限和networkHosts明确列出小写域名，只支持公开HTTPS443，禁止重定向和私网，无自动Cookie/账号认证。响应最多24KiB。不要在代码或介绍硬编码密钥。
orbis.clipboard.read()、write(text)需要clipboard.read/clipboard.write；只读写文字，Android后台限制仍适用。
orbis.shell.run(command)需shell权限，以及人类已给当前助手工作区终端的现有许可；未授权明确拒绝。工作区变化需重新批准。终端可以访问工作区文件与网络，不受普通files/network能力范围约束，请在介绍中如实说明必要性。插件默认不开放终端。此接口不会自动建立工作区或安装运行环境。

orbis.schedule.list({date?:YYYY-MM-DD,kind?:date|weekly,limit?:1..20,offset?:0,expected_revision?:整数})、read(id)、add({expected_revision,entry})、update({id,expected_revision,entry})、delete({id,expected_revision})，需schedule.read/write。先list获取revision；entry为{kind:"date",date:"2026-11-05",title:"事项",notes:"",all_day:true}，也支持start_time/end_time(HH:mm)、location、important；weekly用weekdays(1..7)、valid_from/valid_until。与日程页面共用数据，创建日程不会自动提醒，提醒另用wake/alarm。冲突刷新后判断，不盲重试。
orbis.alarm.set({hour,minute,message?})、cancel({hour,minute})、list({limit?:1..50,include_history?:true})需alarm；影响Orbis的一次性闹钟，同一时刻会替换原闹钟。返回已预约不等于已经响铃。日程和闹钟是保存到本机的独立事项，停用插件不删除这些事项。
orbis.notify.send(text)需notify；返回原生通知回执，不等于人已读。Android通知权限和陪伴服务设置仍有效。
orbis.wake(reason,due_at_ms可选)需wake；固定当前聊天的助手和窗口，创建一次性哨兵。省略时间为尽快预约（非即时保证），Unix毫秒必须未来时间。它唤醒AI处理reason，不直接执行JS。返回rule与human_master_enabled；总开关关闭时仅保存未运行，不能宣称已叫醒。原窗口删除/换助手、插件停用或版本授权失效都禁止旧预约投递，插件停用/换版会暂停旧规则，不自动恢复。
orbis.sentinel.create({type,name?,prompt?,...})、read(rule_id)、pause(rule_id)、resume(rule_id)、delete(rule_id)需sentinel，仅管理此插件此版本在当前窗口创建的规则。支持once(due_at_ms+prompt)、ritual(daily_at_local如09:00+prompt)、agreement(prompt，默认静默30分钟)、low_battery(无prompt，系统事实)、screen_on(无prompt，默认常亮10分钟)。更细字段沿用orbis_sentinel_guide。创建不等于运行，总开关只能由人类开；low_battery、screen_on、night_usage(默认00:00–07:30内非聊天应用使用5分钟)、geofence(沿用人类已设置围栏)还需单独声明并获准device.observe，原观察授权仍适用。截图和任意应用监视不通过此接口开放。定时更新雷达可用ritual唤醒原窗口，在prompt中说明应运行的插件工具和检查事项；此路径仍须AI实际调用；需要直接运行插件请用jobs。
orbis.memory.save("笔记")或save({body,summary?,tags?,keywords?,important?,state?:"static"|"conditional"|"pinned"|"paused"})需memory.write；search("关键词")或search({query,limit?:1..50,offset?})需memory.read。只访问当前助手的Orbis本机笔记，不访问其他助手、ST、OB或手机陪伴共享记忆；保存回执使用宿主调用身份。
orbis.system.status()需device.status，返回battery原生结果、screen_on、locked；steps()需device.steps；weather()需device.weather，沿用已有步数授权和天气城市，不自动启用观察。foreground()需device.foreground，并要求人类已经开启设备情境；只返回近期前台包名观察及时间，过期时明确不可用，不等于此刻仍在该应用。手机坠落事件在本版不支持，绝不伪造。
orbis.network.github({repo:"owner/repo",kind:"release"或"commit"})需network权限且networkHosts包含api.github.com；只读公开最新正式release或默认分支最新commit的少量元数据，不下载/安装。首次存基线，只有后续成功得到的tag/sha改变才通知，非200或网络失败保留旧基线。网络访问受相同权限与时间限制。
交稿前自动检查：manifest加beforeResponse:true并声明response.check，人类勾选后才生效。同助手各对话在最终回复交付前自动以args={event:"before_response",text:正文}运行，最多4个检查器；此入口不要求parameters匹配，普通手动调用仍校验parameters。脚本返回{pass:true}；也可返回{pass:true,text:作者自己处理后的全文}；不通过返回{pass:false,feedback:"具体修改建议"}。此阶段只能读本插件文件，禁止写文件、网络、通知等副作用。未通过/出错会保留并标记待修改草稿，不自动循环改写或额外调用模型；下一轮AI能看到具体提示。启用后正文先缓冲，检查完成再显示；未启用保持原流式显示。检查判断与改写完全由AI作者代码定义，Orbis不替作者设字数/代词规则。
orbis.jobs.add({interval_minutes:15..43200或due_at_ms:未来Unix毫秒,arguments:普通插件入参})、list()、cancel(job_id)需background.run；按计划直接运行当前已批准版本和参数，不先调用模型。一次性任务需due_at_ms；周期任务最短15分钟，可选due_at_ms设首次时间。Android可能推迟，不保证准点；每插件最多10项。预约与当前助手/原对话/版本/权限绑定，停用、换版、撤权、窗口删除后不继续执行旧任务。一次执行失败/中断不自动重试；周期任务下一次为新执行。任务不随备份恢复启用。可在详情→使用记录查看/取消。插件代码应区分args.mode=setup与check：setup只保存基线并添加jobs({arguments:{mode:"check"}})，check查询元数据，变化才notify并写回新基线，避免每次检查重复创建任务。外部通知与文件写入不是跨系统事务，结果未知时需核对，不能承诺恰好一次通知。
每次执行记录含宿主调用名称、开始/结束、返回/未完成/未知状态，不记录调用正文和密钥；可在插件详情使用记录及read.recent_runs查看。未知不代表未执行，不自动重放。
新建propose expected_revision=0，后续先read获取revision；人类批准前不可运行。命名工具在下一轮目录出现，也可用run(id,active_hash,arguments)调用已批准版本。成功结束才增加使用次数；尝试/失败单独记录。宿主能力与后台预约见下文。"""
internal const val PLUGIN_EXAMPLE = """{"name":"word_count","title":"文字小尺","summary":"看看一段文字有多长","introduction":"把你交给我的文字数一数，方便一起调整篇幅。只处理这次传入的文字，不读取其他内容。","icon":"book","palette":"blue","permissions":[],"networkHosts":[],"parameters":{"type":"object","properties":{"text":{"type":"string","maxLength":10000}},"required":["text"],"additionalProperties":false},"code":"return {characters: Array.from(args.text).length};"}"""
