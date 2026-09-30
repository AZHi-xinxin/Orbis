package me.rerere.rikkahub.data.orbis.group

import com.artifex.mupdf.fitz.Document
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Never send a truncated document as if it were whole. Oversize extraction stays a file-only attachment. */
internal fun extractGroupDocument(file: File, name: String, mime: String): String? {
    if (file.length() > 2 * 1024 * 1024) return null
    val extension = groupDocumentExtension(name, mime)
    return try {
        when {
            extension == "pdf" || mime == "application/pdf" -> extractGroupPdf(file)
            extension in setOf("docx", "pptx", "epub") -> extractGroupZipDocument(file, extension)
            else -> null
        }
    } catch (_: Exception) { null }
}

internal fun groupDocumentExtension(name: String, mime: String): String = when (mime) {
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
    "application/epub+zip" -> "epub"
    "application/pdf" -> "pdf"
    else -> name.substringAfterLast('.', "").lowercase()
}

private fun extractGroupPdf(file: File): String? {
    val document = Document.openDocument(file.absolutePath)
    try {
        if (document.needsPassword() || document.countPages() !in 1..40) return null
        val output = StringBuilder()
        for (index in 0 until document.countPages()) {
            val page = document.loadPage(index)
            try {
                val structured = page.toStructuredText()
                try {
                    val text = structured.asText()
                    if (!appendGroupDocumentText(output, text)) return null
                } finally { structured.destroy() }
            } finally { page.destroy() }
        }
        return output.toString().takeIf { it.isNotBlank() }
    } finally { document.destroy() }
}

/** Read selected text entries only, with bounds on count, compressed size and actual inflated bytes. */
internal fun extractGroupZipDocument(file: File, extension: String): String? {
    return ZipFile(file).use { zip ->
    if (zip.size() > 512) return null
    val entries = zip.entries().asSequence().filter { entry ->
        !entry.isDirectory && when (extension) {
            "docx" -> entry.name == "word/document.xml"
            "pptx" -> entry.name.matches(Regex("ppt/slides/slide[0-9]{1,3}\\.xml"))
            "epub" -> entry.name.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm")
            else -> false
        }
    }.toList()
    if (entries.size !in 1..100 || entries.any { it.size !in 0..512 * 1024L }) return null
    var inflated = 0L
    val output = StringBuilder()
    for (entry in entries) {
        val bytes = zip.getInputStream(entry).use { input ->
            val data = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                inflated += n
                if (data.size() + n > 512 * 1024 || inflated > 2 * 1024 * 1024) return null
                data.write(buffer, 0, n)
            }
            data.toByteArray()
        }
        // Jsoup parses data only: it does not resolve XML entities or fetch network resources.
        val parser = if (extension == "epub") Parser.htmlParser() else Parser.xmlParser()
        val document = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", parser)
        document.select("script,style").remove()
        if (!appendGroupDocumentText(output, document.text())) return null
    }
    output.toString().takeIf { it.isNotBlank() }
    }
}

internal fun appendGroupDocumentText(output: StringBuilder, text: String): Boolean {
    if (text.length > GROUP_ATTACHMENT_TEXT_BYTES || output.length + text.length + 1 > GROUP_ATTACHMENT_TEXT_BYTES) return false
    if ((output.toString() + text + "\n").toByteArray(Charsets.UTF_8).size > GROUP_ATTACHMENT_TEXT_BYTES) return false
    output.append(text).append('\n')
    return true
}
