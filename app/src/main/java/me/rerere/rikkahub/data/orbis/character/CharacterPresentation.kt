package me.rerere.rikkahub.data.orbis.character

import kotlinx.serialization.Serializable

@Serializable enum class CharacterAssetKind { POSE, EXPRESSION, ROOM_DAY, ROOM_NIGHT, CARD }

/** Opaque content-addressed local images; never executable paths or URLs. */
@Serializable data class CharacterAsset(
    val id: String, val kind: CharacterAssetKind, val tag: String,
    val sha256: String, val width: Int, val height: Int,
    val emotion: String = "calm",
    // Display-only framing for busts with a straight waist cutoff. Original PNG stays intact.
    val trimBodyAtCardEdge: Boolean = false,
    val faceAnchor: CharacterFaceAnchor? = null,
    // Explicit opt-in; private/imported art is never recolored by filename or visual guessing.
    val colorModel: String? = null,
)
/** Human calibrated compatible face patches. No automatic identity/landmark inference. */
@Serializable data class CharacterFaceAnchor(val group: String, val x: Float, val y: Float, val width: Float, val height: Float)
@Serializable data class CharacterExpressionPolicy(val allowed: List<String>, val expiresAt: Long)
@Serializable data class CharacterPresentation(
    val assets: List<CharacterAsset> = emptyList(),
    val widgetAssetId: String? = null,
    val scene: String = "living",
    val card: String = "cloud-blue",
    val greeting: String = "",
    val expressionPolicy: CharacterExpressionPolicy? = null,
    val widgetExpressionId: String? = null,
    val colors: CharacterColors = CharacterColors(),
)

val characterEmotions = setOf("smile", "cry", "calm", "think", "angry", "shy", "sleepy", "excited")
val characterSceneNames = listOf("living", "study", "terrace")
val characterCardNames = listOf("cloud-blue", "peach-cream", "sage-mint", "lavender-night", "latte-sand")
private fun assetId(value: String) = value.matches(Regex("[a-z][a-z0-9_-]{0,47}"))

fun validatePresentation(p: CharacterPresentation) {
    validateCharacterColors(p.colors)
    require(p.assets.size <= 64 && p.assets.map { it.id }.distinct().size == p.assets.size) { "character_assets" }
    p.assets.forEach {
        require(it.colorModel == null || it.colorModel == BuiltinCharacter.COLOR_MODEL) { "character_assets" }
        require(assetId(it.id) && assetId(it.tag) && it.emotion in characterEmotions &&
            it.sha256.matches(Regex("[a-f0-9]{64}")) && it.width in 1..4096 &&
            it.height in 1..4096 && it.width.toLong() * it.height <= 2048L * 2048) { "character_assets" }
        it.faceAnchor?.let { f ->
            require(it.kind in widgetKinds && assetId(f.group) &&
                listOf(f.x, f.y, f.width, f.height).all { v -> v.isFinite() && v in 0f..1f } &&
                f.width >= .03f && f.height >= .03f && f.x + f.width <= 1f && f.y + f.height <= 1f) { "character_face_anchor" }
        }
    }
    require(p.widgetAssetId == null || p.assets.any { it.id == p.widgetAssetId && it.kind in widgetKinds }) { "character_assets" }
    require(p.widgetExpressionId == null || p.assets.any { it.id == p.widgetExpressionId && it.kind == CharacterAssetKind.EXPRESSION }) { "character_assets" }
    require(assetId(p.scene) && assetId(p.card) && CharacterRules.text(p.greeting, 120, blank = true)) { "character_assets" }
    p.expressionPolicy?.let {
        require(it.allowed.isNotEmpty() && it.allowed.size <= characterEmotions.size &&
            it.allowed.distinct().size == it.allowed.size && it.allowed.all(characterEmotions::contains) &&
            it.expiresAt > 0) { "character_expression_policy" }
    }
}
private val widgetKinds = setOf(CharacterAssetKind.POSE, CharacterAssetKind.EXPRESSION)

fun CharacterPresentation.allowedAssets(now: Long, kind: CharacterAssetKind? = null): List<CharacterAsset> {
    val allowed = expressionPolicy?.takeIf { now < it.expiresAt }?.allowed
    return assets.filter { it.kind in widgetKinds && (kind == null || it.kind == kind) && (allowed == null || it.emotion in allowed) }
}
fun CharacterPresentation.widgetAsset(now: Long): CharacterAsset? {
    return widgetLayers(now).pose
}
data class CharacterWidgetLayers(val pose: CharacterAsset?, val expression: CharacterAsset?)
fun CharacterPresentation.widgetLayers(now: Long): CharacterWidgetLayers {
    val requested = assets.firstOrNull { it.id == widgetAssetId && it.kind in widgetKinds }
        ?: assets.firstOrNull { it.kind == CharacterAssetKind.POSE }
        ?: allowedAssets(now).firstOrNull()
    if (requested != null) {
        val expressions = compatibleExpressions(requested, now)
        val expression = expressions.firstOrNull { it.id == widgetExpressionId }
            ?: if (expressionPolicy?.takeIf { now < it.expiresAt }?.allowed?.contains(requested.emotion) == false)
                expressions.firstOrNull { it.emotion == "calm" } ?: expressions.firstOrNull() else null
        if (expression != null) return CharacterWidgetLayers(requested, expression)
        if (allowedAssets(now).any { it.id == requested.id }) return CharacterWidgetLayers(requested, null)
    }
    val candidates = allowedAssets(now)
    return CharacterWidgetLayers(candidates.firstOrNull { it.emotion == "calm" } ?: candidates.firstOrNull(), null)
}
fun CharacterPresentation.compatibleExpressions(pose: CharacterAsset, now: Long): List<CharacterAsset> {
    val anchor = pose.faceAnchor ?: return emptyList()
    return allowedAssets(now, CharacterAssetKind.EXPRESSION).filter { it.faceAnchor?.group == anchor.group }
}
fun CharacterPresentation.selectSprite(id: String, now: Long): CharacterPresentation {
    val selected = requireNotNull(assets.firstOrNull { it.id == id && it.kind in widgetKinds }) { "character_assets" }
    if (selected.kind == CharacterAssetKind.EXPRESSION) {
        val pose = widgetAsset(now) ?: error("character_face_anchor")
        require(compatibleExpressions(pose, now).any { it.id == id }) { "character_face_anchor" }
        return copy(widgetAssetId = pose.id, widgetExpressionId = id)
    }
    val candidate = copy(widgetAssetId = id)
    require(candidate.widgetLayers(now).pose?.id == id) { "character_expression_assets_missing" }
    return candidate
}
fun CharacterPresentation.cycle(kind: CharacterAssetKind, now: Long): CharacterPresentation {
    val pose = widgetAsset(now)
    val candidates = if (kind == CharacterAssetKind.EXPRESSION) pose?.let { compatibleExpressions(it, now) }.orEmpty()
        else assets.filter { it.kind == CharacterAssetKind.POSE && copy(widgetAssetId = it.id).widgetLayers(now).pose?.id == it.id }
    if (candidates.isEmpty()) return this
    val index = candidates.indexOfFirst { it.id == if (kind == CharacterAssetKind.EXPRESSION) widgetLayers(now).expression?.id else pose?.id }
    return selectSprite(candidates[(index + 1) % candidates.size].id, now)
}

/** Linear sunrise/sunset blend, based only on local device time (no location permission). */
fun characterNightFraction(hour: Int, minute: Int, mode: RoomMode): Float {
    require(hour in 0..23 && minute in 0..59)
    if (mode == RoomMode.DAY) return 0f
    if (mode == RoomMode.NIGHT) return 1f
    val t = hour + minute / 60f
    return when { t < 6 -> 1f; t < 8 -> (8 - t) / 2; t < 17 -> 0f; t < 19 -> (t - 17) / 2; else -> 1f }
}

fun CharacterProfile.assetHashes(): Set<String> = presentation.assets.map { it.sha256 }.toSet() + listOfNotNull(pack?.sha256)
