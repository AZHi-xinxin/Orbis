package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisManualContextPreview
import me.rerere.rikkahub.data.model.prepareOrbisManualContext
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic immutable chats and callbacks only; never opens Room, a provider, files or user preferences. */
@RunWith(AndroidJUnit4::class)
class OrbisManualContextUiTest {
    @get:Rule val compose = createShellComposeRule()

    private fun chat(count: Int = 4) = Conversation(assistantId = Uuid.random(), title = "Synthetic",
        messageNodes = List(count) { index -> UIMessage(
            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Synthetic message ${index + 1}")),
        ).toMessageNode() })

    @Test fun openingDefaultsToKeeping32AndCannotApplyBeforePreview() {
        val source = chat(36)
        var previews = 0
        var applies = 0
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, null, null, null,
                onPreview = { _, _ -> previews++ }, onApply = { applies++ }, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-end").assertTextContains("4")
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("orbis-manual-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, previews); assertEquals(0, applies) }
    }

    @Test fun previewThenConfirmationAreRequiredAndSuccessDoesNotShowAnInternalId() {
        val source = chat()
        val preview = mutableStateOf<OrbisManualContextPreview?>(null)
        val archiveId = mutableStateOf<Uuid?>(null)
        val archive = Uuid.parse("00000000-0000-0000-0000-000000000099")
        var applies = 0
        val requests = mutableListOf<Pair<Int, String>>()
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, preview.value, null, archiveId.value,
                onPreview = { end, summary -> requests += end to summary; preview.value = prepareOrbisManualContext(source, end, summary) },
                onApply = { applies++; archiveId.value = archive }, onInvalidate = { preview.value = null }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-end").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-preview-ready").assertExists()
        compose.onNodeWithTag("orbis-manual-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, applies); assertEquals(listOf(2 to ""), requests) }
        compose.onNodeWithTag("orbis-manual-cancel-confirm").performClick()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithTag("orbis-manual-apply").performClick()
        compose.onNodeWithTag("orbis-manual-confirm").performClick()
        compose.onNodeWithTag("orbis-manual-success").assertExists()
        compose.onNodeWithText(archive.toString(), substring = true).assertDoesNotExist()
        compose.onNodeWithTag("orbis-manual-apply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, applies); assertEquals(4, source.messageNodes.size) }
    }

    @Test fun editingEitherInputImmediatelyInvalidatesEvenIfParentStillHoldsOldPreview() {
        val source = chat()
        val preview = mutableStateOf<OrbisManualContextPreview?>(null)
        var invalidations = 0
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, preview.value, null, null,
                onPreview = { end, summary -> preview.value = prepareOrbisManualContext(source, end, summary) },
                onApply = { error("Must not apply an invalidated preview") }, onInvalidate = { invalidations++ }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-apply").assertIsEnabled()
        compose.onNodeWithTag("orbis-manual-summary").performScrollTo().performTextReplacement("Human summary")
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-apply").assertIsEnabled()
        compose.onNodeWithTag("orbis-manual-end").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, invalidations) }
    }

    @Test fun freshPreviewFromAnOlderSummaryIsRejectedDespiteAnIdenticalRange() {
        val source = chat()
        val preview = mutableStateOf<OrbisManualContextPreview?>(null)
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, preview.value, null, null,
                onPreview = { end, _ -> preview.value = prepareOrbisManualContext(source, end, "Older text") },
                onApply = { error("Must not apply a mismatched summary") }, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-summary").performScrollTo().performTextReplacement("Newer text")
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-manual-preview-ready").assertDoesNotExist()
    }

    @Test fun updatedConversationCannotReuseAnOldPreview() {
        val source = mutableStateOf(chat())
        val preview = mutableStateOf<OrbisManualContextPreview?>(null)
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source.value, false, preview.value, null, null,
                onPreview = { end, summary -> preview.value = prepareOrbisManualContext(source.value, end, summary) },
                onApply = { error("Must recheck updated source") }, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-apply").assertIsEnabled()
        compose.runOnIdle { source.value = source.value.copy(updateAt = source.value.updateAt.plusSeconds(1)) }
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled()
    }

    @Test fun aNonShrinkingPreviewWarnsClearlyButStillRequiresAnExplicitConfirmation() {
        val source = chat()
        val preview = mutableStateOf<OrbisManualContextPreview?>(null)
        var applied = 0
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, preview.value, null, null,
                onPreview = { end, summary ->
                    val prepared = prepareOrbisManualContext(source, end, summary)
                    preview.value = prepared.copy(afterTokens = prepared.beforeTokens)
                }, onApply = { applied++ }, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.onNodeWithTag("orbis-manual-not-smaller").assertExists()
        compose.onNodeWithTag("orbis-manual-apply").assertIsEnabled().performClick()
        compose.onNodeWithText("此次估算占用没有下降", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithTag("orbis-manual-cancel-confirm").performClick()
        compose.runOnIdle { assertEquals(0, applied) }
    }

    @Test fun busyDisablesEditsDismissAndBothActions() {
        val source = chat()
        var callbacks = 0
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, true, null, null, null,
                onPreview = { _, _ -> callbacks++ }, onApply = { callbacks++ }, onInvalidate = { callbacks++ }, onDismiss = { callbacks++ })
        } }
        compose.onNodeWithTag("orbis-manual-close").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("orbis-manual-end").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-manual-summary").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-manual-preview").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("orbis-manual-apply").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("orbis-manual-busy").assertExists()
        compose.runOnIdle { assertEquals(0, callbacks) }
    }

    @Test fun boundedLazyListLetsHumanPickALateMessageWithoutRenderingAllBodies() {
        val source = chat(180)
        var chosen: Int? = null
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, null, null, null,
                onPreview = { end, _ -> chosen = end }, onApply = {}, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-toggle-list").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-manual-message-list").performScrollTo().performScrollToIndex(80)
        compose.onNodeWithTag("orbis-manual-select-81").performClick()
        compose.onNodeWithTag("orbis-manual-preview").performClick()
        compose.runOnIdle { assertEquals(81, chosen) }
    }

    @Test fun invalidRangeAndOversizeSummaryCannotBePreviewed() {
        val source = chat()
        var calls = 0
        compose.setContent { MaterialTheme {
            OrbisManualContextSheet(source, false, null, null, null,
                onPreview = { _, _ -> calls++ }, onApply = {}, onInvalidate = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-manual-end").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("orbis-manual-preview").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-manual-end").performTextReplacement("2")
        compose.onNodeWithTag("orbis-manual-summary").performScrollTo().performTextReplacement("s".repeat(20_001))
        compose.onNodeWithTag("orbis-manual-preview").assertIsNotEnabled()
        compose.onNodeWithText("输入超过 20000 字符", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun shortExcerptNeverJoinsAnEntireLongMessageOrExpandsAttachments() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("  visible\n" + "x".repeat(100_000) + "private-tail-not-for-preview"),
            UIMessagePart.Image("file:///not-read/synthetic.png"),
        )).toMessageNode()
        val short = manualContextMessageSnippet(message)
        assertTrue(short.length <= 120)
        assertTrue(short.startsWith("visible "))
        assertFalse(short.contains("private-tail-not-for-preview"))
        assertFalse(short.contains("file://"))
        assertEquals("[无有效的所选分支]", manualContextMessageSnippet(MessageNode(messages = emptyList())))
    }
}
