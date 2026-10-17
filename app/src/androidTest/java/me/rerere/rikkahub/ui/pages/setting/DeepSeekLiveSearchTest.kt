package me.rerere.rikkahub.ui.pages.setting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only, on the isolated emulator; never runs with the normal test suite. */
@RunWith(AndroidJUnit4::class)
class DeepSeekLiveSearchTest {
    @Test fun officialSearchAndFetchWithExplicitLocalCredential() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deepSeekLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credential = File(context.cacheDir, "deepseek-live-once.key")
        try {
            val options = SearchServiceOptions.DeepSeekOptions(apiKey = credential.readText().trim())
            val tools = createSearchTools(Settings(searchServices = listOf(options),
                searchServiceSelected = 0, searchCommonOptions = SearchCommonOptions(resultSize = 1)))
            val result = tools.single { it.name == "search_web" }.execute(buildJsonObject {
                put("query", "DeepSeek Search Fetch site:api-docs.deepseek.com")
            }).single() as UIMessagePart.Text
            val search = Json.parseToJsonElement(result.text).jsonObject
            val items = search.getValue("items").jsonArray
            assertTrue("official search returned no source", items.isNotEmpty())
            assertTrue(items.all { it.jsonObject.getValue("url").jsonPrimitive.content.startsWith("https://") })
            assertTrue(items.all { it.jsonObject.getValue("id").jsonPrimitive.content.isNotBlank() })
            assertTrue(search.getValue("retrievedAt").jsonPrimitive.content.isNotBlank())
            val page = tools.single { it.name == "scrape_web" }.execute(buildJsonObject {
                put("url", "https://api-docs.deepseek.com/zh-cn/guides/search/")
            }).single() as UIMessagePart.Text
            val urls = Json.parseToJsonElement(page.text).jsonObject.getValue("urls").jsonArray
            assertTrue("official fetch returned no page", urls.single().jsonObject.getValue("content").jsonPrimitive.content.isNotBlank())
        } finally {
            credential.delete()
        }
    }
}
