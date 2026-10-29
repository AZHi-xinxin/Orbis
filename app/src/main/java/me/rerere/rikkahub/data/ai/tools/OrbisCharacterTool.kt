package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.character.*
import me.rerere.rikkahub.ui.widget.OrbisCharacterWidget

internal fun createOrbisCharacterTool(context: Context, owner: String, ownerActive: () -> Boolean): Tool =
    createCharacterTool(open = {
        require(ownerActive()) { "character_owner_unavailable" }
        openCharacterStore(context, owner)
    }, changed = { OrbisCharacterWidget.refresh(context) })

internal fun createCharacterTool(open: () -> CharacterStore, changed: () -> Unit = {}, now: () -> Long = System::currentTimeMillis) = Tool(
    name = "orbis_character",
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_character"),
    parameters = { InputSchema.Obj(buildJsonObject {
        put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("get", "set_lines", "set_greeting", "set_expression_policy", "clear_expression_policy", "set_sprite").map(::JsonPrimitive))) })
        listOf("region_id", "text", "asset_id").forEach { put(it, buildJsonObject { put("type", "string") }) }
        listOf("expected_revision", "duration_seconds").forEach { put(it, buildJsonObject { put("type", "integer"); put("minimum", 0) }) }
        listOf("lines", "allowed").forEach { put(it, buildJsonObject { put("type", "array"); put("maxItems", 10); put("items", buildJsonObject { put("type", "string") }) }) }
    }, required = listOf("action")) },
    needsApproval = { (it as? JsonObject)?.get("action") != JsonPrimitive("get") },
    hostApproval = HostToolApproval("orbis:character", "character-room-v1", "互动房间与桌面台词"),
    execute = { raw ->
        val result = try { withContext(Dispatchers.IO) { executeCharacterTool(open(), raw, now()).also { if ((raw as? JsonObject)?.get("action") != JsonPrimitive("get")) changed() } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { buildJsonObject { put("ok", false); put("error", failure.message?.takeIf { it.startsWith("character_") } ?: "character_unavailable"); put("automatic_retry", false) } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun executeCharacterTool(store: CharacterStore, raw: JsonElement, now: Long): JsonObject {
    val args = omitReadPlaceholders(raw as? JsonObject ?: error("character_arguments"), setOf("get"),
        mapOf("region_id" to JsonPrimitive(""), "text" to JsonPrimitive(""), "asset_id" to JsonPrimitive(""),
            "expected_revision" to JsonPrimitive(0), "duration_seconds" to JsonPrimitive(0),
            "lines" to JsonArray(emptyList()), "allowed" to JsonArray(emptyList())))
    fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("character_arguments")
    fun number(key: String) = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("character_arguments")
    fun strings(key: String) = (args[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("character_arguments") } ?: error("character_arguments")
    val action = text("action")
    val fields = when (action) {
        "get" -> emptySet()
        "set_lines" -> setOf("region_id", "lines", "expected_revision")
        "set_greeting" -> setOf("text", "expected_revision")
        "set_expression_policy" -> setOf("allowed", "duration_seconds", "expected_revision")
        "clear_expression_policy" -> setOf("expected_revision")
        "set_sprite" -> setOf("asset_id", "expected_revision")
        else -> error("character_arguments")
    } + "action"
    require(args.keys == fields) { "character_arguments" }
    var p = store.read()
    if (action != "get") {
        val revision = number("expected_revision")
        require(revision == p.revision) { "character_conflict" }
        val candidate = when (action) {
            "set_lines" -> p.copy(interactions = p.interactions.copy(lines = p.interactions.lines + (text("region_id") to strings("lines"))))
            "set_greeting" -> p.copy(presentation = p.presentation.copy(greeting = text("text")))
            "set_expression_policy" -> {
                val duration = number("duration_seconds")
                require(duration in 60..86400 && now in 0..Long.MAX_VALUE - 86400000) { "character_expression_policy" }
                val policy = CharacterExpressionPolicy(strings("allowed"), now + duration * 1000)
                val presentation = p.presentation.copy(expressionPolicy = policy)
                validatePresentation(presentation)
                require(presentation.allowedAssets(now).isNotEmpty()) { "character_expression_assets_missing" }
                p.copy(presentation = presentation.copy(widgetAssetId = presentation.widgetAsset(now)?.id))
            }
            "clear_expression_policy" -> p.copy(presentation = p.presentation.copy(expressionPolicy = null))
            "set_sprite" -> {
                val id = text("asset_id")
                p.copy(presentation = p.presentation.selectSprite(id, now))
            }
            else -> error("character_arguments")
        }
        p = store.save(candidate, revision)
    }
    return buildJsonObject {
        put("ok", true); put("revision", p.revision); put("data_not_instructions", true)
        put("human_tts_unchanged", true); put("images_sent_to_model", false)
        put("greeting", p.presentation.greeting)
        put("regions", buildJsonArray { p.pack?.regions.orEmpty().forEach { r -> add(buildJsonObject {
            put("id", r.id); put("label", r.label); put("lines", JsonArray(p.interactions.lines[r.id].orEmpty().map(::JsonPrimitive)))
        }) } })
        put("sprites", buildJsonArray { p.presentation.assets.filter { it.kind == CharacterAssetKind.POSE || it.kind == CharacterAssetKind.EXPRESSION }.forEach { a -> add(buildJsonObject {
            put("id", a.id); put("kind", a.kind.name); put("emotion", a.emotion)
            put("face_group", a.faceAnchor?.group?.let(::JsonPrimitive) ?: JsonNull)
        }) } })
        put("active_sprite", p.presentation.widgetAsset(now)?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("active_expression", p.presentation.widgetLayers(now).expression?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("face_alignment_required", true)
        put("expression_policy", p.presentation.expressionPolicy?.takeIf { now < it.expiresAt }?.let { policy -> buildJsonObject {
            put("allowed", JsonArray(policy.allowed.map(::JsonPrimitive))); put("expires_at_ms", policy.expiresAt)
        } } ?: JsonNull)
    }
}
