package me.rerere.rikkahub.data.orbis.webshortcuts

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.executeGenerationTool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebShortcutsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun repo(name:String="a")=WebShortcutRepository(File(temp.root,"$name/shelf.json"))
    @Test fun atomicReopenAndOwnerIsolationAndHumanEntryProtection() {
        val a=repo();val b=repo("b")
        val first=a.add("https://example.test/article","标题","human","chat")
        assertEquals(first,a.add(first.url,"replacement","ai"))
        assertEquals(listOf(first),repo().snapshot().entries);assertTrue(b.snapshot().entries.isEmpty())
        assertThrows(IllegalStateException::class.java) { a.remove(first.id,false) }
        assertEquals(listOf(first),a.snapshot().entries)
        val ai=a.add("https://example.test/other","Other","ai");a.remove(ai.id,false)
        a.remove(first.id,true);assertTrue(a.snapshot().entries.isEmpty())
        assertFalse(File(temp.root,"a").listFiles()!!.any{it.name.endsWith(".tmp")})
    }
    @Test fun onlyHttpUrlsWithoutEmbeddedCredentialsAndControlCharacters() {
        for(url in listOf("javascript:alert(1)","file:///data/private","intent://open","data:text/html,hi","https://user:pass@example.test/","https://example.test/\nX: secret")) {
            assertThrows(Exception::class.java) { shortcutUrl(url) }
        }
        assertEquals("https://example.test/a",sharedWebUrl("分享 https://example.test/a。"))
        assertEquals("http://127.0.0.1:18000/play",shortcutUrl("http://127.0.0.1:18000/play"))
    }
    @Test fun interactionIsExplicitPerOriginAndPortAndDoesNotChangeOnAnAiBookmark() {
        val r=repo();val first=r.add("https://game.test/play?id=1","game","ai")
        assertTrue(r.snapshot().interactiveOrigins.isEmpty())
        r.allowInteraction(first.url,"mcp-id")
        assertEquals("mcp-id",r.snapshot().interactiveOrigins[shortcutOrigin("https://game.test/other")])
        assertNull(r.snapshot().interactiveOrigins[shortcutOrigin("http://game.test/play")])
        assertNull(r.snapshot().interactiveOrigins[shortcutOrigin("https://game.test:8443/play")])
        r.add("https://stranger.test/play","other","ai")
        assertNull(r.snapshot().interactiveOrigins[shortcutOrigin("https://stranger.test/play")])
        r.allowInteraction(first.url,null);assertTrue(r.snapshot().interactiveOrigins.isEmpty())
    }
    @Test fun readOnlySanitizerDropsExecutableAndMutatingMarkupButRetainsReadingLinks() {
        val html=ReadOnlyWebPage.sanitize("""<script>fetch('/write',{method:'POST'})</script><form action='/write'><input name='secret'><button>send</button></form><iframe src='/private'></iframe><p onclick='steal()'>read this</p><img src='/photo.png' onerror='steal()'><a href='/next'>next</a><a href='javascript:steal()'>unsafe</a>""","https://example.test/page")
        assertFalse(html.contains("<script"));assertFalse(html.contains("<form"));assertFalse(html.contains("<input"));assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("onclick"));assertFalse(html.contains("onerror"));assertFalse(html.contains("javascript:"));assertFalse(html.contains("fetch("))
        assertTrue(html.contains("read this"));assertTrue(html.contains("https://example.test/next"));assertTrue(html.contains("https://example.test/photo.png"))
        assertTrue(html.contains("form-action 'none'"));assertTrue(html.contains("default-src 'none'"))
    }
    @Test fun toolDefaultOffNeverReadsStoreAndCannotGrantInteraction() = runBlocking {
        assertFalse(LocalToolOption.WebShortcuts in Assistant().localTools)
        val off=webShortcutTool("owner","chat",{false}) {error("opened")}
        val error=(off.execute(buildJsonObject {put("action","list")}).single() as UIMessagePart.Text).text
        assertTrue(error.contains("web_shortcuts_disabled"));assertFalse(error.contains("opened"))
        val r=repo();val tool=webShortcutTool("owner","chat",{true}) {r}
        val result=tool.execute(Json.parseToJsonElement("""{"action":"add","url":"https://game.test/","interactive":true,"mcp_id":"test"}"""))
        assertFalse(Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r.snapshot().entries.isEmpty());assertTrue(r.snapshot().interactiveOrigins.isEmpty())
    }
    @Test fun showIsBoundToConversationAndRequestsAFloatingPageWithoutChangingShelf() = runBlocking {
        val r=repo();val entry=r.add("https://example.test/page","title","ai")
        val tool=webShortcutTool("owner","bound",{true}) {r}
        val args=buildJsonObject {put("action","show");put("id",entry.id)}
        val wrong=executeGenerationTool(tool,args,"call1","message","other")
        assertTrue((wrong.single() as UIMessagePart.Text).text.contains("web_shortcut_conversation_missing"))
        assertNull(WebRecommendations.pending.value["owner/bound"])
        val before=r.snapshot()
        val ok=executeGenerationTool(tool,args,"call2","message","bound")
        assertTrue(Json.parseToJsonElement((ok.single() as UIMessagePart.Text).text).jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertEquals(entry,WebRecommendations.pending.value["owner/bound"]?.entry)
        assertTrue(WebRecommendations.windows.value["owner/bound"]!!.expanded)
        assertEquals(before,r.snapshot()) // No visit/open/click telemetry in the shelf.
    }

    @Test fun multipleShowsSurviveCoalescingAndMinimizeCloseAndFreshCallAreDistinct() {
        val owner="window-owner";val chat="window-chat";val key="$owner/$chat"
        val a=repo().add("https://game.test/a","a","ai")
        val b=repo().add("https://game.test/b","b","ai")
        WebRecommendations.show(owner,chat,a);WebRecommendations.show(owner,chat,b)
        assertEquals(listOf(a,b),WebRecommendations.windows.value[key]!!.pages)
        WebRecommendations.change(owner,chat) {it.copy(expanded=false)}
        assertEquals(2,WebRecommendations.windows.value[key]!!.pages.size)
        assertFalse(WebRecommendations.windows.value[key]!!.expanded)
        WebRecommendations.show(owner,chat,a)
        assertTrue(WebRecommendations.windows.value[key]!!.expanded)
        assertEquals(2,WebRecommendations.windows.value[key]!!.pages.size)
        assertEquals(a.id,WebRecommendations.windows.value[key]!!.selectedId)
        WebRecommendations.close(owner,chat,a.id)
        assertEquals(b.id,WebRecommendations.windows.value[key]!!.selectedId)
        WebRecommendations.close(owner,chat,b.id)
        assertTrue(WebRecommendations.windows.value[key]!!.pages.isEmpty())
        assertFalse(WebRecommendations.windows.value[key]!!.expanded)
        WebRecommendations.show(owner,chat,a)
        assertEquals(listOf(a),WebRecommendations.windows.value[key]!!.pages)
        assertNull(WebRecommendations.windows.value["$owner/other"])
        assertEquals(2,repo().snapshot().entries.size)
    }
}
