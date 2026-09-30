package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.ORBIS_EVENT_SOURCES
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.OrbisInboxState
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** The pure card uses in-memory synthetic models; it never resolves Koin services or sends events. */
@RunWith(AndroidJUnit4::class)
class OrbisSentinelBindingUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val ai = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val target = Conversation(id = Uuid.parse("00000000-0000-4000-8000-000000000002"),
        assistantId = ai, title = "合成固定会话", messageNodes = emptyList())

    @Test fun openingDoesNotPickLatestOrEnableAnySourceIncludingLegacy() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelBindingCard(ai.toString(), "合成 AI", emptyMap(), listOf(target), OrbisInboxState(),
                canEdit = true, loading = false, notice = null,
                onChooseTarget = { error("Opening must not select a target") },
                onToggle = { _, _ -> error("Opening must not enable a sender") })
        } } }
        ORBIS_EVENT_SOURCES.forEach {
            compose.onNodeWithTag("sentinel-enabled-$it").assertIsOff().assertIsNotEnabled()
        }
    }

    @Test fun onlyExplicitTapEnablesAnAlreadyChosenCurrentAiTarget() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        val inbox = OrbisInboxState(bindings = mapOf("rikka_sentinel" to OrbisEventBinding(ai.toString(), target.id.toString(), false)))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelBindingCard(ai.toString(), "合成 AI", emptyMap(), listOf(target), inbox,
                canEdit = true, loading = false, notice = null,
                onChooseTarget = {}, onToggle = { source, enabled -> changes += source to enabled })
        } } }
        compose.runOnIdle { assertEquals(0, changes.size) }
        compose.onNodeWithTag("sentinel-enabled-rikka_sentinel").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle { assertEquals(listOf("rikka_sentinel" to true), changes) }
    }

    @Test fun anotherAiBindingCannotBeToggledAndUnknownReceiptIsNotSuccessful() {
        val other = "00000000-0000-4000-8000-000000000003"
        val inbox = OrbisInboxState(
            bindings = mapOf("rikka_sentinel" to OrbisEventBinding(other, target.id.toString(), true)),
            events = listOf(OrbisInboxEvent(eventId = "synthetic", source = "rikka_sentinel", text = "合成原文",
                wake = true, assistantId = ai.toString(), conversationId = target.id.toString(), receivedAt = 1_000L, state = "unknown")))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelBindingCard(ai.toString(), "合成 AI", mapOf(other to "另一合成 AI"), listOf(target), inbox,
                canEdit = true, loading = false, notice = null,
                onChooseTarget = {}, onToggle = { _, _ -> error("Cannot toggle another AI's binding") })
        } } }
        compose.onNodeWithTag("sentinel-enabled-rikka_sentinel").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("聊天哨兵 · 状态未知").performScrollTo().assertExists()
        compose.onNodeWithText("无法确认是否完成，不会自动重试。请先查看固定会话，避免重复唤醒。").assertExists()
    }
}
