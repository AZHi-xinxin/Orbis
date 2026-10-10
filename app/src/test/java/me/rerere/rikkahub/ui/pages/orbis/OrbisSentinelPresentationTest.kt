package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelAction
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelExecutionStatus
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRule
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisSentinelPresentationTest {
    @Test fun skippedWakeShowsCauseWithoutClaimingSentOrReplied() {
        assertEquals("本次已跳过，不再排队", orbisSentinelReceiptStateLabel("skipped"))
        assertTrue(orbisWakeReasonLabel("wake_gateway_unconfirmed").contains("旧连接"))
        assertTrue(orbisWakeReasonLabel("wake_tool_pending").contains("审批"))
        assertTrue(orbisWakeReasonLabel("wake_reply_in_progress").contains("另一条回复"))
        assertEquals("future_reason", orbisWakeReasonLabel("future_reason"))
    }

    private fun rule(type: OrbisSentinelType) = OrbisSentinelRule(
        id = "synthetic-rule", assistantId = "synthetic-ai", conversationId = "synthetic-chat",
        type = type, prompt = "  preserve [this] exactly\n", createdAtMs = 1L,
    )

    @Test fun durationHandlesMissingZeroAndSubsecondWithoutInventingAnInterval() {
        assertEquals("未设置", orbisSentinelDurationLabel(null))
        assertEquals("未设置", orbisSentinelDurationLabel(-1))
        assertEquals("0 毫秒", orbisSentinelDurationLabel(0))
        assertEquals("250 毫秒", orbisSentinelDurationLabel(250))
    }

    @Test fun durationKeepsNonRoundRemainders() {
        assertEquals("1 小时 2 分钟 3 秒 450 毫秒", orbisSentinelDurationLabel(3_723_450L))
        assertEquals("2 分钟", orbisSentinelDurationLabel(120_000))
        assertEquals("1 秒", orbisSentinelDurationLabel(1_000))
    }

    @Test fun intervalConditionShowsActualRuleInterval() {
        assertEquals("间隔触发 · 每 5 分钟", orbisSentinelConditionLabel(rule(OrbisSentinelType.INTERVAL).copy(intervalMs = 300_000L)))
    }

    @Test fun missingOnceTimeDoesNotPretendItWasScheduled() {
        assertEquals("定时一次 · 时间未设置", orbisSentinelConditionLabel(rule(OrbisSentinelType.ONCE)))
    }

    @Test fun chatIdleAndChatLeftHaveDistinctExplanations() {
        assertEquals("固定会话未收到新用户消息达到 2 分钟", orbisSentinelConditionLabel(rule(OrbisSentinelType.CHAT_IDLE).copy(thresholdMs = 120_000)))
        assertEquals("离开固定会话达到 2 分钟", orbisSentinelConditionLabel(rule(OrbisSentinelType.CHAT_LEFT).copy(thresholdMs = 120_000)))
    }

    @Test fun appUsageDisplaysActualPackageAndThreshold() {
        assertEquals("应用 test.synthetic.app 使用达到 3 分钟", orbisSentinelConditionLabel(rule(OrbisSentinelType.APP_USAGE)
            .copy(appPackage = "test.synthetic.app", thresholdMs = 180_000)))
    }

    @Test fun acceptedNeverClaimsTheAiAlreadyReplied() {
        assertEquals("收件箱已接收", orbisSentinelExecutionStatusLabel(OrbisSentinelExecutionStatus.ACCEPTED))
        OrbisSentinelExecutionStatus.entries.forEach {
            assertFalse(orbisSentinelExecutionStatusLabel(it).contains("已回复"))
        }
    }

    @Test fun allActionsAndStatesHaveExplicitPresentation() {
        assertEquals("唤醒 AI", orbisSentinelActionLabel(OrbisSentinelAction.WAKE))
        assertEquals("读取设备上下文后唤醒", orbisSentinelActionLabel(OrbisSentinelAction.DEVICE_CONTEXT))
        assertEquals("截屏观察后唤醒", orbisSentinelActionLabel(OrbisSentinelAction.SCREENSHOT))
        assertTrue(orbisSentinelExecutionStatusLabel(OrbisSentinelExecutionStatus.BLOCKED).contains("暂停"))
        assertEquals("已准备，待送达", orbisSentinelExecutionStatusLabel(OrbisSentinelExecutionStatus.STAGED))
        assertEquals("已暂停，未唤醒", orbisSentinelReceiptStateLabel("suppressed"))
    }

    @Test fun sevenCategoriesAreUniqueAndCoverOnlyNewDesignTypes() {
        assertEquals(7, orbisSentinelCategories.size)
        val included = orbisSentinelCategories.flatMap { it.types }
        assertEquals(included.size, included.toSet().size)
        assertEquals(setOf(OrbisSentinelType.RITUAL, OrbisSentinelType.AGREEMENT, OrbisSentinelType.SCREEN_OBSERVATION,
            OrbisSentinelType.NIGHT_USAGE, OrbisSentinelType.LOW_BATTERY, OrbisSentinelType.SCREEN_ON,
            OrbisSentinelType.GEOFENCE, OrbisSentinelType.TOUCH), included.toSet())
    }

    @Test fun newTypesShowActualConditionsRatherThanClaimingAlreadyTriggered() {
        assertEquals("每天 23:30–24:00 内随机一次", orbisSentinelConditionLabel(rule(OrbisSentinelType.RITUAL)
            .copy(dailyAtLocal = "23:30", dailyWindowEndLocal = "24:00")))
        assertEquals("会话无新活动达到 30 分钟，按 50% 概率唤醒", orbisSentinelConditionLabel(rule(OrbisSentinelType.AGREEMENT)
            .copy(thresholdMs = 1_800_000, probabilityPercent = 50)))
        assertTrue(orbisSentinelConditionLabel(rule(OrbisSentinelType.GEOFENCE)).contains("人类"))
        assertTrue(orbisSentinelConditionLabel(rule(OrbisSentinelType.TOUCH)).contains("真实触摸"))
        OrbisSentinelType.entries.forEach { assertTrue(orbisSentinelConditionLabel(rule(it)).isNotBlank()) }
    }
}
