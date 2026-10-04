package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.util.Locale

/** Logical Linux names are not ZIP entry names or host filesystem paths. Never URL-decode them. */
internal object EmergencyArchivePaths {
    fun isRelative(path: String, allowColon: Boolean = true): Boolean {
        if (path.isEmpty() || '\\' in path || '\u0000' in path || (!allowColon && ':' in path) ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        // Java's UTF-8 encoder otherwise replaces unpaired surrogates with '?'. Refuse them
        // before hashing so two different manifest paths cannot alias the same payload ID.
        var index = 0
        while (index < path.length) {
            val char = path[index++]
            if (Character.isHighSurrogate(char)) {
                if (index == path.length || !Character.isLowSurrogate(path[index++])) return false
            } else if (Character.isLowSurrogate(char)) return false
        }
        return true
    }

    fun requireMaterializable(paths: Collection<String>, windows: Boolean = File.separatorChar == '\\',
                              linux: Boolean = System.getProperty("os.name").orEmpty().equals("Linux", true)) {
        if (paths.any { !isRelative(it) }) throw IOException("emergency_archive_path_invalid")
        // A colon is an ordinary Linux name, but is an NTFS alternate stream on Windows.
        // Verify is portable; materialization of such names is explicitly Linux/Android only.
        if (!linux && paths.any { ':' in it }) throw IOException("emergency_archive_target_platform_requires_linux")
        if (!windows) return
        val folded = HashSet<String>()
        val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])")
        paths.forEach { path ->
            if (path.split('/').any { segment ->
                    segment.endsWith('.') || segment.endsWith(' ') ||
                        segment.any { it < ' ' || it in "<>:\"|?*" } ||
                        reserved.matches(segment.substringBefore('.'))
                } || !folded.add(path.lowercase(Locale.ROOT))) {
                throw IOException("emergency_archive_target_platform_unrepresentable")
            }
        }
    }

    fun isMaterializable(path: String): Boolean = try {
        requireMaterializable(listOf(path))
        true
    } catch (_: IOException) {
        false
    }
}
