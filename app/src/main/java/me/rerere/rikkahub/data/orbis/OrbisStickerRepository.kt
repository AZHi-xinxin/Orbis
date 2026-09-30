package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.zip.CRC32

object OrbisStickerLimits {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_DIMENSION = 4096
    const val MAX_TAGS = 12
    const val MAX_TAG_LENGTH = 40
    const val MAX_TAG_TOTAL_LENGTH = 240
    const val MAX_STICKERS = 1000
    const val MAX_TOTAL_IMAGE_BYTES = 256L * 1024 * 1024
    const val MAX_MANIFEST_CHARS = 4 * 1024 * 1024
    const val MAX_NUMBER = 999999
}

/** Only fixed public codes escape this boundary; never attach private paths/provider messages. */
class OrbisStickerException(val code: String) : IllegalStateException(code)

internal fun stickerCheck(condition: Boolean, code: String) {
    if (!condition) throw OrbisStickerException(code)
}

@Serializable
data class OrbisSticker(
    val id: String,
    val tags: List<String>,
    val format: String,
    val width: Int,
    val height: Int,
    val byteSize: Int,
    val sha256: String,
    val createdAt: Long,
) {
    val reference: String get() = "(表情包:$id)"
}

@Serializable
data class OrbisStickerState(
    val version: Int = 1,
    val nextNumber: Int = 1,
    val stickers: List<OrbisSticker> = emptyList(),
)

/** A validated snapshot for an explicit human send, never exposed by the metadata tool. */
data class OrbisStickerImage(val sticker: OrbisSticker, val bytes: ByteArray)

/** Images are exclusive, immutable objects. A write exception is an indeterminate commit. */
interface OrbisStickerStorage {
    fun readManifest(): String?
    fun writeManifest(text: String)
    fun writeImageExclusive(id: String, bytes: ByteArray)
    fun readImage(id: String): ByteArray
    /** Includes unfinished image files, so loss/rollback of the manifest cannot recycle IDs. */
    fun largestImageNumber(): Int
    fun totalImageBytes(): Long
}

/** Pure JVM image-envelope checks; Android additionally performs an actual bounded codec decode. */
object OrbisStickerImages {
    data class Info(val format: String, val width: Int, val height: Int)
    fun inspect(bytes: ByteArray): Info {
        stickerCheck(bytes.size in 14..OrbisStickerLimits.MAX_IMAGE_BYTES, "sticker_image_size")
        val info = when {
            bytes.size >= 33 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> png(bytes)
            u8(bytes, 0) == 255 && u8(bytes, 1) == 216 -> jpeg(bytes)
            ascii(bytes, 0, 6) in setOf("GIF87a", "GIF89a") -> {
                stickerCheck(u8(bytes, bytes.lastIndex) == 0x3b, "sticker_image_invalid")
                Info("gif", le16(bytes, 6), le16(bytes, 8))
            }
            ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" -> webp(bytes)
            else -> throw OrbisStickerException("sticker_image_format")
        }
        stickerCheck(info.width in 1..OrbisStickerLimits.MAX_DIMENSION && info.height in 1..OrbisStickerLimits.MAX_DIMENSION,
            "sticker_image_dimensions")
        return info
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun u8(b: ByteArray, n: Int) = b[n].toInt() and 255
    private fun le16(b: ByteArray, n: Int) = u8(b, n) or (u8(b, n + 1) shl 8)
    private fun be16(b: ByteArray, n: Int) = (u8(b, n) shl 8) or u8(b, n + 1)
    private fun uint32(b: ByteArray, n: Int, little: Boolean): Long = (0..3).fold(0L) { result, i ->
        result or (u8(b, n + i).toLong() shl (if (little) i * 8 else (3 - i) * 8))
    }
    private fun ascii(b: ByteArray, n: Int, count: Int): String = String(b, n, count, Charsets.US_ASCII)
    private fun png(b: ByteArray): Info {
        var offset = 8
        var info: Info? = null
        var data = false
        var ended = false
        while (offset + 12 <= b.size) {
            val length = uint32(b, offset, false)
            stickerCheck(length <= b.size - offset - 12, "sticker_image_invalid")
            val count = length.toInt()
            val kind = ascii(b, offset + 4, 4)
            val crc = CRC32().also { it.update(b, offset + 4, count + 4) }.value
            stickerCheck(crc == uint32(b, offset + 8 + count, false), "sticker_image_invalid")
            if (offset == 8) {
                stickerCheck(kind == "IHDR" && count == 13, "sticker_image_invalid")
                val width = uint32(b, offset + 8, false)
                val height = uint32(b, offset + 12, false)
                stickerCheck(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE, "sticker_image_dimensions")
                info = Info("png", width.toInt(), height.toInt())
            } else stickerCheck(kind != "IHDR", "sticker_image_invalid")
            if (kind == "IDAT") data = true
            offset += count + 12
            if (kind == "IEND") {
                stickerCheck(count == 0 && offset == b.size && data, "sticker_image_invalid")
                ended = true
                break
            }
        }
        stickerCheck(ended && info != null, "sticker_image_invalid")
        return info!!
    }
    private fun jpeg(b: ByteArray): Info {
        stickerCheck(u8(b, b.size - 2) == 255 && u8(b, b.size - 1) == 217, "sticker_image_invalid")
        var offset = 2
        var info: Info? = null
        while (offset + 4 <= b.size) {
            stickerCheck(u8(b, offset) == 255, "sticker_image_invalid")
            while (offset < b.size && u8(b, offset) == 255) offset++
            stickerCheck(offset + 3 <= b.size, "sticker_image_invalid")
            val marker = u8(b, offset++)
            val length = be16(b, offset)
            stickerCheck(length >= 2 && length <= b.size - offset, "sticker_image_invalid")
            if (marker in setOf(0xc0, 0xc1, 0xc2)) {
                stickerCheck(length >= 8 && info == null, "sticker_image_invalid")
                info = Info("jpeg", be16(b, offset + 5), be16(b, offset + 3))
            }
            if (marker == 0xda) {
                stickerCheck(info != null && offset + length < b.size - 2, "sticker_image_invalid")
                return info!!
            }
            offset += length
        }
        throw OrbisStickerException("sticker_image_invalid")
    }
    private fun webp(b: ByteArray): Info {
        stickerCheck(uint32(b, 4, true) == b.size.toLong() - 8, "sticker_image_invalid")
        var offset = 12
        var info: Info? = null
        while (offset + 8 <= b.size) {
            val kind = ascii(b, offset, 4)
            val length = uint32(b, offset + 4, true)
            stickerCheck(length <= b.size - offset - 8, "sticker_image_invalid")
            val start = offset + 8
            when (kind) {
                "VP8X" -> {
                    stickerCheck(length == 10L, "sticker_image_invalid")
                    fun dim(n: Int) = 1 + u8(b, n) + (u8(b, n + 1) shl 8) + (u8(b, n + 2) shl 16)
                    info = Info("webp", dim(start + 4), dim(start + 7))
                }
                "VP8L" -> {
                    stickerCheck(length >= 5 && u8(b, start) == 0x2f, "sticker_image_invalid")
                    val bits = uint32(b, start + 1, true)
                    info = info ?: Info("webp", 1 + (bits and 0x3fff).toInt(), 1 + ((bits shr 14) and 0x3fff).toInt())
                }
                "VP8 " -> {
                    stickerCheck(length >= 10 && u8(b, start + 3) == 0x9d && u8(b, start + 4) == 1 && u8(b, start + 5) == 0x2a,
                        "sticker_image_invalid")
                    info = info ?: Info("webp", le16(b, start + 6) and 0x3fff, le16(b, start + 8) and 0x3fff)
                }
            }
            offset += 8 + length.toInt() + (length.toInt() and 1)
        }
        stickerCheck(offset == b.size && info != null, "sticker_image_invalid")
        return info!!
    }
}

/** Reservations precede image creation; failed imports burn a number, never reuse a published ID. */
class OrbisStickerRepository(private val storage: OrbisStickerStorage, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { encodeDefaults = true }
    private var manifestExpected = false
    private val mutableState = MutableStateFlow(safe { load() })
    val state = mutableState.asStateFlow()
    private val mutableBlocked = MutableStateFlow(false)
    val writeBlocked = mutableBlocked.asStateFlow()

    @Synchronized fun readSnapshot(): OrbisStickerState = safe {
        ensureAvailable()
        state.value.also(::validateMetadata)
    }
    @Synchronized fun reloadFromStorage() = safe {
        mutableBlocked.value = true
        val restored = load()
        mutableState.value = restored
        mutableBlocked.value = false
    }
    @Synchronized fun importImage(bytes: ByteArray, tags: List<String>): OrbisSticker = safe {
        ensureAvailable()
        stickerCheck(bytes.size in 14..OrbisStickerLimits.MAX_IMAGE_BYTES, "sticker_image_size")
        val image = bytes.copyOf() // A caller cannot mutate an in-flight import.
        val info = OrbisStickerImages.inspect(image)
        val normalized = normalizeTags(tags)
        val before = state.value
        stickerCheck(before.stickers.size < OrbisStickerLimits.MAX_STICKERS && before.nextNumber <= OrbisStickerLimits.MAX_NUMBER,
            "sticker_library_full")
        stickerCheck(storage.totalImageBytes() + image.size <= OrbisStickerLimits.MAX_TOTAL_IMAGE_BYTES, "sticker_library_full")
        val entry = OrbisSticker(idForNumber(before.nextNumber), normalized, info.format, info.width, info.height,
            image.size, OrbisStickerImages.sha256(image), now().coerceAtLeast(0))
        commit(before.copy(nextNumber = before.nextNumber + 1))
        try { storage.writeImageExclusive(entry.id, image) }
        catch (_: Throwable) { mutableBlocked.value = true; throw OrbisStickerException("sticker_storage_reload_required") }
        commit(state.value.copy(stickers = state.value.stickers + entry))
        entry
    }
    @Synchronized fun updateTags(id: String, tags: List<String>) = safe {
        ensureAvailable()
        val normalized = normalizeTags(tags)
        stickerCheck(validId(id), "sticker_invalid_id")
        stickerCheck(state.value.stickers.any { it.id == id }, "sticker_not_found")
        commit(state.value.copy(stickers = state.value.stickers.map { if (it.id == id) it.copy(tags = normalized) else it }))
    }
    @Synchronized fun imageForSend(id: String): OrbisStickerImage = safe {
        ensureAvailable()
        stickerCheck(validId(id), "sticker_invalid_id")
        val entry = state.value.stickers.firstOrNull { it.id == id }
            ?: throw OrbisStickerException("sticker_not_found")
        val bytes = storage.readImage(id).copyOf()
        val info = OrbisStickerImages.inspect(bytes)
        stickerCheck(bytes.size == entry.byteSize && OrbisStickerImages.sha256(bytes) == entry.sha256 &&
            info == OrbisStickerImages.Info(entry.format, entry.width, entry.height), "sticker_storage_invalid")
        OrbisStickerImage(entry, bytes)
    }
    private fun ensureAvailable() = stickerCheck(!mutableBlocked.value, "sticker_storage_reload_required")
    private fun commit(value: OrbisStickerState) {
        validateMetadata(value)
        val text = json.encodeToString(value)
        stickerCheck(text.length <= OrbisStickerLimits.MAX_MANIFEST_CHARS, "sticker_library_full")
        try { storage.writeManifest(text) }
        catch (_: Throwable) { mutableBlocked.value = true; throw OrbisStickerException("sticker_storage_reload_required") }
        manifestExpected = true
        mutableState.value = value
    }
    private fun load(): OrbisStickerState {
        val text = storage.readManifest()
        val largestImage = storage.largestImageNumber()
        stickerCheck(largestImage in 0..OrbisStickerLimits.MAX_NUMBER, "sticker_storage_invalid")
        stickerCheck(storage.totalImageBytes() in 0..OrbisStickerLimits.MAX_TOTAL_IMAGE_BYTES, "sticker_storage_invalid")
        if (text == null) {
            stickerCheck(!manifestExpected && largestImage == 0, "sticker_storage_missing")
            return OrbisStickerState()
        }
        stickerCheck(text.length <= OrbisStickerLimits.MAX_MANIFEST_CHARS, "sticker_storage_invalid")
        val loaded = json.decodeFromString<OrbisStickerState>(text)
        validateMetadata(loaded)
        stickerCheck(loaded.nextNumber > largestImage, "sticker_storage_invalid")
        loaded.stickers.forEach { entry ->
            val bytes = storage.readImage(entry.id)
            val info = OrbisStickerImages.inspect(bytes)
            stickerCheck(bytes.size == entry.byteSize && OrbisStickerImages.sha256(bytes) == entry.sha256 &&
                info == OrbisStickerImages.Info(entry.format, entry.width, entry.height), "sticker_storage_invalid")
        }
        manifestExpected = true
        return loaded
    }
    companion object {
        fun validId(id: String) = id.matches(Regex("st[0-9]{6}")) && id != "st000000"
        fun idForNumber(number: Int): String {
            stickerCheck(number in 1..OrbisStickerLimits.MAX_NUMBER, "sticker_invalid_id")
            return "st" + number.toString().padStart(6, '0')
        }
        fun normalizeTags(tags: List<String>): List<String> {
            stickerCheck(tags.size in 1..OrbisStickerLimits.MAX_TAGS, "sticker_invalid_tags")
            val normalized = tags.map { it.trim() }
            stickerCheck(normalized.all { tag -> tag.length in 1..OrbisStickerLimits.MAX_TAG_LENGTH &&
                tag.none { Character.isISOControl(it) || it in listOf('\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069') } } &&
                normalized.sumOf { it.length } <= OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH, "sticker_invalid_tags")
            return normalized.distinct().toList()
        }
        fun validateMetadata(value: OrbisStickerState) {
            stickerCheck(value.version == 1 && value.nextNumber in 1..OrbisStickerLimits.MAX_NUMBER + 1 &&
                value.stickers.size <= OrbisStickerLimits.MAX_STICKERS, "sticker_storage_invalid")
            stickerCheck(value.stickers.map { it.id }.distinct().size == value.stickers.size, "sticker_storage_invalid")
            stickerCheck(value.stickers.sumOf { it.byteSize.toLong() } <= OrbisStickerLimits.MAX_TOTAL_IMAGE_BYTES, "sticker_storage_invalid")
            value.stickers.forEach { entry ->
                stickerCheck(validId(entry.id) && entry.id.substring(2).toInt() < value.nextNumber && entry.createdAt >= 0 &&
                    entry.format in listOf("png", "jpeg", "webp", "gif") && entry.width in 1..OrbisStickerLimits.MAX_DIMENSION &&
                    entry.height in 1..OrbisStickerLimits.MAX_DIMENSION && entry.byteSize in 14..OrbisStickerLimits.MAX_IMAGE_BYTES &&
                    entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.tags == normalizeTags(entry.tags), "sticker_storage_invalid")
            }
        }
        private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (error: OrbisStickerException) { throw error }
        catch (_: Exception) { throw OrbisStickerException("sticker_storage_unavailable") }
    }
}
