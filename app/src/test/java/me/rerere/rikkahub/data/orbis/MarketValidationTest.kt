package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException
import me.rerere.rikkahub.data.ai.toolOutcomeIsUnknown
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

class MarketValidationTest {
    @Test fun newEditorNeedsAnExplicitDestinationAndKeepsTheSelectedShelf() {
        assertEquals("", marketEditorCategory(null, ""))
        assertEquals("grid", marketEditorCategory(null, "grid"))
        assertEquals("extra", marketEditorCategory("extra", "grid"))
        assertEquals("", marketEditorCategory("", "grid"))
    }
    @Test fun htmlMisclassifiedAsStoryIsKnownNotExecutedRatherThanUnknown() {
        val args = buildJsonObject { put("kind", "html"); put("text", "<html><body>合成测试</body></html>") }
        val failure = assertThrows(ToolRejectedBeforeExecutionException::class.java) { marketSaveRequest("extra", args) }
        assertTrue(failure.publicReason.contains("分类"))
        assertFalse(toolOutcomeIsUnknown(true, true, failure))
        assertEquals("html", marketSaveRequest("grid", args).kind)
    }
    @Test fun malformedArgumentsAndQuestionnairesAreRejectedBeforeAnySave() {
        for (raw in listOf("[]", "{}", "{\"text\":4}", "{\"text\":\"ok\",\"kind\":false}",
            "{\"text\":\"ok\",\"other\":1}", "{\"text\":\"\"}",
            "{\"text\":\"not a questionnaire\",\"kind\":\"questionnaire\"}")) {
            assertThrows(ToolRejectedBeforeExecutionException::class.java) { marketSaveRequest("grid", Json.parseToJsonElement(raw)) }
        }
    }
    @Test fun plainTextStillDefaultsToTextAndKeepsBodyExact() {
        val body = "正文 🌙\n下一行"
        assertEquals(MarketSaveRequest(body, "text"), marketSaveRequest("extra", buildJsonObject { put("text", body) }))
    }
}
