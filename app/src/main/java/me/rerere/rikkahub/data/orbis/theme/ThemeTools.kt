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
    description = "为当前助手设计聊天外观，默认关闭。action=list/read/write/publish/apply/revert/delete/import_asset。先list读取授权；人类打开“交给AI自由装扮”后，新建主题也能通过write→publish→apply立即更换，不再每套确认。import_asset传path=/workspace/内已有图片，返回asset文件名；可做背景或气泡角标。write只写数据草稿，body为JSON字符串，最多32KiB；新建不传id，修改需id和expected_revision，不能修改人类主题或其他助手。publish校验草稿并生成不可变版本；apply带id和revision，只请求人类在侧栏“自定义美化”预览确认，人类全局允许AI装扮或对该主题开启自动应用时直接生效。revert一步回到上次已用外观，不改全局手工设置。list也列出人类导入的图片asset名称。schemaVersion=1,name,basedOn=builtin:default；缺省字段继承内置值。colors含background/surface/primary/onPrimary/text/textSecondary，均#RRGGBB；typography:{fontFamily:default|sans|serif|monospace|NotoSerifSC,fontSizeScale:0.85到1.4,lineHeight:1.2到2}，NotoSerifSC使用系统衬线后备；bubbles:{assistant:{bg,text,radius:0到32,padding:6到24,maxWidthRatio:0.55到1,opacity:0到1(默认1，只改变底色/花纹/尾巴，文字与角标不透明),tail:none|cat|curl,shape:round|cat|rabbit|ticket|speech,pattern:none|dots|stars|gingham,decoration:none|paw|heart|star,decorationAsset:可选已导入文件名},user:{同上}}；background:{type:solid|image,asset:已导入文件名,dim:0到0.8,scroll:fixed}，assets:[该文件名]，纯色不带asset。chrome:{dock,onDock,star,composer,onComposer}可选#RRGGBB，控制底栏、星星与输入框。颜色仍用#RRGGBB，透明度单独用opacity，不使用8位颜色。透明气泡遇到壁纸时请通过预览检查文字；文字需足够对比度。主题正文禁止脚本、CSS、远程URL、文件路径；只有import_asset接受当前助手工作区路径。不会修改系统栏或输入法，不读取聊天正文。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        listOf("action", "id", "body", "path").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        listOf("expected_revision", "revision").forEach { key -> put(key, buildJsonObject { put("type", "integer") }) }
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("list", "read") },
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
        "list", "revert" -> setOf("action")
        "read" -> setOf("action", "id")
        "write" -> setOf("action", "body", "id", "expected_revision")
        "publish", "delete" -> setOf("action", "id", "expected_revision")
        "apply" -> setOf("action", "id", "revision")
        else -> error("theme_invalid_parameters")
    }
    require(args.keys.all { it in allowed }) { "theme_invalid_parameters" }
    return buildJsonObject {
        put("ok", true)
        when (action) {
            "list" -> {
                val state = store.snapshot()
                put("ai_may_apply_new_themes", state.allowAiApply)
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
            "apply" -> { val applied = store.requestApply(ThemeRef(text("id"), number("revision"))); put("status", if (applied) "applied" else "awaiting_human_preview"); put("message", if (applied) "已按人类的装扮授权更换外观。" else "请在侧栏“自定义美化”查看预览并确认。尚未更换外观。") }
            "revert" -> { store.revert(); put("status", "reverted") }
            "delete" -> { store.delete(text("id"), number("expected_revision")); put("status", "deleted") }
        }
    }
}
