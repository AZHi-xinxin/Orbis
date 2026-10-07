package me.rerere.rikkahub.ui.components.richtext

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedContentPolicyTest {
    @Test fun allInertImportersDisableAutomaticHistoricalMedia() {
        listOf("deepseek", "operit_json_v2", "kelivo_sqlite_v2", "polaris_export_v1", "claude_export_v1", "chatgpt_export_v1").forEach { source ->
            assertTrue(listOf(UIMessagePart.Text("synthetic", metadata = buildJsonObject {
                put("import_source", source)
            })).isDeepSeekHistory())
        }
        assertFalse(listOf(UIMessagePart.Text("ordinary draft")).isDeepSeekHistory())
        assertFalse(emptyList<UIMessagePart>().isDeepSeekHistory())
    }

    @Test fun historicalDiagramsNeverAutoRenderWhileOrdinaryCompletedMermaidStillDoes() {
        listOf("deepseek", "operit_json_v2", "kelivo_sqlite_v2", "polaris_export_v1", "claude_export_v1", "chatgpt_export_v1").forEach { source ->
            val imported = listOf(UIMessagePart.Text(
                "```mermaid\nflowchart LR\n A@{ img: \"https://invalid.example/archive.png\", label: \"synthetic\", h: 60 }\n```",
                metadata = buildJsonObject { put("import_source", source) },
            )).isDeepSeekHistory()
            assertFalse(shouldAutoRenderMermaid("mermaid", completeCodeBlock = true, importedHistory = imported))
        }
        assertTrue(shouldAutoRenderMermaid("mermaid", completeCodeBlock = true, importedHistory = false))
        assertTrue(shouldAutoRenderMermaid("MERMAID", completeCodeBlock = true, importedHistory = false))
        assertFalse(shouldAutoRenderMermaid("mermaid", completeCodeBlock = false, importedHistory = false))
        assertFalse(shouldAutoRenderMermaid("json", completeCodeBlock = true, importedHistory = false))
    }
}
