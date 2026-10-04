package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.ai.mcp.McpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisSettingsPresentationTest {
    @Test fun noConnectionSnapshotIsUnknownNotConnected() {
        assertEquals("连接：暂无状态", orbisMcpOverview(true, true, null).connection)
    }

    @Test fun connectedDoesNotOverrideDisabledConfiguration() {
        val overview = orbisMcpOverview(false, true, McpStatus.Connected)
        assertEquals("配置：已关闭", overview.configured)
        assertEquals("连接：已连接", overview.connection)
        assertEquals("当前AI：已选用", overview.selected)
    }

    @Test fun globallyEnabledDoesNotImplyAssistantSelection() {
        val overview = orbisMcpOverview(true, false, McpStatus.Connected)
        assertEquals("配置：已开启", overview.configured)
        assertEquals("当前AI：未选用", overview.selected)
    }

    @Test fun missingAssistantIsNotReplacedByEnabled() {
        assertEquals("当前AI：未确定", orbisMcpOverview(true, null, McpStatus.Connected).selected)
    }

    @Test fun everyLiveConnectionKindHasASeparateFixedLabel() {
        val pairs = listOf(
            McpStatus.Idle to "连接：未连接", McpStatus.Connecting to "连接：连接中",
            McpStatus.Connected to "连接：已连接", McpStatus.Reconnecting(1, 4) to "连接：重连中",
            McpStatus.Error("synthetic detail") to "连接：出错",
            McpStatus.NeedsAuthorization to "连接：需要授权", McpStatus.Authorizing to "连接：授权中",
        )
        for ((state, expected) in pairs) assertEquals(expected, orbisMcpOverview(true, false, state).connection)
    }

    @Test fun errorMessageAndDetailNeverBecomeSummary() {
        val overview = orbisMcpOverview(true, true,
            McpStatus.Error("https://example.invalid/?token=synthetic", "Authorization: Bearer synthetic-value"))
        assertFalse(overview.toString().contains("example.invalid"))
        assertFalse(overview.toString().contains("synthetic"))
        assertEquals("连接：出错", overview.connection)
    }

    @Test fun labelsDoNotExposeUrlsOrObviousCredentialStrings() {
        listOf("https://example.invalid/", "Bearer synthetic-value", "api_key=synthetic",
            "token:synthetic", "sk-synthetic1234", "D:\\private\\config").forEach {
            assertEquals("hidden", orbisSettingsLabel(it, "hidden"))
        }
    }

    @Test fun labelsRemainNamesNotProviderDescriptions() {
        assertEquals("My provider", orbisSettingsLabel("  My provider  ", "hidden"))
        assertEquals("deepseek-example", orbisSettingsLabel("deepseek-example", "hidden"))
        assertEquals("未命名", orbisSettingsLabel(null, "未命名"))
        assertEquals("未命名", orbisSettingsLabel(" \n ", "未命名"))
    }

    @Test fun controlsAndBidirectionalMarksAreNotRetained() {
        val value = orbisSettingsLabel("Test\nname\u202Ehidden", "fallback")
        assertEquals("Test name hidden", value)
    }

    @Test fun longLabelsDoNotSplitSurrogatePairs() {
        val result = orbisSettingsLabel("🌟".repeat(100), "fallback")
        assertEquals(64, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("…"))
        assertFalse(result.dropLast(1).last().isHighSurrogate())
    }

    @Test fun speechUpdatePreservesTwoOtherRulesForEveryCombination() {
        for (auto in listOf(false, true)) for (quoted in listOf(false, true)) for (outside in listOf(false, true)) {
            val before = OrbisSpeechRules(auto, quoted, outside)
            assertEquals(OrbisSpeechRules(!auto, quoted, outside), before.withRule(OrbisSpeechRule.AUTO_READ, !auto))
            assertEquals(OrbisSpeechRules(auto, !quoted, outside), before.withRule(OrbisSpeechRule.QUOTED_ONLY, !quoted))
            assertEquals(OrbisSpeechRules(auto, quoted, !outside), before.withRule(OrbisSpeechRule.OUTSIDE_BRACKETS, !outside))
        }
    }

    @Test fun sequentialRuleChangesDoNotUndoPreviousRule() {
        val result = OrbisSpeechRules(false, false, false).withRule(OrbisSpeechRule.AUTO_READ, true)
            .withRule(OrbisSpeechRule.QUOTED_ONLY, true).withRule(OrbisSpeechRule.OUTSIDE_BRACKETS, true)
        assertEquals(OrbisSpeechRules(true, true, true), result)
    }

    @Test fun speechSetIsIdempotent() {
        val before = OrbisSpeechRules(true, false, true)
        assertEquals(before, before.withRule(OrbisSpeechRule.AUTO_READ, true))
        assertEquals(before, before.withRule(OrbisSpeechRule.QUOTED_ONLY, false))
    }

    @Test fun mcpDeepLinkStillTargetsSecondLazyItem() {
        assertEquals(0, orbisSettingsInitialItem(false))
        assertEquals(1, orbisSettingsInitialItem(true))
    }

    @Test fun savedPlaybackSpeedIsAnExactSavedReferenceNotRoundedProviderSpeed() {
        assertEquals("1.0×（已保存）", orbisSavedPlaybackSpeed(1.0f))
        assertEquals("1.25×（已保存）", orbisSavedPlaybackSpeed(1.25f))
    }

    @Test fun invalidPlaybackSpeedIsUnknownNotInventedDefault() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach {
            assertEquals("未确定", orbisSavedPlaybackSpeed(it))
        }
    }

    @Test fun toolDestinationsAreUniqueAndImplemented() {
        val destinations = orbisToolEntries.map { it.destination }
        assertEquals(destinations.size, destinations.toSet().size)
        assertEquals(OrbisToolDestination.entries.toSet(), destinations.toSet())
        assertTrue(orbisToolEntries.all { it.title.isNotBlank() && it.subtitle.isNotBlank() })
    }

    @Test fun workspaceGroupsFilesTerminalAndSkillsInsteadOfDuplicateTiles() {
        val workspace = orbisToolEntries.single { it.destination == OrbisToolDestination.WORKSPACE }
        assertEquals(OrbisToolGroup.WORK, workspace.group)
        listOf("文件", "终端", "技能").forEach { assertTrue(workspace.subtitle.contains(it)) }
        assertEquals(3, orbisToolEntries.count { it.group == OrbisToolGroup.WORK })
    }

    @Test fun toolsDoNotAdvertiseConfigurationOrUnavailablePlaceholders() {
        val labels = orbisToolEntries.joinToString(" ") { it.title + " " + it.subtitle }
        listOf("MCP", "TTS", "快捷消息", "天气", "未接入").forEach {
            assertFalse("Unexpected duplicated or unavailable tool: $it", labels.contains(it))
        }
        assertEquals(6, orbisToolEntries.count { it.group == OrbisToolGroup.PLAY })
        assertEquals(1, orbisToolEntries.count { it.destination == OrbisToolDestination.BLUETOOTH_TOY })
    }

    @Test fun scheduleAndPrivateRoomOccupyTheRequestedGridPositions() {
        val rows = orbisToolEntries.filter { it.group == OrbisToolGroup.PLAY }
            .map { it.destination }.chunked(3)
        assertEquals(listOf(OrbisToolDestination.GAMES, OrbisToolDestination.STICKERS,
            OrbisToolDestination.BLUETOOTH_TOY), rows[0])
        assertEquals(listOf(OrbisToolDestination.GALLERY, OrbisToolDestination.SCHEDULE,
            OrbisToolDestination.PRIVATE_ROOM), rows[1])
    }
}
