package me.rerere.search

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekResearchTest {
    @Test fun `research uses isolated Flash web tools without companion functions or history`() {
        val body=DeepSeekResearch.body("查询天气")
        assertEquals("deepseek-flash",body["model"]!!.jsonPrimitive.content)
        assertEquals(1,body["messages"]!!.jsonArray.size)
        assertEquals(listOf("deepseek_web_search","deepseek_read_url"),body["tools"]!!.jsonArray.map{it.jsonObject["type"]!!.jsonPrimitive.content})
        assertFalse(SearchServiceOptions.DeepSeekOptions().allowDeepResearch)
        assertFalse(DeepSeekSearchService.parameters(SearchServiceOptions.DeepSeekOptions()).toString().contains("research"))
    }
    private fun response(reason:String="stop")="""{"choices":[{"finish_reason":"$reason","message":{"content":"查询结果","annotations":[{"url_citation":{"url":"https://example.com","title":"来源"}},{"url_citation":{"url":"javascript:bad"}}]}}]}"""
    @Test fun `citations survive and unsafe links are excluded`() {
        val parsed=DeepSeekResearch.parse(response());assertEquals("查询结果",parsed.answer)
        assertEquals(listOf("https://example.com"),parsed.items.map{it.url})
        assertTrue(DeepSeekResearch.parse(response("length")).answer!!.contains("部分结果"))
    }
    @Test fun `refusal and missing responses are not fabricated search success`() {
        assertThrows(IllegalStateException::class.java) { DeepSeekResearch.parse(response("content_filter")) }
        assertThrows(IllegalStateException::class.java) { DeepSeekResearch.parse("{\"error\":{}}") }
    }
}
