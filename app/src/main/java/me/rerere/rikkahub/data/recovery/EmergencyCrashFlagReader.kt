package me.rerere.rikkahub.data.recovery

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_LAUNCHER_CRASH_XML_BYTES = 1_048_576

/**
 * Read only, fresh on every inspection, and called ONLY while the business fence is held.
 * A cached :launcher process must not trust its SharedPreferences cache after another process
 * crashes or acknowledges recovery. Honor Android's .bak precedence without renaming/restoring
 * either file. Never parse other preferences, emit the report, or rewrite its crash flag.
 */
internal fun readEmergencyLauncherCrashFlag(sharedPreferencesDirectory: File): Boolean {
    fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
    val parent = attributes(sharedPreferencesDirectory) ?: return false
    check(parent.isDirectory && !parent.isSymbolicLink) { "crash_flag_directory_unavailable" }
    val backup = File(sharedPreferencesDirectory, "crash_handler.xml.bak")
    val file = if (attributes(backup) != null) backup else File(sharedPreferencesDirectory, "crash_handler.xml")
    val metadata = attributes(file) ?: return false
    check(metadata.isRegularFile && !metadata.isSymbolicLink && metadata.size() <= MAX_LAUNCHER_CRASH_XML_BYTES) {
        "crash_flag_file_unavailable"
    }
    val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && count <= MAX_LAUNCHER_CRASH_XML_BYTES - output.size()) { "crash_flag_size_limit" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    check(bytes.size <= MAX_LAUNCHER_CRASH_XML_BYTES) { "crash_flag_size_limit" }
    val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    check('\u0000' !in xml && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
        "crash_flag_declaration_rejected"
    }
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        // Some Android-compatible pull parsers cannot set this optional feature at all.
        // Accept that only when they report it disabled; declarations were already rejected
        // in the bounded, strictly decoded source and are also refused as parser tokens.
        try { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {
            check(!getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "crash_flag_parser_unsafe" }
        }
        setInput(StringReader(xml))
    }
    var rootSeen = false
    var rootClosed = false
    var result: Boolean? = null
    var token = parser.eventType
    while (token != XmlPullParser.END_DOCUMENT) {
        when (token) {
            XmlPullParser.DOCDECL -> error("crash_flag_declaration_rejected")
            XmlPullParser.START_TAG -> {
                check(!rootClosed) { "crash_flag_structure_invalid" }
                check(parser.depth <= 2) { "crash_flag_structure_invalid" }
                if (parser.depth == 1) {
                    check(!rootSeen && parser.name == "map") { "crash_flag_structure_invalid" }
                    rootSeen = true
                } else if (parser.getAttributeValue(null, "name") == "crashed") {
                    check(parser.name == "boolean" && result == null) { "crash_flag_type_invalid" }
                    result = when (parser.getAttributeValue(null, "value")) {
                        "true" -> true
                        "false" -> false
                        else -> error("crash_flag_value_invalid")
                    }
                }
            }
            XmlPullParser.END_TAG -> if (parser.depth == 1) {
                check(parser.name == "map" && rootSeen) { "crash_flag_structure_invalid" }
                rootClosed = true
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (parser.depth == 0) {
                check(parser.text.isNullOrBlank()) { "crash_flag_structure_invalid" }
            }
        }
        token = parser.nextToken()
    }
    check(rootSeen && rootClosed) { "crash_flag_structure_invalid" }
    return result ?: false
}
