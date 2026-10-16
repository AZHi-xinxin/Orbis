package me.rerere.rikkahub.data.orbis.character

import kotlinx.serialization.Serializable
import java.time.LocalTime
import kotlin.math.min

@Serializable
enum class RoomMode { AUTO, DAY, NIGHT }

enum class RoomLight { MORNING, DAY, DUSK, NIGHT }

fun roomLight(hour: Int, mode: RoomMode): RoomLight {
    require(hour in 0..23)
    return when (mode) {
        RoomMode.DAY -> RoomLight.DAY
        RoomMode.NIGHT -> RoomLight.NIGHT
        RoomMode.AUTO -> when (hour) { in 6..9 -> RoomLight.MORNING; in 10..16 -> RoomLight.DAY; in 17..18 -> RoomLight.DUSK; else -> RoomLight.NIGHT }
    }
}

fun roomLight(mode: RoomMode): RoomLight = roomLight(LocalTime.now().hour, mode)

/** No implicit default: loading, absent owners and invalid routes stay unbound. */
fun resolveCharacterOwner(requested: String?, available: Collection<String>, settingsReady: Boolean): String? =
    requested?.takeIf { settingsReady && CharacterRules.owner(it) && it in available }

@Serializable
data class CharacterRegion(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Portable assets and hit areas only. No assistant, credential, script or network fields. */
@Serializable
data class CharacterPack(
    val format: String = "orbis.character/1",
    val title: String = "我的角色",
    val image: String = "portrait.png",
    val sha256: String,
    val width: Int,
    val height: Int,
    val regions: List<CharacterRegion> = defaultCharacterRegions(),
)

@Serializable
data class CharacterInteractions(val format: String = "orbis.interactions/1", val lines: Map<String, List<String>> = emptyMap())

@Serializable
data class CharacterProfile(
    val version: Int = 1,
    val revision: Long = 0,
    val pack: CharacterPack? = null,
    val interactions: CharacterInteractions = CharacterInteractions(),
    val room: RoomMode = RoomMode.AUTO,
    val motion: Boolean = true,
    val presentation: CharacterPresentation = CharacterPresentation(),
)

fun defaultCharacterRegions() = listOf(
    CharacterRegion("head", "头部", .30f, .03f, .40f, .25f),
    CharacterRegion("shoulder", "肩部", .20f, .30f, .60f, .18f),
    CharacterRegion("hand", "手部", .68f, .62f, .20f, .22f),
)

object CharacterRules {
    const val IMAGE_BYTES = 16 * 1024 * 1024
    const val JSON_BYTES = 256 * 1024
    const val MAX_EDGE = 2048
    fun owner(id: String): Boolean = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    fun text(value: String, max: Int, blank: Boolean = false) = value.length <= max && (blank || value.isNotBlank()) && value.none { it.isISOControl() && it != '\n' }
    fun validate(pack: CharacterPack) {
        require(pack.format == "orbis.character/1" && pack.image == "portrait.png") { "character_format" }
        require(text(pack.title, 60) && pack.sha256.matches(Regex("[a-f0-9]{64}"))) { "character_format" }
        require(pack.width in 1..MAX_EDGE && pack.height in 1..MAX_EDGE) { "character_image" }
        require(pack.regions.size in 1..12 && pack.regions.map { it.id }.distinct().size == pack.regions.size) { "character_regions" }
        pack.regions.forEach { r ->
            require(r.id.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && text(r.label, 24)) { "character_regions" }
            require(listOf(r.x, r.y, r.width, r.height).all { it.isFinite() && it in 0f..1f } &&
                r.width >= .03f && r.height >= .03f && r.x + r.width <= 1.00001f && r.y + r.height <= 1.00001f) { "character_regions" }
        }
    }
    fun validate(profile: CharacterProfile) {
        require(profile.version == 1 && profile.revision in 0 until Long.MAX_VALUE) { "character_format" }
        profile.pack?.let(::validate)
        require(profile.interactions.format == "orbis.interactions/1") { "character_format" }
        val ids = profile.pack?.regions?.map { it.id }.orEmpty()
        require(profile.interactions.lines.size <= 12 && profile.interactions.lines.keys.all { it in ids }) { "character_lines" }
        profile.interactions.lines.values.forEach { lines -> require(lines.size <= 10 && lines.all { text(it, 240) }) { "character_lines" } }
        validatePresentation(profile.presentation)
    }
}

data class CharacterPoint(val x: Float, val y: Float)
data class CharacterFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float): CharacterPoint? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
        val u = (x - left) / width; val v = (y - top) / height
        return if (u in 0f..1f && v in 0f..1f) CharacterPoint(u, v) else null
    }
}

/** The stage and editor share this exact FIT transform, including letterbox exclusion. */
fun characterFrame(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): CharacterFrame {
    require(viewWidth.isFinite() && viewHeight.isFinite() && viewWidth >= 0 && viewHeight >= 0 && imageWidth > 0 && imageHeight > 0)
    val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val w = imageWidth * scale; val h = imageHeight * scale
    return CharacterFrame((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
}

/** Portrait framing: fill a tall room up to 1.35x FIT, never distort or crop the head vertically. */
fun characterStageFrame(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): CharacterFrame {
    val fit = characterFrame(viewWidth, viewHeight, imageWidth, imageHeight)
    if (fit.width <= 0 || fit.height <= 0) return fit
    val factor = min(1.35f, viewHeight / fit.height)
    val w = fit.width * factor; val h = fit.height * factor
    return CharacterFrame((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
}

/** Earlier entries win overlaps; transparent pixels are separately rejected by the renderer. */
fun characterHit(regions: List<CharacterRegion>, point: CharacterPoint): CharacterRegion? = regions.firstOrNull {
    point.x.isFinite() && point.y.isFinite() && point.x >= it.x && point.x <= it.x + it.width && point.y >= it.y && point.y <= it.y + it.height
}

class CharacterTapGate(private val clock: () -> Long) {
    private var last: Long? = null
    fun accept(): Boolean {
        val now = clock()
        if (last?.let { now >= it && now - it < 500 } == true) return false
        last = now
        return true
    }
}
