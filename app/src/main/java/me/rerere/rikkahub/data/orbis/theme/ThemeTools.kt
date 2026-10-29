package me.rerere.rikkahub.data.orbis.theme

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

internal fun openThemes(context: Context, owner: String) = ThemeRepository(File(context.filesDir.canonicalFile, "orbis-themes/$owner"))
internal fun importThemeImage(store: ThemeRepository, bytes: ByteArray): String {
    require(bytes.size <= 10 * 1024 * 1024) { "theme_asset_invalid" }
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    require(opts.outWidth in 1..8192 && opts.outHeight in 1..8192 && opts.outWidth.toLong() * opts.outHeight <= 32_000_000) { "theme_asset_invalid" }
    val extension = when (opts.outMimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> error("theme_asset_invalid") }
    return store.importAsset(bytes, extension)
}
internal fun themeTool(open: () -> ThemeRepository, enabled: () -> Boolean,
    readWorkspaceImage: (suspend (String) -> ByteArray)? = null) = Tool(
    name = "orbis_theme",
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_theme"),
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("list", "read", "guide", "write", "patch", "publish", "apply", "revert", "delete", "import_asset").map(::JsonPrimitive))) })
        listOf("id", "body", "path").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        put("expected_revision", buildJsonObject { put("type", "integer"); put("description", "publish/patch/delete和修改已有草稿必填；用最新read/write/patch返回的revision值") })
        put("revision", buildJsonObject { put("type", "integer"); put("description", "仅apply使用：已发布版本号；publish请改用expected_revision") })
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("list", "read", "guide") },
    hostApproval = HostToolApproval("orbis:theme", "theme-data-v1", "自定义美化"),
    isApprovalCurrent = { enabled() },
    execute = { args ->
        try {
            val result = withContext(Dispatchers.IO) { check(enabled()) { "theme_tool_disabled" }
                if (args.jsonObject["action"]?.jsonPrimitive?.content == "import_asset") {
                    val objectArgs = args.jsonObject
                    require(objectArgs.keys == setOf("action", "path")) { "theme_invalid_parameters" }
                    val path = objectArgs["path"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("theme_invalid_parameters")
                    require(path.startsWith("/workspace/") && path.length <= 512 && path.split('/').none { it == ".." || it == "." } &&
                        path.none { it.isISOControl() || it == '\\' }) { "theme_asset_invalid" }
                    val bytes = (readWorkspaceImage ?: error("theme_workspace_unavailable"))(path)
                    check(enabled()) { "theme_tool_disabled" }
                    val asset = importThemeImage(open(), bytes)
                    buildJsonObject { put("ok", true); put("asset", asset); put("status", "asset_imported") }
                } else executeThemeTool(open(), args) }
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", false); put("code", e.message?.takeIf { it.matches(Regex("theme_[a-z_]+")) } ?: "theme_invalid_package_or_storage")
            if (e is ThemeAssetException) {
                put("field", e.field); put("issue", e.issue); put("phase", "asset_validation"); put("message", e.hint)
            } else if (e is ThemeParameterException) {
                put("field", e.field); put("expected_fields", JsonArray(e.expectedFields.map(::JsonPrimitive)))
                put("phase", "argument_validation"); put("storage_checked", false)
                put("message", e.hint)
            } else put("message", when (e.message) {
                "theme_revision_conflict", "theme_revision_or_owner_conflict" -> "主题版本或作者不匹配。先read同一id，使用返回的revision作为expected_revision；AI只能编辑自己的主题。"
                "theme_draft_missing" -> "没有待发布草稿。先read核对已有发布版本；需要更改时先patch。"
                "theme_store_full" -> "主题库容量已满，请在自定义美化里整理后再试。"
                else -> "本次未完成，请先read核对当前状态，再检查主题内容或存储。"
            })
            put("diagnose", "可用orbis_self_check focus=theme；人类也可到当前助手设置→本地工具→AI自检。")
            put("automatic_retry", false)
        }.toString())) }
    },
)
internal fun executeThemeTool(store: ThemeRepository, raw: JsonElement): JsonObject {
    val args = raw as? JsonObject ?: throw ThemeParameterException("arguments", emptyList(), "参数必须是JSON对象。")
    val action = (args["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ThemeParameterException("action", listOf("action"), "请填写action。")
    val allowed = when (action) {
        "list", "guide", "revert" -> setOf("action")
        "read" -> setOf("action", "id")
        "write", "patch" -> setOf("action", "body", "id", "expected_revision")
        "publish", "delete" -> setOf("action", "id", "expected_revision")
        "apply" -> setOf("action", "id", "revision")
        else -> throw ThemeParameterException("action", listOf("action"), "未知action；用guide查看支持的操作。")
    }
    fun invalid(key: String): Nothing = throw ThemeParameterException(key, allowed.toList(),
        if (action == "publish") "publish需要id和整数expected_revision；先read取最新revision，填入expected_revision，不能传revision或body。"
        else "$action 的${key}缺失或类型不匹配；只使用expected_fields列出的参数。")
    if (args.keys.any { it !in allowed }) invalid("arguments")
    fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(key)
    fun number(key: String) = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid(key)
    return buildJsonObject {
        put("ok", true)
        when (action) {
            "guide" -> {
                put("package_spec", me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_theme"))
                put("bubble_shape", "每侧bubbles.assistant/user独立：shape=round/cat/rabbit/ticket/speech；tail=none/cat/curl，none无尾、cat猫尾、curl卷尾。tail直接放气泡字段内；已有drawing.outline时它覆盖shape/tail，要用预设尾巴先patch该侧drawing.outline=null（保留其余绘图字段），或把尾巴画进完整outline。")
                put("tail_patch_example", "{\"bubbles\":{\"assistant\":{\"tail\":\"cat\",\"drawing\":{\"outline\":null}},\"user\":{\"tail\":\"curl\",\"drawing\":{\"outline\":null}}}}")
                put("coordinates", "drawing使用0..100的气泡局部坐标，随气泡尺寸缩放；outline是闭合SVG路径，自由绘制完整轮廓，存在时优先于shape和tail。支持M/L/H/V/Q/C/Z及对应小写、重复坐标，暂不支持SVG的A/S/T命令或XML。每路径最多4096字符/160段。")
                put("drawing", "drawing:{outline?:路径,gradient?:{colors:[2到6个#RRGGBB],x1:0,y1:0,x2:100,y2:100},stroke?:#RRGGBB,strokeWidth:0到8,layers:[最多12层]}。layers每项{path,fill?:#RRGGBB,gradient?:同上,stroke?:#RRGGBB,strokeWidth:0到8,opacity:0到1,motion?:{durationMs:1000到60000,alternate:true,frames:[{at:0,x:0,y:0,scale:1,rotation:0,opacity:1},...,{at:1,...}]}}。关键帧2到12个，at严格递增且起止0/1；x/y=-100到100，scale=0.1到3，rotation=-720到720。系统在关键帧间插值，动画只画在气泡轮廓内，不改变正文；聊天退后台或关闭系统动画时静止，列表缩略图静止。")
                put("typography", "fontFamily默认default，跟随人类聊天字体。人类‘字体跟随我的设置’默认开启时优先于主题字体，AI不能修改该开关。顶层typography为共同字体样式；bubbles.assistant.typography、bubbles.user.typography可分别覆盖。fontFamily为设备字体default/sans/serif/monospace/NotoSerifSC，fontSizeScale=0.85..1.4，lineHeight=1.2..2，weight=100..900，italic=true/false，letterSpacing=-0.05..0.2(em)。本版不导入外部字体文件。")
                put("edit", "list→read原id→patch(id,expected_revision,body仅变更字段)→publish(id,新的expected_revision)→apply(id,revision)。未传字段原样保留；null移除可选字段，数组整组替换。改名字仍是同一套，不要write无id创建副本。保持近20个版本用于回退，当前使用/待确认版本保留。")
                put("example_body", themeJson.encodeToJsonElement(themeDrawingExample()))
            }
            "list" -> {
                val state = store.snapshot()
                put("ai_may_apply_new_themes", state.allowAiApply)
                put("font_follows_human", state.followHumanFont)
                put("design_guide", "action=guide 获取自由路径、动态图层、独立字体和局部修改示例")
                put("edit_existing", "小改动先read原id，再patch同一id和expected_revision，只写要改的字段；publish→apply。不要为每次调整新建主题。")
                put("next_step", if(state.allowAiApply) "write → publish → apply，可直接更换新主题" else "可设计并请求预览；人类在主题工作室打开交给AI自由装扮后可直接切换")
                put("entries", JsonArray(state.entries.map { entry -> buildJsonObject {
                    put("id", entry.id); put("name", entry.draft?.name ?: entry.versions.lastOrNull()?.body?.name.orEmpty())
                    put("authorRole", entry.authorRole); put("revision", entry.revision); put("has_draft", entry.draft != null)
                    put("published_revisions", JsonArray(entry.versions.map { JsonPrimitive(it.revision) }))
                    put("auto_apply", state.allowAiApply || entry.id in state.autoApply)
                } }))
                put("assets", JsonArray(store.assets().map(::JsonPrimitive)))
                put("active", themeJson.encodeToJsonElement(state.active)); put("pending", themeJson.encodeToJsonElement(state.pending))
            }
            "read" -> { val id = text("id"); val e = store.snapshot().entries.firstOrNull { it.id == id } ?: error("theme_not_found")
                put("id", e.id); put("revision", e.revision); put("draft", themeJson.encodeToJsonElement(e.draft))
                put("published", themeJson.encodeToJsonElement(e.versions.lastOrNull()))
                put("missing_asset_fields", JsonArray((e.draft ?: e.versions.lastOrNull()?.body)?.let { store.missingAssetFields(it) }.orEmpty().map(::JsonPrimitive))) }
            "write" -> { val id = if ("id" in args) text("id") else null
                val e = store.write(text("body"), id, if ("expected_revision" in args) number("expected_revision") else { check(id == null) { "theme_revision_required" }; 0 })
                put("id", e.id); put("revision", e.revision); put("status", "draft")
                put("next_call", buildJsonObject { put("action", "publish"); put("id", e.id); put("expected_revision", e.revision) }) }
            "publish" -> { val e = store.publish(text("id"), number("expected_revision")); put("id", e.id); put("revision", e.revision); put("status", "published_not_applied")
                put("next_call", buildJsonObject { put("action", "apply"); put("id", e.id); put("revision", e.revision) }) }
            "patch" -> { val e=store.patch(text("body"),text("id"),number("expected_revision")); put("id",e.id);put("revision",e.revision);put("status","draft_updated_in_place")
                put("next_call", buildJsonObject { put("action", "publish"); put("id", e.id); put("expected_revision", e.revision) }) }
            "apply" -> { val applied = store.requestApply(ThemeRef(text("id"), number("revision"))); put("status", if (applied) "applied" else "awaiting_human_preview"); put("message", if (applied) "已按人类的装扮授权更换外观。" else "请在侧栏“自定义美化”查看预览并确认。尚未更换外观。") }
            "revert" -> { store.revert(); put("status", "reverted") }
            "delete" -> { store.delete(text("id"), number("expected_revision")); put("status", "deleted") }
        }
    }
}

internal class ThemeParameterException(val field: String, val expectedFields: List<String>, val hint: String) : IllegalArgumentException("theme_invalid_parameters")

/** Synthetic authoring example; returned as documentation, never installed by the tool. */
internal fun themeDrawingExample() = ThemePackage(name="流动的手写信", bubbles=ThemeBubbles(
    assistant=ThemeBubble(bg="#EDF4FC",text="#263342",typography=ThemeTypography(weight=500,italic=true,letterSpacing=.03f),
        drawing=ThemeDrawing(outline="M 8 3 Q 50 0 92 3 Q 100 5 98 20 L 98 80 Q 100 97 90 96 Q 55 100 8 97 Q 0 94 2 80 L 2 20 Q 0 5 8 3 Z",
            gradient=ThemeGradient(listOf("#E4ECFF","#F4E9FA","#E4F4F1")),stroke="#98AEC4",strokeWidth=.7f,
            layers=listOf(ThemeVectorLayer(path="M 0 70 C 25 45 70 100 100 65 L 100 100 L 0 100 Z",fill="#80B7C7",opacity=.2f,
                motion=ThemeMotion(durationMs=5000,frames=listOf(ThemeKeyframe(0f,y=-5f,opacity=.5f),ThemeKeyframe(.5f,y=3f,opacity=1f),ThemeKeyframe(1f,y=-2f,opacity=.6f))))))),
    user=ThemeBubble(bg="#F4E9E1",typography=ThemeTypography(fontFamily="sans",weight=600))))
