package me.rerere.rikkahub.data.recovery

import java.io.IOException
import java.io.InputStream

/** Checked before recursive JSON decoding; strings/escapes are not treated as structure. */
internal class EmergencyJsonShapeGuard {
    private val kind = IntArray(32)
    private val commas = IntArray(32)
    private var depth = 0
    private var quoted = false
    private var escaped = false

    fun accept(value: Int) {
        if (quoted) {
            if (escaped) escaped = false
            else if (value == '\\'.code) escaped = true
            else if (value == '"'.code) quoted = false
            return
        }
        when (value) {
            '"'.code -> quoted = true
            '['.code, '{'.code -> {
                if (depth == kind.size) throw IOException("Emergency JSON is too deeply nested")
                kind[depth] = value
                commas[depth] = 0
                depth++
            }
            ']'.code, '}'.code -> {
                if (depth == 0) throw IOException("Invalid emergency JSON nesting")
                val expected = if (value == ']'.code) '['.code else '{'.code
                if (kind[--depth] != expected) throw IOException("Invalid emergency JSON nesting")
            }
            ','.code -> if (depth > 0 && kind[depth - 1] == '['.code) {
                if (++commas[depth - 1] >= 100_000) throw IOException("Emergency JSON contains too many entries")
            }
        }
    }
}

internal fun requireEmergencyJsonShape(text: String) {
    if (text.length > 16 * 1024 * 1024) throw IOException("Emergency JSON is too large")
    val guard = EmergencyJsonShapeGuard()
    text.forEach { guard.accept(it.code) }
}

/** Scans each input block before handing any of it to the decoder, with no recursive allocation. */
internal class EmergencyJsonInput(private val source: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L
    private val shape = EmergencyJsonShapeGuard()

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) {
            if (++count > limit) throw IOException("Emergency JSON is too large")
            shape.accept(value)
        }
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = source.read(bytes, offset, minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt())
        if (read > 0) {
            count += read
            if (count > limit) throw IOException("Emergency JSON is too large")
            for (index in offset until offset + read) shape.accept(bytes[index].toInt() and 0xff)
        }
        return read
    }
}
