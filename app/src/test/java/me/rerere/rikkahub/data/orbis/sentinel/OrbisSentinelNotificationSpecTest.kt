package me.rerere.rikkahub.data.orbis.sentinel

import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import org.junit.Assert.*
import org.junit.Test

/** Pure projections only: no Context, notification posting, observer or model invocation. */
class OrbisSentinelNotificationSpecTest {
    private val rule = OrbisSentinelRule(
        id = "synthetic-rule", assistantId = "assistant-1", conversationId = "conversation-1",
        type = OrbisSentinelType.ONCE, prompt = "PRIVATE RULE PROMPT", name = "午后提醒", enabled = true,
    )
    private val event = OrbisInboxEvent(
        eventId = "native:synthetic-1", source = "native_sentinel.${rule.id}", text = "PRIVATE SCREEN OBSERVATION",
        wake = true, assistantId = rule.assistantId, conversationId = rule.conversationId,
        receivedAt = 1000, localImage = "private-screenshot.jpg", error = "PRIVATE ERROR",
    )

    @Test fun lightAndStrongHaveSeparateChannelIdentities() {
        val light = sentinelAcceptedNotificationSpec(rule, event)!!
        val strong = sentinelAcceptedNotificationSpec(rule.copy(notificationLevel = OrbisSentinelNotificationLevel.STRONG), event)!!
        assertEquals(OrbisSentinelNotificationLevel.LIGHT, light.level)
        assertEquals(OrbisSentinelNotificationLevel.STRONG, strong.level)
        assertEquals("orbis_sentinel_light_v1", light.channelId)
        assertEquals("orbis_sentinel_strong_v1", strong.channelId)
        assertEquals(light.notificationTag, strong.notificationTag)
        assertEquals(light.notificationId, strong.notificationId)
    }

    @Test fun contentOnlyIncludesRuleNameAndHonestAcceptanceStatus() {
        val spec = sentinelAcceptedNotificationSpec(rule, event)!!
        assertEquals(rule.name, spec.title)
        assertEquals("已送入固定会话，等待AI处理", spec.content)
        assertFalse(spec.toString().contains("PRIVATE"))
        assertFalse(spec.toString().contains("private-screenshot"))
        assertFalse(spec.content.contains("已醒"))
        assertFalse(spec.content.contains("已回复"))
    }

    @Test fun fixedConversationAndTimeComeFromMatchingDurableEvent() {
        val spec = sentinelAcceptedNotificationSpec(rule, event)!!
        assertEquals("conversation-1", spec.conversationId)
        assertEquals(1000L, spec.receivedAtMs)
    }

    @Test fun mismatchedSourceAssistantOrConversationCannotProduceNotification() {
        assertNull(sentinelAcceptedNotificationSpec(rule, event.copy(source = "lc_sentinel")))
        assertNull(sentinelAcceptedNotificationSpec(rule, event.copy(source = "native_sentinel.other-rule")))
        assertNull(sentinelAcceptedNotificationSpec(rule, event.copy(assistantId = "other-assistant")))
        assertNull(sentinelAcceptedNotificationSpec(rule, event.copy(conversationId = "other-conversation")))
    }

    @Test fun failedSuppressedUncertainOrCompletedReceiptsAreNotAdvertisedAsWaiting() {
        listOf("unknown", "target_invalid", "failed", "suppressed", "replied", "nonexistent").forEach {
            assertNull(it, sentinelAcceptedNotificationSpec(rule, event.copy(state = it)))
        }
        listOf("accepted", "queued", "displayed", "generating", "pending_tool").forEach {
            assertNotNull(it, sentinelAcceptedNotificationSpec(rule, event.copy(state = it)))
        }
    }

    @Test fun autoDisabledOneShotMayStillNotifyItsNewAcceptedEvent() {
        assertNotNull(sentinelAcceptedNotificationSpec(rule.copy(enabled = false), event))
    }

    @Test fun subsequentOccurrencesOfOneRuleReplaceRatherThanStack() {
        val first = sentinelAcceptedNotificationSpec(rule, event)!!
        val next = sentinelAcceptedNotificationSpec(rule.copy(name = "Updated name", prompt = "Different prompt"),
            event.copy(id = "another-record", eventId = "native:synthetic-2", receivedAt = 5000))!!
        assertEquals(first.notificationId, next.notificationId)
        assertEquals(first.notificationTag, next.notificationTag)
        assertEquals(first.identity, next.identity)
    }

    @Test fun differentRulesOrBindingsHaveIsolatedPendingIntentIdentities() {
        val first = sentinelAcceptedNotificationSpec(rule, event)!!
        val otherRule = sentinelAcceptedNotificationSpec(rule.copy(id = "other-rule"), event.copy(source = "native_sentinel.other-rule"))!!
        val otherWindow = sentinelAcceptedNotificationSpec(rule.copy(conversationId = "other-window"), event.copy(conversationId = "other-window"))!!
        assertNotEquals(first.identity, otherRule.identity)
        assertNotEquals(first.notificationTag, otherRule.notificationTag)
        assertNotEquals(first.identity, otherWindow.identity)
    }

    @Test fun blankNameDoesNotFallBackToPrivatePrompt() {
        val spec = sentinelAcceptedNotificationSpec(rule.copy(name = " \n\t"), event)!!
        assertEquals("本地哨兵", spec.title)
        assertFalse(spec.toString().contains(rule.prompt))
    }

    @Test fun notificationNameIsSingleLineAndPreservesCompleteEmoji() {
        val single = sentinelAcceptedNotificationSpec(rule.copy(name = "first\nsecond\u202Etitle"), event)!!
        assertEquals("first second title", single.title)
        val emoji = sentinelAcceptedNotificationSpec(rule.copy(name = "😀".repeat(100)), event)!!
        assertEquals("😀".repeat(80), emoji.title)
    }
}
