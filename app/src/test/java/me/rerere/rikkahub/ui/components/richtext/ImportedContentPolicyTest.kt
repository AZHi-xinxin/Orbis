package me.rerere.rikkahub.ui.components.richtext

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedContentPolicyTest {
    @Test fun allInertImportersDisableAutomaticHistoricalMedia() {
        listOf("deepseek", "operit_json_v2", "kelivo_sqlite_v2").forEach { source ->
            assertTrue(listOf(UIMessagePart.Text("synthetic", metadata = buildJsonObject {
                put("import_source", source)
            })).isDeepSeekHistory())
        }
        assertFalse(listOf(UIMessagePart.Text("ordinary draft")).isDeepSeekHistory())
        assertFalse(emptyList<UIMessagePart>().isDeepSeekHistory())
    }
}
