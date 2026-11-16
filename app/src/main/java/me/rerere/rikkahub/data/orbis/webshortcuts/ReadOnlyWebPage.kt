package me.rerere.rikkahub.data.orbis.webshortcuts

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.concurrent.TimeUnit

/** Standalone client: no app interceptor, MCP headers, browser cookies or model credentials. */
internal object ReadOnlyWebPage {
    val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(25,TimeUnit.SECONDS).build()
    fun load(url: String): String = client.newCall(Request.Builder().url(shortcutUrl(url)).get().build()).execute().use { response ->
        check(response.isSuccessful) { "网页返回 HTTP ${response.code}" }
        val body = checkNotNull(response.body)
        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
        require(mime in setOf("text/html","application/xhtml+xml","text/plain")) { "此链接不是可阅读网页" }
        val bytes = body.byteStream().use { it.readBytesBounded(8 * 1024 * 1024) }
        val text = bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        sanitize(if (mime == "text/plain") "<pre>${org.jsoup.nodes.Entities.escape(text)}</pre>" else text,response.request.url.toString())
    }
    fun sanitize(html: String, base: String): String {
        val safe = Safelist.relaxed().removeTags("form","input","button","select","textarea")
            .removeAttributes(":all","style","class","id").addEnforcedAttribute("a","rel","noreferrer")
        val clean = Jsoup.clean(html,shortcutUrl(base),safe)
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src http: https:; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'; frame-src 'none'"><style>body{font:17px/1.7 sans-serif;padding:14px;color:#26334b;background:#fff;overflow-wrap:anywhere}img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{max-width:100%;display:block;overflow:auto}a{color:#5264cc}</style></head><body>$clean</body></html>"""
    }
}
internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
    while(true) { val n=read(buffer);if(n<0)break;require(out.size().toLong()+n<=limit) { "页面资源过大，请选择较小的页面" };out.write(buffer,0,n) }
    return out.toByteArray()
}
