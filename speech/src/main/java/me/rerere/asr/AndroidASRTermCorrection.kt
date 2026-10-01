package me.rerere.asr

import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.util.Locale

/** No network/model or bundled name dictionary. Older systems keep the exact-alias path. */
fun isAsrPhoneticCorrectionAvailable(): Boolean = Build.VERSION.SDK_INT >= 29 && Api29Pronunciation.available

fun asrDevicePronunciation(character: Char): String? =
    if (Build.VERSION.SDK_INT >= 29) Api29Pronunciation.read(character) else null

fun correctDeviceAsrTranscript(raw: String, settings: ASRTermCorrectionSettings): ASRCorrectionResult =
    correctAsrTranscript(raw, settings, if (settings.enabled && settings.rules.any { it.phoneticMatch } &&
        isAsrPhoneticCorrectionAvailable()) ::asrDevicePronunciation else null)

@RequiresApi(29)
private object Api29Pronunciation {
    private val reader by lazy { runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull() }
    private val cache = linkedMapOf<Char, String?>()
    val available: Boolean get() = reader != null

    @Synchronized
    fun read(character: Char): String? {
        if (character !in '\u3400'..'\u9fff') return null
        if (cache.containsKey(character)) return cache[character]
        val result = runCatching { reader?.transliterate(character.toString()) }.getOrNull()?.let { latin ->
            // Remove only Mandarin tone marks. Keep ü distinct from u, and syllable boundaries
            // are retained by the pure policy's List<String> key (never concatenate initials).
            val decomposed = Normalizer.normalize(latin, Normalizer.Form.NFD)
            Normalizer.normalize(decomposed.filterNot { it in setOf('\u0300', '\u0301', '\u0304', '\u030c') },
                Normalizer.Form.NFC).lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-zü]+")) }
        }
        if (cache.size >= 1024) cache.remove(cache.keys.first())
        cache[character] = result
        return result
    }
}
