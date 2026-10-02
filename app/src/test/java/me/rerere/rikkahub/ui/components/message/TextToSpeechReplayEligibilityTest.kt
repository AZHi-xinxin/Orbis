package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.ui.components.message.tools.TextToSpeechToolUI
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure renderer eligibility checks: no playback, Android context, DI, storage, or network. */
class TextToSpeechReplayEligibilityTest {
    private fun tool() = UIMessagePart.Tool(
        toolCallId = "synthetic-tts",
        toolName = "text_to_speech",
        input = """{"text":"synthetic full playback text"}""",
        output = listOf(UIMessagePart.Text("""{"success":true}""")),
    )

    private fun context(tool: UIMessagePart.Tool = tool(), loading: Boolean = false) = ToolUIContext(
        tool = tool,
        arguments = tool.inputAsJson(),
        content = if (tool.isExecuted) Json.parseToJsonElement("""{"success":true}""") else null,
        loading = loading,
    )

    @Test fun completedAutoAndApprovedSpeechHaveExplicitHeaderReplay() {
        assertTrue(TextToSpeechToolUI.hasHeaderActions(context()))
        assertTrue(TextToSpeechToolUI.hasHeaderActions(context(tool().copy(approvalState = ToolApprovalState.Approved))))
    }

    @Test fun unexecutedAndLoadingSpeechCannotReplay() {
        assertFalse(TextToSpeechToolUI.hasHeaderActions(context(tool().copy(output = emptyList()))))
        assertFalse(TextToSpeechToolUI.hasHeaderActions(context(loading = true)))
    }

    @Test fun pendingIsBlockedEvenWhenAnImportedRecordHasOutput() {
        val pending = tool().copy(approvalState = ToolApprovalState.Pending)
        assertTrue(pending.isExecuted)
        assertFalse(pending.isPending)
        assertFalse(TextToSpeechToolUI.hasHeaderActions(context(pending)))
        assertFalse(TextToSpeechToolUI.hasHeaderActions(context(pending.copy(output = emptyList()))))
    }

    @Test fun deniedIsBlockedEvenWhenItHasAReceipt() {
        val denied = tool().copy(approvalState = ToolApprovalState.Denied("synthetic denial"))
        assertTrue(denied.isExecuted)
        assertFalse(TextToSpeechToolUI.hasHeaderActions(context(denied)))
    }

    @Test fun explicitHostFailureCannotBecomePlayback() {
        for (failure in HostToolFailure.entries) {
            val failed = tool().copy(output = emptyList()).withHostToolFailure(failure)
            assertTrue(failed.isExecuted)
            assertFalse(TextToSpeechToolUI.hasHeaderActions(context(failed)))
        }
    }

    @Test fun missingNullMalformedOrBlankTextHasNoReplay() {
        for (input in listOf("{}", """{"text":null}""", """{"text":""}""",
            """{"text":" \n\t "}""", "not json")) {
            assertFalse("No replay for $input", TextToSpeechToolUI.hasHeaderActions(context(tool().copy(input = input))))
        }
    }

    @Test fun OtherToolsDoNotAcquireSpeechActions() {
        for (name in listOf("get_time_info", "synthetic_unknown_tool", "tts", "orbis_voice_note")) {
            val other = tool().copy(toolName = name)
            assertFalse(ToolUIRegistry.resolve(name).hasHeaderActions(context(other)))
        }
    }
}
