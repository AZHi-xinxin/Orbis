package me.rerere.rikkahub.data.recovery

/** A diagnostic copy must preserve even malformed UTF-16 code units. Escaping every surrogate
 * inside serialized JSON avoids the UTF-8 encoder's lossy '?' replacement and hash collisions. */
internal fun exactRescueJsonBytes(serializedJson: String): ByteArray = buildString(serializedJson.length) {
    serializedJson.forEach { char ->
        if (char.isSurrogate()) append("\\u").append(char.code.toString(16).padStart(4, '0'))
        else append(char)
    }
}.toByteArray(Charsets.UTF_8)

/** Deliberately narrow: the historical UTF-16 SQLite binding fault consumed a quote after
 * a surrogate. A checksum-valid journal is not sufficient to replace arbitrary damaged text. */
internal fun isKnownQuoteEncodingDamage(damaged: String, complete: String): Boolean {
    if (damaged == complete || damaged.length < 32 || complete.length < 32) return false
    var prefix = 0
    while (prefix < minOf(damaged.length, complete.length) && damaged[prefix] == complete[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(damaged.length, complete.length) - prefix &&
        damaged[damaged.lastIndex - suffix] == complete[complete.lastIndex - suffix]) suffix++
    val bad = damaged.substring(prefix, damaged.length - suffix)
    val good = complete.substring(prefix, complete.length - suffix)
    if (good != "?\"" && good != "\uFFFD\"") return false
    return (bad.length == 2 && Character.isSurrogatePair(bad[0], bad[1])) ||
        (bad.length == 1 && (bad[0] == '\uFFFD' || bad[0].isSurrogate()))
}
