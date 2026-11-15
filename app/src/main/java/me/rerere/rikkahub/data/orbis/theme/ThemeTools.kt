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
                "theme_liquid_requires_round_shape" -> "液态玻璃需要该侧shape=round、tail=none、drawing.outline=null；自定义轮廓可改用frosted毛玻璃。"
                "theme_surface_invalid", "theme_texture_invalid", "theme_material_invalid", "theme_sticker_invalid", "theme_sticker_source_invalid", "theme_sticker_drawing_empty", "theme_stickers_too_many" -> "材质、纹理或贴纸参数无效。用guide查看material/texture/stickers范围；贴纸asset与drawing二选一，drawing需要layers。"
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
                put("material", "要真正模糊/折射/流动，必须设置material；只改opacity/drawing.gradient或画动态线条不会产生背景折射。每侧可选material；null恢复原底色。{type:frosted|liquid,blur:0到32(dp),tintOpacity:0到1,refraction:0到1,displacement:0到24(dp),depth:0到1,dispersion:0到1,highlight:0到1,rim:0到1,lightX:-1到1,lightY:-1到1,shimmer:false,shimmerDurationMs:2000到30000,flow:0到1,flowSpeed:0.2到2,metallic:0到1,fold:0到1,refractionHeight:0.1到0.5,edgeColors:[2到4个#RRGGBB],edgeShadow:#RRGGBB}。毛玻璃frosted真正模糊聊天壁纸；液态liquid增加边缘折射/色散，要求shape=round、tail=none、drawing.outline=null。bg是染色及后备颜色，tintOpacity控制染色透光；opacity控制整个材质透明度，正文始终清晰。已有drawing.gradient不再覆盖材质，drawing.layers及描边可叠加。纯色背景上折光不明显，请在壁纸上预览。Android 12起原生模糊、13起原生折光；旧设备/无背景源缩略图使用后备底色。shimmer只移动边缘高光；液态flow会持续扭曲背景采样，flowSpeed控制速度，metallic增强明暗金属反射，fold控制边缘反向折叠，refractionHeight控制折光带宽。edgeColors控制边缘流动色带，默认蓝/淡蓝/浅紫/浅粉；edgeShadow默认浅灰，不使用浓黑反光。可以分别调各区域的边缘配色。清透风格以弧形边缘的薄淡折光为主，避免把fold/metallic/blur开得过高形成厚重直边色带。要流动水面而不是静态透明，使用liquid且flow>0，例如flow=0.8/metallic=0.45/dispersion=0.55/fold=0.05/refractionHeight=0.18/displacement=12/blur=0/depth=0/tintOpacity=0.12。动画遵守系统动画及前后台设置。")
                put("stickers", "每侧stickers:[最多4项]，每项{asset:已导入文件名或drawing:同绘图规格二选一,anchor:topStart|topCenter|topEnd|bottomStart|bottomCenter|bottomEnd,size:16到48(dp),rotation:-45到45,opacity:0到1}。asset需列在assets；透明PNG/WebP适合贴纸。drawing必须有layers，可自由画蝴蝶结/动物/蕾丝等，按固定正方形0..100绘制，不随长消息拉长；outline/gradient/描边请画在layers里。贴纸跨气泡边缘绘制并自动给正文留空间；相同anchor会叠放，请错开。长消息花边仍用气泡drawing/pattern。")
                put("chrome_surfaces", "输入框、底部导航条、中心星星支持同款材质：chrome.surface为共享默认，composerSurface/dockSurface/starSurface可单独覆盖，starSurface缺省继承dockSurface。每个surface:{material:与气泡同规格,texture:与气泡同规格,opacity:0到1}。局部设{}使用本处原底色；设null恢复继承。气泡的material/texture仍各侧独立。表面只改外观，文字/图标/发送/导航/触控与键盘不变；颜色继续用chrome.composer/onComposer/dock/onDock/star。")
                put("whole_surface_patch_example", """{"chrome":{"surface":{"material":{"type":"liquid","blur":0,"depth":0,"tintOpacity":0.15,"refraction":0.9,"displacement":12,"flow":0.8,"flowSpeed":0.7,"metallic":0.45,"fold":0.05,"refractionHeight":0.18,"dispersion":0.55}}}}""")
                put("texture", "每侧可选texture，null移除。{type:ice|image,asset:仅image填写已导入图片名,color:#ECF8FF,opacity:0到1,scale:0.5到2,density:0.1到1,seed:0到65535,edgeFade:0到1}。ice是羽毛/蕨叶分叉冰晶，color为冰晶色，density控制疏密，scale控制纹理尺寸，seed固定花纹；edgeFade越大正文中间越淡。叠加material后，Android13以上会在模糊底层上生成透明冰晶厚度面：晶脊透出更清晰的背景，沿晶体法线局部折射，material.displacement/refraction控制扭曲，blur控制底层雾面。建议frosted blur12到20、tintOpacity0.25到0.4、displacement12到18、refraction0.8、texture.opacity0.9；没有材质时仅显示静态冰纹。旧系统/超长消息保留毛玻璃和静态纹理，不做晶体折射。image用自己的纹理图按原比例平铺，不随消息高度拉长，asset须列入assets；scale调平铺大小，opacity调透明，edgeFade给正文中间叠加淡底色以保持可读，color/density/seed仅ice有效。长消息纹理复杂度有上限。")
                put("builtin_materials", "人类可在主题工作室选择雾蓝·毛玻璃/流光·液态玻璃/静澜·静态液态玻璃/霜花·冰玻璃，并直接换背景图片、分别调整双方文字及输入框/底栏字色。静澜保留折射和彩色边缘，气泡/输入框/底栏/星星不持续流动。辅助图标、耗时等界面文字跟随人类界面主题；手选字色是本机显示偏好，换背景会保留，可恢复默认。这些内置主题保存在独立human条目，AI可list/read参考原设计，不能patch覆盖；想自己设计时复制body到新draft，保留用户的原版本。")
                put("ice_patch_example", """{"bubbles":{"assistant":{"bg":"#DDEFF9","material":{"type":"frosted","blur":16,"tintOpacity":0.3,"displacement":14,"refraction":0.85},"texture":{"type":"ice","color":"#F0FAFF","opacity":0.85,"density":0.8,"scale":1.2,"edgeFade":0.8,"seed":19}}}}""")
                put("material_patch_example", """{"bubbles":{"assistant":{"shape":"round","tail":"none","drawing":{"outline":null},"material":{"type":"frosted","blur":20,"tintOpacity":0.45}},"user":{"shape":"round","tail":"none","drawing":{"outline":null},"material":{"type":"liquid","blur":5,"depth":0.2,"tintOpacity":0.18,"dispersion":0.4,"shimmer":true}}}}""")
                put("typography", "fontFamily默认default，跟随人类聊天字体。人类‘字体跟随我的设置’默认开启时优先于主题字体，AI不能修改该开关。顶层typography为共同字体样式；bubbles.assistant.typography、bubbles.user.typography可分别覆盖。fontFamily为设备字体default/sans/serif/monospace/NotoSerifSC，fontSizeScale=0.85..1.4，lineHeight=1.2..2，weight=100..900，italic=true/false，letterSpacing=-0.05..0.2(em)。本版不导入外部字体文件。")
                put("edit", "list→read原id→patch(id,expected_revision,body仅变更字段)→publish(id,新的expected_revision)→apply(id,revision)。未传字段原样保留；null移除可选字段，数组整组替换。改名字仍是同一套，不要write无id创建副本。保持近20个版本用于回退，当前使用/待确认版本保留。")
                put("example_body", themeJson.encodeToJsonElement(themeDrawingExample()))
            }
            "list" -> {
                val state = store.snapshot()
                put("ai_may_apply_new_themes", state.allowAiApply)
                put("font_follows_human", state.followHumanFont)
                put("design_guide", "action=guide 获取毛玻璃/液态玻璃、冰花/图片纹理、边缘贴纸、自由路径、动态图层及局部修改示例")
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
