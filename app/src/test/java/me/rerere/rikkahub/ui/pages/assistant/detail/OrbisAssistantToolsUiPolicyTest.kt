package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolDescriptor
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolFamily
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisAssistantToolsUiPolicyTest {
    @Test fun menuFamiliesAreTheThreeRequestedNativeToolGroupsInOrder() {
        assertEquals(listOf(CloudToolFamily.READING, CloudToolFamily.ORBIS, CloudToolFamily.TURTLESOUP), assistantCloudToolFamilies)
        assertEquals(listOf("藏书阁共读", "Orbis工具", "海龟汤"), assistantCloudToolFamilies.map(::cloudToolFamilyTitle))
    }

    @Test fun identityOnlyMergesChangedFieldsAndPreservesConcurrentToolsMemoryAndModel() {
        val before = Assistant(name = "old", systemPrompt = "role")
        val current = before.copy(chatModelId = Uuid.random(), enableMemory = true, mcpServers = setOf(Uuid.random()), name = "newer")
        val edited = before.copy(background = "synthetic-local", backgroundOpacity = .42f, temperature = 1.8f, systemPrompt = "must-not-apply")
        val result = mergeOrbisAssistantProfileEdit(current, before, edited, OrbisAssistantProfileSection.IDENTITY)
        assertEquals(current.copy(background = "synthetic-local", backgroundOpacity = .42f), result)
    }

    @Test fun allUniqueRoleFieldsRemainEditableWithoutReplacingOtherPreferences() {
        val before = Assistant(name = "identity")
        val current = before.copy(enableMemory = true, maxTokens = 3456)
        val edited = before.copy(systemPrompt = "role", allowConversationSystemPrompt = true,
            allowConversationPromptInjection = true, messageTemplate = "{{ date }} {{ message }}",
            regexes = listOf(AssistantRegex(id = Uuid.random(), name = "sample")), name = "must-not-apply")
        val result = mergeOrbisAssistantProfileEdit(current, before, edited, OrbisAssistantProfileSection.ROLE)
        assertEquals(current.copy(systemPrompt = edited.systemPrompt, allowConversationSystemPrompt = true,
            allowConversationPromptInjection = true, messageTemplate = edited.messageTemplate, regexes = edited.regexes), result)
    }

    @Test fun profileEditCannotCrossAssistantIdentity() {
        val before = Assistant()
        assertThrows(IllegalArgumentException::class.java) {
            mergeOrbisAssistantProfileEdit(Assistant(), before, before.copy(name = "wrong"), OrbisAssistantProfileSection.IDENTITY)
        }
    }

    @Test fun gatewayFormUsesTheSameStrictPolicyAsTheCredentialStore() {
        val token = "a".repeat(48)
        assertTrue(canSaveCloudToolCredentials("https://synthetic.example.ts.net:18910", token))
        listOf("http://synthetic.example.ts.net:18910", "https://example.com:18910",
            "https://synthetic.example.ts.net", "https://synthetic.example.ts.net:18910/path",
            "https://synthetic.example.ts.net:18910/?token=x", "https://user:pass@synthetic.example.ts.net:18910").forEach {
            assertFalse(canSaveCloudToolCredentials(it, token))
        }
        assertFalse(canSaveCloudToolCredentials("https://synthetic.example.ts.net:18910", ""))
        assertFalse(canSaveCloudToolCredentials("https://synthetic.example.ts.net:18910", "Bearer $token"))
    }

    @Test fun permissionLabelsNeverDescribeUnknownOrApprovedReadsAsUnrestrictedReads() {
        fun descriptor(effect: String, approval: Boolean = false) = CloudToolDescriptor("example", "synthetic", JsonObject(emptyMap()), effect, approval)
        assertEquals("只读", cloudToolRiskLabel(descriptor("read")))
        assertEquals("写入 · 需审批", cloudToolRiskLabel(descriptor("write")))
        assertTrue(cloudToolRiskLabel(descriptor("read", true)).contains("需审批"))
        assertNotEquals("只读", cloudToolRiskLabel(descriptor("unknown")))
    }
}
