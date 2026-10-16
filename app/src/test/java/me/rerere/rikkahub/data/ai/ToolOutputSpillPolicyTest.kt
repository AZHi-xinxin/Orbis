package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSpillPolicyTest {
    @Test fun boundedNativeReadRetainsJsonAndCursor() {
        assertTrue(keepCompleteToolOutput("workspace_read_file"))
    }

    @Test fun memoryDoesNotSpillToOrdinaryFiles() {
        assertTrue(keepCompleteToolOutput(me.rerere.rikkahub.data.ai.tools.ORBIS_MEMORY_TOOL))
    }

    @Test fun otherToolsKeepExistingSpillPolicy() {
        assertFalse(keepCompleteToolOutput("workspace_shell"))
        assertFalse(keepCompleteToolOutput("web_search"))
        assertFalse(keepCompleteToolOutput("remote_workspace_read_file"))
    }
}
