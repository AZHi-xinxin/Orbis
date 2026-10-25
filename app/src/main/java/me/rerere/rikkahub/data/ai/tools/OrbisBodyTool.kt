package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.body.*
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

internal const val ORBIS_BODY_TOOL = "orbis_body"

internal fun bodyTool(conversation: String, enabled: () -> Boolean, open: () -> BodyRepository) = Tool(
    name = ORBIS_BODY_TOOL,
    description = """
        当前助手的本地身体表达模拟，不是生理测量或主观体验证明，不依赖ST。关闭时无此工具。
        启用后先读rules，查看setup提示并完成设置。首次draft未写triggerMode时默认auto；已有规则未写时保留原模式。若setup.code=manual_mode，表示仍未开启自动响应，请保留自己现有规则切换，不能告诉人类“程序尚未实现”。不要等待人类知道模式名或点击运行。
        action=rules读取定义/版本/运行状态与revision，不返回计算数值。人类只启用模块，运行由你管理：先pause暂停，draft定义、activate发布不可变新版本，最后resume继续；这些操作均需最新expected_revision。不允许一边运行一边改规则，首次无规则时可直接draft→activate→resume，无需等待人类点运行。
        新规则默认triggerMode="auto"：只扫描宿主接收的新的人类消息，AI输出/工具/编辑/重试不触发。auto禁止event和undo；你用state读取当前会话自身的数值，但不能手动写数值或制造事件。旧存档缺triggerMode时兼容manual并提示待切换；切换模式须pause→draft→activate→resume，保留历史和已有同ID数值。缺触发词或未恢复运行也会在setup中明确提醒。
        definition示例：{"triggerMode":"auto","dimensions":[{"id":"energy","label":"活力","scope":"session","baseline":50,"min":0,"max":100,"maxDelta":10,"halfLifeMinutes":30,"private":false}],"rules":[{"event":"rest","deltas":{"energy":8},"keywords":["休息"],"synonyms":["歇一会儿"],"weight":1.0}],"absence":{"graceMinutes":30,"capHours":24,"perHour":{},"returnEvent":null}}。这只是格式示例，不是预置个性。
        最多16个独立维度、64个事件；scope=session会话隔离或persistent助手共享。规则只是数据，不支持脚本。auto中每个词面/近义短语命中算一份，重叠词不重复计份，乘weight后受浓度上限限制；delta乘浓度再受maxDelta和min/max约束。可配置autoPolicy={maxConcentration:3,windowMinutes:10,maxEventsPerWindow:3}，分别为浓度上限(0.1–10)、同句去重及频率窗口(1–1440分钟)、同事件同会话窗口内次数(1–60)。这些是省略时的默认参数，不是所有角色固定的反应节奏；只能在定义阶段修改。去重跨重开保留，不存人类原句。synonyms由你定义，是本机短语匹配，不冒充外部语义模型。
        可选cooling={keywords:[],synonyms:[],event:null,blockedEvents:[],cooldownMinutes:5,pauseAuto:false}。词、反应事件、冷却的事件、时长及是否暂停全由规则定义；默认没有冷静词或固定反应，不把“停”、某个人的喜好或所有指标下降写成通用规则。命中时只触发配置的event，冷却只拦blockedEvents；可让某指标降低、另一个稳定指标升高。配置pauseAuto才暂停；AI须等配置的冷静期结束才能resume。此配置同样只可在暂停阶段修改。
        维度可加private布尔字段，默认false；设为true后不能改为false，删除再建同ID也不能解除。action=visibility用shared布尔值及expected_revision决定你是否公开当前会话私密层，默认不公开；不影响数值。人类本机privateVisible查看开关默认false，只能人类UI改，你不能提交或查询。只有你愿意公开且人类选择展开时才渲染私密值/峰值/曲线；隐藏仍计算和记日志。
        manual模式才允许action=event，传event、version、strength(0–3)、visible；不传数值，回执仅已记及seq。visible控制普通面板，不覆盖私密层双条件。manual的undo需最新seq，仅撤回当前会话最近事件。重试由宿主标识去重，不要另造事件重试。
        时间变化无需调用event：运行期间按真实经过的时间自然回归baseline；absence.graceMinutes是静默多少分钟后开始，perHour指定各指标每小时正负变化速率，capHours限制每段静默的累计作用时间。时间速率与自然回归连续合算，结果仍受min/max限制；maxDelta限制离散事件，不按刷新次数累加。session维度各会话独立计时，persistent维度按该助手最近一次人类消息计时，多个窗口不重复叠加。returnEvent仅在对应会话静默超过graceMinutes后收到新的人类消息时触发一次。后台/关闭重开也按经过时间计算，不调用模型；暂停或关闭模块期间冻结，恢复后重新计时。旧版升级从首次打开起建立新时钟，不追算升级前的空白。修改这些时间规则同样由你pause→draft→activate→resume。
        action=self_report、text最多300字，仅显示“自述”，不改任何计算值。规则/历史不自动带入上下文；不要借负面量表催促人类留下。
    """.trimIndent(),
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("rules", "state", "visibility", "pause", "resume", "draft", "activate", "event", "undo", "self_report").map(::JsonPrimitive))) })
        put("definition", buildJsonObject { put("type", "object"); put("description", "完整安全规则JSON，参照工具说明；未知字段会拒绝") })
        for(name in listOf("expected_revision", "version", "strength", "seq")) put(name, buildJsonObject { put("type", "integer") })
        for(name in listOf("event", "text")) put(name, buildJsonObject { put("type", "string") })
        put("visible", buildJsonObject { put("type", "boolean") })
        put("shared", buildJsonObject { put("type", "boolean") })
    }, required = listOf("action")) }, needsApproval = { false }, execute = { raw ->
        val invocation = currentCoroutineContext()[CloudToolInvocationContext]
        val result = try { withContext(Dispatchers.IO) {
            check(enabled()) { "body_disabled" }
            val obj = raw as? JsonObject ?: error("body_parameters_invalid")
            require(obj.toString().toByteArray().size <= 32768) { "body_definition_limit" }
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("body_parameters_invalid")
            val fields = when(action) {
                "rules", "state" -> emptySet(); "draft" -> setOf("definition", "expected_revision")
                "visibility" -> setOf("shared", "expected_revision")
                "activate", "pause", "resume" -> setOf("expected_revision"); "event" -> setOf("event", "version", "strength", "visible")
                "undo" -> setOf("seq"); "self_report" -> setOf("text"); else -> error("body_parameters_invalid")
            }
            require(obj.keys.all { it == "action" || it in fields }) { "body_parameters_invalid" }
            fun num(name: String): Long = (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("body_parameters_invalid")
            fun text(name: String): String = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("body_parameters_invalid")
            val repo = open()
            if(action == "state") buildJsonObject {
                val state = repo.viewSnapshot(); val definition = state.versions.lastOrNull()?.definition ?: BodyDefinition(triggerMode = "auto")
                val session = state.snapshot.sessions[conversation]
                put("ok", true); put("running", state.running); put("revision", state.revision)
                put("version", state.versions.lastOrNull()?.version ?: 0); put("triggerMode", definition.triggerMode)
                put("privateShared", session?.privateShared == true)
                put("values", buildJsonObject { BodyRules.values(state.snapshot, conversation, definition).forEach { (id, value) -> put(id, value) } })
                put("cooldownUntil", state.auto.cooldownUntil)
                putBodySetup(state)
            } else if(action == "rules") buildJsonObject {
                val state = repo.snapshot(); put("ok", true); put("running", state.running); put("revision", state.revision)
                put("version", state.versions.lastOrNull()?.version ?: 0)
                val definition = state.versions.lastOrNull()?.definition ?: BodyDefinition(triggerMode = "auto")
                put("definition", JsonObject(BodyRules.json.encodeToJsonElement(definition).jsonObject +
                    ("triggerMode" to JsonPrimitive(definition.triggerMode))))
                put("has_draft", state.draft != null)
                putBodySetup(state)
            } else {
                require(invocation?.conversationId == conversation) { "body_invocation_missing" }
                val key = "$conversation:${invocation!!.messageId}:${invocation.toolCallId}"
                when(action) {
                    "visibility" -> {
                        val shared = (obj["shared"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("body_parameters_invalid")
                        repo.sharePrivate(conversation, shared, num("expected_revision"), key)
                    }
                    "pause", "resume" -> repo.setRunningFromTool(conversation, action == "resume", num("expected_revision"), key)
                    "draft" -> {
                        val definition = obj["definition"] as? JsonObject ?: error("body_parameters_invalid")
                        val explicitMode = if ("triggerMode" in definition) definition else JsonObject(definition +
                            ("triggerMode" to JsonPrimitive(BodySetup.defaultDraftMode(repo.snapshot()))))
                        repo.define(BodyRules.json.decodeFromJsonElement(explicitMode), num("expected_revision"), key)
                    }
                    "activate" -> repo.activate(num("expected_revision"), key)
                    "event" -> {
                        val version = num("version"); require(version in 1..Int.MAX_VALUE.toLong()) { "body_version_changed" }
                        val strength = if("strength" in obj) num("strength") else 0
                        require(strength in 0..3) { "body_strength_invalid" }
                        val visible = if("visible" !in obj) true else (obj["visible"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("body_parameters_invalid")
                        repo.event(conversation, text("event"), strength.toInt(), visible, key, version.toInt())
                    }
                    "undo" -> repo.undo(conversation, num("seq"), key)
                    "self_report" -> repo.selfReport(conversation, text("text"), key)
                }
                buildJsonObject {
                    put("ok", true); put("status", "已记"); put("seq", repo.receiptSeq(key))
                    if (action in setOf("draft", "activate", "pause", "resume", "visibility")) {
                        val state = repo.snapshot()
                        put("revision", state.revision); put("version", state.versions.lastOrNull()?.version ?: 0)
                        put("has_draft", state.draft != null)
                        put("running", state.running)
                    }
                    // Even a legacy caller that only sends events learns that auto is available.
                    putBodySetup(repo.snapshot())
                }
            }
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { buildJsonObject {
            val code = error.message?.takeIf { it.matches(Regex("body_[a-z_]+")) } ?: "body_unavailable"
            put("ok", false); put("code", code)
            put("message", when (code) {
                "body_invocation_missing" -> "应用未提供匹配的会话调用信息，未写入。这不是人类未开启运行；先修复或更新应用的工具执行链。"
                "body_pause_before_definition" -> "当前正在运行，请先调用 pause 暂停，再 draft/activate 修改规则，最后 resume 继续。无需人类点运行。"
                "body_definition_empty" -> "尚无已发布规则。先 draft 定义、再 activate 发布，之后调用 resume 开始运行。"
                "body_private_downgrade_forbidden" -> "已标为私密的维度不能改回普通；请保留 private=true。你可用visibility选择是否公开，人类选择是否展开。"
                "body_manual_event_disabled", "body_auto_undo_disabled" -> "当前为auto，事件与计算由系统按规则触发；不能手动event或undo。可以读state，或暂停后修改规则。"
                "body_cooling_active" -> "当前仍在规则配置的冷静期，尚未恢复运行；可读state核对结束时间。"
                "body_disabled" -> "当前助手尚未启用身体模块，请先由人类开启模块，再从新的聊天轮次调用。"
                else -> "本次未确认完成，未自动重试；请读 rules 核对版本，并根据 code 检查参数。"
            })
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun JsonObjectBuilder.putBodySetup(state: BodyState) {
    put("triggerMode", state.versions.lastOrNull()?.definition?.triggerMode ?: "unconfigured")
    put("defaultTriggerMode", "auto")
    val notice = BodySetup.notice(state)
    put("setup", buildJsonObject {
        put("needs_attention", notice != null)
        put("code", notice?.code ?: "auto_ready")
        notice?.let { put("message", it.aiNextStep) }
    })
}
