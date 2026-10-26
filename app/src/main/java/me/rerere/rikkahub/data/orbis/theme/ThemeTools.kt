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
    description = "为当前助手设计聊天外观，默认关闭。action=list/read/guide/write/patch/publish/apply/revert/delete/import_asset。小改动必须优先patch已有主题id，body仅传更改字段；不传的字段保留，null移除可选字段，数组整组替换。publish和apply继续同一id，不增一套主题。guide返回完整自由绘制规格与可运行示例。先list读取授权；人类打开“交给AI自由装扮”后，新建主题也能通过write→publish→apply立即更换，不再每套确认。import_asset传path=/workspace/内已有图片，返回asset文件名；可做背景或气泡角标。write只写数据草稿，body为JSON字符串，最多32KiB；新建不传id，修改需id和expected_revision，不能修改人类主题或其他助手。publish校验草稿并生成不可变版本；apply带id和revision，只请求人类在侧栏“自定义美化”预览确认，人类全局允许AI装扮或对该主题开启自动应用时直接生效。revert一步回到上次已用外观，不改全局手工设置。list也列出人类导入的图片asset名称。schemaVersion=1,name,basedOn=builtin:default；缺省字段继承内置值。colors含background/surface/primary/onPrimary/text/textSecondary，均#RRGGBB；typography:{fontFamily:default|sans|serif|monospace|NotoSerifSC,fontSizeScale:0.85到1.4,lineHeight:1.2到2,weight:100到900,italic:boolean,letterSpacing:-0.05到0.2em}，NotoSerifSC使用系统衬线后备；bubbles:{assistant:{bg,text,radius:0到32,padding:6到24,maxWidthRatio:0.55到1,opacity:0到1(默认1，只改变底色/花纹/尾巴，文字与角标不透明),tail:none|cat|curl,shape:round|cat|rabbit|ticket|speech,pattern:none|dots|stars|gingham,decoration:none|paw|heart|star,decorationAsset:可选已导入文件名,typography:可选独立字体样式,drawing:自由向量轮廓/渐变/描边/动画图层，详见guide},user:{同上}}；background:{type:solid|image,asset:已导入文件名,dim:0到0.8,scroll:fixed}，assets:[该文件名]，纯色不带asset。chrome:{dock,onDock,star,composer,onComposer}可选#RRGGBB，控制底栏、星星与输入框。颜色仍用#RRGGBB，透明度单独用opacity，不使用8位颜色。透明气泡遇到壁纸时请通过预览检查文字；文字需足够对比度。主题正文禁止脚本、CSS、远程URL、文件路径；只有import_asset接受当前助手工作区路径。不会修改系统栏或输入法，不读取聊天正文。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        listOf("action", "id", "body", "path").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        listOf("expected_revision", "revision").forEach { key -> put(key, buildJsonObject { put("type", "integer") }) }
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
            put("message", "未应用改动，请检查参数、版本或主题内容；现有外观保留。"); put("automatic_retry", false)
        }.toString())) }
    },
)
internal fun executeThemeTool(store: ThemeRepository, raw: JsonElement): JsonObject {
    val args = raw.jsonObject
    fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("theme_invalid_parameters")
    fun number(key: String) = args[key]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("theme_invalid_parameters")
    val action = text("action")
    val allowed = when (action) {
        "list", "guide", "revert" -> setOf("action")
        "read" -> setOf("action", "id")
        "write", "patch" -> setOf("action", "body", "id", "expected_revision")
        "publish", "delete" -> setOf("action", "id", "expected_revision")
        "apply" -> setOf("action", "id", "revision")
        else -> error("theme_invalid_parameters")
    }
    require(args.keys.all { it in allowed }) { "theme_invalid_parameters" }
    return buildJsonObject {
        put("ok", true)
        when (action) {
            "guide" -> {
                put("coordinates", "drawing使用0..100的气泡局部坐标，随气泡尺寸缩放；outline是闭合SVG路径，自由绘制完整轮廓，存在时优先于shape和tail。支持M/L/H/V/Q/C/Z及对应小写、重复坐标，暂不支持SVG的A/S/T命令或XML。每路径最多4096字符/160段。")
                put("drawing", "drawing:{outline?:路径,gradient?:{colors:[2到6个#RRGGBB],x1:0,y1:0,x2:100,y2:100},stroke?:#RRGGBB,strokeWidth:0到8,layers:[最多12层]}。layers每项{path,fill?:#RRGGBB,gradient?:同上,stroke?:#RRGGBB,strokeWidth:0到8,opacity:0到1,motion?:{durationMs:1000到60000,alternate:true,frames:[{at:0,x:0,y:0,scale:1,rotation:0,opacity:1},...,{at:1,...}]}}。关键帧2到12个，at严格递增且起止0/1；x/y=-100到100，scale=0.1到3，rotation=-720到720。系统在关键帧间插值，动画只画在气泡轮廓内，不改变正文；聊天退后台或关闭系统动画时静止，列表缩略图静止。")
                put("typography", "顶层typography为共同字体样式；bubbles.assistant.typography、bubbles.user.typography可分别覆盖。fontFamily为设备字体default/sans/serif/monospace/NotoSerifSC，fontSizeScale=0.85..1.4，lineHeight=1.2..2，weight=100..900，italic=true/false，letterSpacing=-0.05..0.2(em)。本版不导入外部字体文件。")
                put("edit", "list→read原id→patch(id,expected_revision,body仅变更字段)→publish(id,新的expected_revision)→apply(id,revision)。未传字段原样保留；null移除可选字段，数组整组替换。改名字仍是同一套，不要write无id创建副本。保持近20个版本用于回退，当前使用/待确认版本保留。")
                put("example_body", themeJson.encodeToJsonElement(themeDrawingExample()))
            }
            "list" -> {
                val state = store.snapshot()
                put("ai_may_apply_new_themes", state.allowAiApply)
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
            "read" -> { val e = store.snapshot().entries.firstOrNull { it.id == text("id") } ?: error("theme_not_found")
                put("id", e.id); put("revision", e.revision); put("draft", themeJson.encodeToJsonElement(e.draft))
                put("published", themeJson.encodeToJsonElement(e.versions.lastOrNull())) }
            "write" -> { val id = if ("id" in args) text("id") else null
                val e = store.write(text("body"), id, if ("expected_revision" in args) number("expected_revision") else { check(id == null) { "theme_revision_required" }; 0 })
                put("id", e.id); put("revision", e.revision); put("status", "draft") }
            "publish" -> { val e = store.publish(text("id"), number("expected_revision")); put("id", e.id); put("revision", e.revision); put("status", "published_not_applied") }
            "patch" -> { val e=store.patch(text("body"),text("id"),number("expected_revision")); put("id",e.id);put("revision",e.revision);put("status","draft_updated_in_place") }
            "apply" -> { val applied = store.requestApply(ThemeRef(text("id"), number("revision"))); put("status", if (applied) "applied" else "awaiting_human_preview"); put("message", if (applied) "已按人类的装扮授权更换外观。" else "请在侧栏“自定义美化”查看预览并确认。尚未更换外观。") }
            "revert" -> { store.revert(); put("status", "reverted") }
            "delete" -> { store.delete(text("id"), number("expected_revision")); put("status", "deleted") }
        }
    }
}

/** Synthetic authoring example; returned as documentation, never installed by the tool. */
internal fun themeDrawingExample() = ThemePackage(name="流动的手写信", bubbles=ThemeBubbles(
    assistant=ThemeBubble(bg="#EDF4FC",text="#263342",typography=ThemeTypography(weight=500,italic=true,letterSpacing=.03f),
        drawing=ThemeDrawing(outline="M 8 3 Q 50 0 92 3 Q 100 5 98 20 L 98 80 Q 100 97 90 96 Q 55 100 8 97 Q 0 94 2 80 L 2 20 Q 0 5 8 3 Z",
            gradient=ThemeGradient(listOf("#E4ECFF","#F4E9FA","#E4F4F1")),stroke="#98AEC4",strokeWidth=.7f,
            layers=listOf(ThemeVectorLayer(path="M 0 70 C 25 45 70 100 100 65 L 100 100 L 0 100 Z",fill="#80B7C7",opacity=.2f,
                motion=ThemeMotion(durationMs=5000,frames=listOf(ThemeKeyframe(0f,y=-5f,opacity=.5f),ThemeKeyframe(.5f,y=3f,opacity=1f),ThemeKeyframe(1f,y=-2f,opacity=.6f))))))),
    user=ThemeBubble(bg="#F4E9E1",typography=ThemeTypography(fontFamily="sans",weight=600))))
