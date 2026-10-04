package me.rerere.rikkahub.data.orbis.gallery

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal fun galleryPreview(kind: String, source: String): String {
    val text = when (kind) {
        "questionnaire" -> parseGalleryQuestionnaire(source).let { "${it.questions.size} 道题 · " + (it.description.ifBlank { it.questions.first().text }) }
        "html" -> Jsoup.parse(source).also { it.select("script,style,noscript,template").remove() }.text()
        else -> source
    }.replace(Regex("\\s+"), " ").trim()
    val end = text.offsetByCodePoints(0, minOf(120, text.codePointCount(0, text.length)))
    return text.substring(0, end)
}

/** Normalize only external text files; persisted bodies and their hashes remain byte-for-byte intact. */
internal fun galleryImportText(source: String): String = source.removePrefix("\uFEFF")

/** Parsing is offline. Scripts, pages, links and form actions never run in the question importer. */
internal fun extractGalleryQuestions(rawSource: String, respondent: String): GalleryQuestionnaire {
    val source = galleryImportText(rawSource)
    require(source.toByteArray().size <= GalleryLimits.BODY_BYTES) { "gallery_file_too_large" }
    require(respondent in setOf("human", "ai"))
    if (source.trimStart().startsWith("{")) return parseGalleryQuestionnaire(source).copy(respondent = respondent)
    // Human-selected text/Markdown has no executable renderer. Only explicit numbered/question
    // lines are promoted, and the user must inspect the native preview before publication.
    if (!Regex("(?i)<(?:!doctype|html|script|form|input|textarea|select|iframe|body)\\b").containsMatchIn(source)) {
        val textQuestions = extractGalleryTextQuestions(source)
        if (textQuestions.isNotEmpty()) return GalleryQuestionnaire(respondent = respondent,
            description = "由纯文本 / Markdown 提取。所有题目暂按自由文本填写，选项文字保留在题目中；请先核对，不会猜选项或答案。", questions = textQuestions)
    }
    val doc = Jsoup.parse(source)
    doc.select("script,style,noscript,iframe,object,embed,template").remove()
    val grouped = mutableSetOf<String>()
    val questions = mutableListOf<GalleryQuestion>()
    fun label(field: Element): String {
        val linked = field.id().takeIf { it.isNotBlank() }?.let { id -> doc.select("label").firstOrNull { it.attr("for") == id } }
        return (linked?.text() ?: field.closest("label")?.text() ?: field.attr("aria-label").ifBlank { field.attr("placeholder") }).trim()
    }
    for (field in doc.select("input,textarea,select")) {
        if (field.hasAttr("disabled") || field.attr("type").lowercase() in setOf("hidden", "password", "file", "submit", "reset", "button", "image")) continue
        val inputType = field.attr("type").lowercase()
        val name = field.attr("name")
        val choice = inputType in setOf("radio", "checkbox") && name.isNotBlank()
        if (choice && !grouped.add(name)) continue
        val options = when {
            choice -> doc.select("input").filter { it.attr("name") == name && it.attr("type").lowercase() == inputType && !it.hasAttr("disabled") }.map { label(it).ifBlank { it.attr("value") } }.filter { it.isNotBlank() }.distinct()
            field.tagName() == "select" -> field.select("option").filter { !it.hasAttr("disabled") && it.text().isNotBlank() }.map { it.text() }.distinct()
            else -> emptyList()
        }
        val prompt = if (choice) field.closest("fieldset")?.selectFirst("legend")?.text()
            ?: field.attr("aria-label").ifBlank { name } else label(field)
        if (prompt.isNullOrBlank()) continue
        require(prompt.length <= 4000 && options.size <= 50 && options.all { it.length <= 500 }) { "gallery_questionnaire_invalid" }
        val type = if (options.size < 2) "text" else if (inputType == "checkbox" || field.hasAttr("multiple")) "multiple" else "single"
        questions += GalleryQuestion("q${questions.size + 1}", prompt, type, if (type == "text") emptyList() else options, field.hasAttr("required"))
        require(questions.size <= 200) { "gallery_questionnaire_too_large" }
    }
    require(questions.isNotEmpty()) { "gallery_no_questions" }
    return GalleryQuestionnaire(respondent = respondent, description = "由外部 HTML 的静态表单提取，请先核对题目；未执行原网页脚本或提交原网站。", questions = questions)
}

internal fun extractGalleryTextQuestions(source: String): List<GalleryQuestion> {
    val lines = source.replace(Regex("(?s)```.*?```"), "").lineSequence()
    val numbered = Regex("^\\s*(?:#{1,6}\\s*)?(?:\\d{1,3}[.、)）]|[一二三四五六七八九十]{1,3}[、.])\\s*(\\S.*)$")
    val option = Regex("^\\s*(?:[-*+]\\s+|[A-Za-z][.、)）]\\s*)(\\S.*)$")
    val questions = mutableListOf<String>()
    for (line in lines) {
        val clean = line.trim()
        if (clean.isEmpty()) continue
        val named = numbered.matchEntire(clean)?.groupValues?.get(1)
        val question = named ?: clean.takeIf { it.endsWith('?') || it.endsWith('？') }?.removePrefix("- ")?.removePrefix("* ")
        if (question != null) {
            require(question.length <= 4000 && questions.size < 200) { "gallery_questionnaire_too_large" }
            questions += question
        } else if (questions.isNotEmpty() && option.matches(clean)) {
            val combined = questions.last() + "\n" + clean
            require(combined.length <= 4000) { "gallery_questionnaire_too_large" }
            questions[questions.lastIndex] = combined
        }
    }
    return questions.mapIndexed { i, text -> GalleryQuestion("q${i + 1}", text) }
}

internal fun galleryPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return if (bytes.size == 4) {
        val a = bytes[0]; val b = bytes[1]
        a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
            !(a == 192 && b == 0) && !(a == 198 && b in 18..19)
    } else bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
        !(bytes.take(4) == listOf(0x20, 0x01, 0, 0)) // Exclude 6to4/Teredo as well as local/mapped routes.
}

internal fun galleryQuestionnaireUrl(raw: String): okhttp3.HttpUrl {
    require(raw.length <= 2048) { "gallery_url_blocked" }
    val url = requireNotNull(raw.toHttpUrlOrNull()) { "gallery_url_blocked" }
    require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "gallery_url_blocked" }
    require(!url.host.endsWith(".local") && url.host != "localhost") { "gallery_url_blocked" }
    if (url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) require(galleryPublicAddress(InetAddress.getByName(url.host))) { "gallery_url_blocked" }
    return url
}

/** A user-triggered fetch. No cookies, app credentials, proxy, redirects or subsequent page resources. */
internal fun fetchGalleryQuestionnaire(rawUrl: String): String {
    val url = galleryQuestionnaireUrl(rawUrl)
    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .dns { hostname -> Dns.SYSTEM.lookup(hostname).also { addresses -> require(addresses.isNotEmpty() && addresses.all(::galleryPublicAddress)) { "gallery_url_blocked" } } }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    return client.newCall(Request.Builder().url(url).header("Accept", "text/html, application/json, text/plain").build()).execute().use { response ->
        require(response.isSuccessful) { "gallery_url_unavailable" }
        val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
        require(type in setOf("text/html", "application/xhtml+xml", "application/json", "text/plain")) { "gallery_url_unavailable" }
        val charset = response.body.contentType()?.charset(Charsets.UTF_8)
        require(charset == Charsets.UTF_8) { "gallery_encoding_unsupported" }
        galleryImportText(galleryUtf8(response.body.byteStream().use { it.galleryReadBounded(GalleryLimits.BODY_BYTES) }))
    }
}
