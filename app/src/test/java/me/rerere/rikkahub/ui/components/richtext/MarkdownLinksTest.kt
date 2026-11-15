package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.platform.UriHandler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownLinksTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
    private val url = "https://example.invalid/page/?v=1#" + "%E6%98%9F(abc);,".repeat(60) + "%25%2Ftail"
    private fun links(node: ASTNode): List<ASTNode> =
        if (node.type == MarkdownElementTypes.INLINE_LINK) listOf(node) else node.children.flatMap(::links)

    @Test fun angleWrappedLongHashUsesAutolinkNodeAndPreservesEveryUrlCharacter() {
        val source = "[打开链接](<$url>)"
        val link = links(parser.buildMarkdownTreeFromString(source)).single()
        // Regression: the shipped parser supplies no LINK_DESTINATION in this shape.
        assertFalse(link.children.any { it.type == MarkdownElementTypes.LINK_DESTINATION })
        assertEquals(url, link.markdownLinkDestination(source))
        assertTrue(url.length > 900)
    }

    @Test fun ordinaryAndShortAngleLinksAndTitlesUseTheSameDestination() {
        for (target in listOf("https://example.invalid/short", url, "mailto:person@example.invalid")) {
            for (wrapped in listOf(target, "<$target>")) {
                val source = "[打开链接]($wrapped \"链接标题\")"
                assertEquals(target, links(parser.buildMarkdownTreeFromString(source)).single().markdownLinkDestination(source))
            }
        }
    }

    @Test fun labelUrlsAndCitationLabelsDoNotReplaceTheDestination() {
        for ((source, target) in listOf(
            "[https://label.invalid](<$url>)" to url,
            "[citation,example.invalid](abc123)" to "abc123",
        )) assertEquals(target, links(parser.buildMarkdownTreeFromString(source)).single().markdownLinkDestination(source))
    }

    @Test fun percentEscapesAndLiteralUnicodeAreNotDecodedOrEncodedAgain() {
        val targets = listOf(url, "https://example.invalid/#星星(测试);,%2525%2b%2B", "https://example.invalid/#" + "%E6%98%9F".repeat(1200))
        for (target in targets) {
            val source = "[打开链接](<$target>)"
            assertEquals(target, links(parser.buildMarkdownTreeFromString(source)).single().markdownLinkDestination(source))
        }
    }

    @Test fun htmlFallbackStillPreservesTheSameAngleWrappedDestination() = runBlocking {
        val doc = markdownDocumentUpdates(flowOf("<div>说明</div>\n\n[打开链接](<$url>)")).single().getOrThrow()
        assertEquals(url, doc.select("a").single().attr("href"))
    }

    @Test fun htmlCompatibilityKeepsTitlesCodeAndExistingHtmlAnchors() = runBlocking {
        val source = "<a href=\"https://example.invalid/raw#x\">原链接</a>\n\n" +
            "[新链接](<$url> \"链接标题\")\n\n`[代码](<$url>)`"
        val doc = markdownDocumentUpdates(flowOf(source)).single().getOrThrow()
        assertEquals(listOf("https://example.invalid/raw#x", url), doc.select("a").map { it.attr("href") })
        assertEquals("链接标题", doc.select("a")[1].attr("title"))
        assertEquals("[代码](<$url>)", doc.select("code").text())
    }

    @Test fun uriHandlerReportsEmptyOrRejectedTargetsAndDoesNotRetryOrRewriteThem() {
        val opened = mutableListOf<String>(); var failures = 0
        val handler = MarkdownUriHandler(object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
                if (uri == "invalid") throw IllegalArgumentException("synthetic-private-payload")
                if (uri == "denied") throw SecurityException("synthetic-private-payload")
            }
        }) { failures++ }
        handler.openUri(url)
        handler.openUri("")
        handler.openUri("invalid")
        handler.openUri("denied")
        assertEquals(listOf(url, "invalid", "denied"), opened)
        assertEquals(3, failures)
    }
}
