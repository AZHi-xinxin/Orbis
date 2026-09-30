package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OrbisReplySlicesTest {
    private val allActions = OrbisChatFlowSettings(
        distinguishActions = true, markers = OrbisActionMarker.entries.toSet(),
    )

    private fun split(text: String, settings: OrbisChatFlowSettings = allActions): List<OrbisReplySlice> =
        splitOrbisReply(text, settings).also { slices ->
            assertTrue(slices.isNotEmpty())
            assertEquals(0, slices.first().start)
            assertEquals(text.length, slices.last().endExclusive)
            slices.zipWithNext().forEach { (left, right) -> assertEquals(left.endExclusive, right.start) }
            slices.forEach { assertTrue(it.endExclusive >= it.start) }
            assertEquals(text, slices.joinToString("") { text.substring(it.start, it.endExclusive) })
        }

    private fun actions(text: String, settings: OrbisChatFlowSettings = allActions): List<String> =
        split(text, settings).filter { it.action }.map { text.substring(it.start, it.endExclusive) }

    private fun pieces(text: String, settings: OrbisChatFlowSettings = allActions): List<String> =
        split(text, settings).map { text.substring(it.start, it.endExclusive) }

    @Test fun disabledLayoutIsExactlyOneOriginalSlice() {
        val text = "（摸头）\n\n正文。\n\n[伸手]"
        assertEquals(listOf(OrbisReplySlice(0, text.length)), split(text, allActions.copy(enabled = false)))
    }

    @Test fun defaultDoesNotInferActionsButStillSplitsParagraphs() {
        val text = "（摸头）\n\n正文。\n\n[伸手]"
        assertEquals(listOf("（摸头）\n\n", "正文。\n\n", "[伸手]"), pieces(text, OrbisChatFlowSettings()))
        assertTrue(actions(text, OrbisChatFlowSettings()).isEmpty())
    }

    @Test fun noMarkersDoesNotInferActions() {
        assertTrue(actions("（摸头）[招手]", allActions.copy(markers = emptySet())).isEmpty())
    }

    @Test fun turningOffDistinctionPreservesRememberedOptionsWithoutApplyingThem() {
        assertTrue(actions("（摸头）[招手]", allActions.copy(distinguishActions = false, collapseActions = true)).isEmpty())
    }

    @Test fun foldingIsPresentationOnlyAndDoesNotChangeRanges() {
        val text = "先说。\n\n（摸头）再说。"
        assertEquals(split(text), split(text, allActions.copy(collapseActions = true)))
    }

    @Test fun softLineBreaksAndSentencesAreNotSeparateBubbles() {
        val text = "第一句。第二句！\n这只是软换行。\n仍是同一段。"
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun hardLineBreaksAlsoRemainWithinParagraph() {
        val text = "one  \ntwo\\\nthree"
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun mixedDialogueAndCompleteActionsPreserveExactOrderAndText() {
        val text = "先说一句。（摸摸头）再说一句。[笑]最后一句。"
        assertEquals(listOf("先说一句。", "（摸摸头）", "再说一句。", "[笑]", "最后一句。"), pieces(text))
        assertEquals(listOf("（摸摸头）", "[笑]"), actions(text))
    }

    @Test fun allSupportedMarkerPairsCanBeChosenIndependently() {
        OrbisActionMarker.entries.forEach { selected ->
            val text = "说话${selected.open}动作${selected.close}说话"
            assertEquals(listOf("${selected.open}动作${selected.close}"),
                actions(text, allActions.copy(markers = setOf(selected))))
            assertEquals(emptyList<String>(), actions(text, allActions.copy(markers = emptySet())))
        }
    }

    @Test fun unselectedMarkersStayNormalText() {
        val text = "（摸头）『招手』[笑]"
        assertEquals(listOf("『招手』"), actions(text,
            allActions.copy(markers = setOf(OrbisActionMarker.DOUBLE_CORNER))))
    }

    @Test fun balancedNestedMarkersAreOneOuterAction() {
        assertEquals(listOf("（伸手[再『笑』一下]）"), actions("说（伸手[再『笑』一下]）好。"))
    }

    @Test fun selectedOuterBalancesUnselectedInnerMarkers() {
        assertEquals(listOf("（伸手[再笑一下]）"), actions("（伸手[再笑一下]）",
            allActions.copy(markers = setOf(OrbisActionMarker.FULLWIDTH_ROUND))))
    }

    @Test fun repeatedBalancedMarkerNestingIsIterative() {
        val text = "（".repeat(2_000) + "笑" + "）".repeat(2_000)
        assertEquals(listOf(text), actions(text))
    }

    @Test fun unclosedAndMismatchedTailIsVisibleAndLossless() {
        listOf("前文（抬手", "前文（外层[已闭合]", "前文（错配]后文）", "[外层（内层]）")
            .forEach { assertTrue(actions(it).isEmpty()) }
    }

    @Test fun validActionBeforeIncompleteTailRemainsIdentified() {
        val text = "（点头）继续说。[还没说完"
        assertEquals(listOf("（点头）"), actions(text))
        assertTrue(pieces(text).last().endsWith("[还没说完"))
    }

    @Test fun streamingPrefixesNeverDropCharactersOrInventClosingMarkers() {
        val text = "😀先说话。（伸手[握住]）再说话。\n\n『点头』"
        for (length in 0..text.length) split(text.take(length))
    }

    @Test fun escapedDelimitersAreNotActions() {
        assertTrue(actions("\\(literal\\) \\[literal\\]").isEmpty())
        assertEquals(listOf("(wave\\) again)"), actions("(wave\\) again)"))
    }

    @Test fun escapingBackslashDoesNotEscapeFollowingRealDelimiter() {
        assertEquals(listOf("(wave)"), actions("\\\\(wave)"))
    }

    @Test fun fencedAndIndentedCodeStayWholeAndNeverBecomeActions() {
        listOf("```kotlin\nfun f() = listOf(\"[x]\")\n\n//（注释）\n```", "    x = (1)\n\n    y = [2]")
            .forEach { assertTrue(actions(it).isEmpty()); assertEquals(listOf(it), pieces(it)) }
    }

    @Test fun listsAndNestedListsAreOneBlock() {
        val text = "- （第一项）\n- [第二项]\n  - 『子项』\n\n  延续这一项。"
        assertTrue(actions(text).isEmpty())
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun quotedParagraphsAndLazyContinuationAreOneBlock() {
        val text = "> （引用）\n延续引用[不是动作]\n>\n> 第二段『仍然引用』"
        assertTrue(actions(text).isEmpty())
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun markdownTableIsOneBlock() {
        val text = "| 项目 | 数值 |\n| --- | --- |\n| （名字） | [值] |\n| 『内容』 | 2 |"
        assertTrue(actions(text).isEmpty())
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun headingsAreNotActions() {
        assertTrue(actions("# （标题）\n\n普通正文").isEmpty())
        assertTrue(actions("（标题）\n===\n\n正文").isEmpty())
    }

    @Test fun distinctStructuralBlocksStayWholeInOriginalOrder() {
        val text = "开头\n\n```\n(a)\n\n[b]\n```\n\n- [one]\n- [two]\n\n末尾"
        assertEquals(listOf("开头\n\n", "```\n(a)\n\n[b]\n```\n\n", "- [one]\n- [two]\n\n", "末尾"), pieces(text))
        assertTrue(actions(text).isEmpty())
    }

    @Test fun inlineCodeAndMathStayVisibleWhileSeparateActionWorks() {
        val text = "`（code）`和\$[x]+(y)\$，然后（点头）"
        assertEquals(listOf("（点头）"), actions(text))
        assertTrue(pieces(text).first().contains("`（code）`"))
    }

    @Test fun incompleteInlineMathAndCodeDoNotBrieflyBecomeActions() {
        assertTrue(actions("代码`[unfinished]").isEmpty())
        assertTrue(actions("公式\$[unfinished]").isEmpty())
    }

    @Test fun displayAndLatexMathRemainTogetherAcrossBlankLines() {
        listOf("before\n\n\$\$\n[x]\n\ny=(1)\n\$\$\n\nafter", "before\n\\[\nx=(1)\n\ny=[2]\n\\]\nafter", "\\((x) + [y]\\)")
            .forEach { assertTrue(actions(it).isEmpty()); assertEquals(listOf(it), pieces(it)) }
    }

    @Test fun linksImagesAutolinksAndCitationsAreNotActions() {
        val text = "[（链接）](https://example.invalid/a(b)) ![图片](https://x/a.png) <https://x/y(z)> [citation,site](1) [1] 【2†source】 （招手）"
        assertEquals(listOf("（招手）"), actions(text))
    }

    @Test fun unclosedLinkDoesNotHideLabelOrDestinationWhileStreaming() {
        listOf("[label](https://example.invalid/a(b", "![image](https://x", "[caption][unfinished")
            .forEach { assertTrue(actions(it).isEmpty()) }
    }

    @Test fun referenceDefinitionsKeepDocumentTogetherAndReferenceMeaningIntact() {
        val text = "[链接][id]\n\n（动作）\n\n[id]: https://example.invalid/path"
        assertEquals(listOf(text), pieces(text))
        assertTrue(actions(text).isEmpty())
    }

    @Test fun unresolvedFullReferencesStillDoNotBecomeActions() {
        assertTrue(actions("[caption][unknown]").isEmpty())
    }

    @Test fun formattingAcrossActionBoundariesStaysUntouched() {
        listOf("（开始**加粗）结束**", "**开始（加粗结束**）", "**正文（点头）正文**",
            "*正文（点头）*", "**（点头）还有话**", "**（点头）（挥手）**", "~~[删除线]~~")
            .forEach { assertTrue(actions(it).isEmpty()) }
        assertEquals(listOf("（挥手）"), actions("**重要**。然后（挥手）"))
    }

    @Test fun completeEmphasisInsideSelectedActionsPreservesActionRecognition() {
        listOf("（**点头**）", "（轻轻**点头**）", "（开始，结束——**加粗。**）",
            "（开始。**中间，结束。**）", "（*点头*）", "（_点头_）", "（__点头__）",
            "（**加粗和*斜体***）", "（***点头***）", "（**点头**并且*挥手*）")
            .forEach { assertEquals(it, listOf(it), actions(it)) }
    }

    @Test fun emphasisWrappingOnlyAnActionIsIncludedWithoutOrphanMarkers() {
        listOf("**（点头）**", "*（点头）*", "__（点头）__", "_（点头）_",
            "***（点头）***", "**（*点头*）**", "*（**点头**）*", "**（点头[微笑]）**")
            .forEach {
                assertEquals(it, listOf(it), actions(it))
                assertEquals(it, listOf(it), pieces(it))
            }
    }

    @Test fun formattedActionsBetweenSpeechRemainLosslessAndOrdered() {
        val text = "先说。**（点头）**再说。（轻轻**挥手**）最后说。\n\n*（微笑）*"
        assertEquals(listOf("先说。", "**（点头）**", "再说。", "（轻轻**挥手**）", "最后说。\n\n", "*（微笑）*"), pieces(text))
        assertEquals(listOf("**（点头）**", "（轻轻**挥手**）", "*（微笑）*"), actions(text))
    }

    @Test fun emphasisDoesNotRemoveNestedCodeLinkMathOrCitationProtection() {
        listOf("（**`code`**）", "（**[链接](https://example.invalid)**）",
            "**[（链接）](https://example.invalid)**", "（**![图片](https://example.invalid/a.png)**）",
            "**`（代码）`**", "（**\$x\$**）", "**[1]**", "**【2†source】**", "~~（点头）~~")
            .forEach { assertTrue(it, actions(it).isEmpty()) }
    }

    @Test fun selectedMarkersAndNonePreferenceStillApplyInsideEmphasis() {
        OrbisActionMarker.entries.forEach { marker ->
            val text = "**${marker.open}动作${marker.close}**"
            assertEquals(listOf(text), actions(text, allActions.copy(markers = setOf(marker))))
            assertTrue(actions(text, allActions.copy(markers = emptySet())).isEmpty())
            assertTrue(actions(text, allActions.copy(distinguishActions = false)).isEmpty())
        }
        assertTrue(actions("**(点头)**", allActions.copy(markers = setOf(OrbisActionMarker.FULLWIDTH_ROUND))).isEmpty())
        assertTrue(actions("**（点头)**").isEmpty())
        assertTrue(actions("**(点头）**").isEmpty())
    }

    @Test fun streamingFormattedPrefixesAreLosslessAndFinalActionsRecognized() {
        listOf("正文（开始，**加粗结束。**）后文", "先说。***（点头）***后说。",
            "（开始**加粗）结束**", "**正文（动作）正文**").forEach { text ->
            for (length in 0..text.length) split(text.take(length))
        }
        assertEquals(listOf("（开始，**加粗结束。**）"), actions("正文（开始，**加粗结束。**）后文"))
        assertEquals(listOf("***（点头）***"), actions("先说。***（点头）***后说。"))
    }

    @Test fun boldOutsideActionDoesNotDisableSameParagraphOrLaterParagraphActions() {
        listOf("**标题**（说明）：\n（点头）", "**标题**。然后（点头）",
            "**标题**\n\n（点头）", "**标题**（说明）：\n说话（点头）结束")
            .forEach { assertTrue(it, "（点头）" in actions(it)) }
    }

    @Test fun literalHtmlInInlineCodeDoesNotDisableOtherParagraphActions() {
        val text = "**标题：**\n\n**第一段**（说明）：\n（点头）\n\n" +
            "**第二段**（说明）：\n（轻轻**挥手**）\n\n**第三段**：\n（**微笑**）\n\n" +
            "（说明——**强调**：示例 `<strong>` 是代码。**结束。**）"
        assertEquals(listOf("（说明）", "（点头）", "（说明）", "（轻轻**挥手**）", "（**微笑**）"), actions(text))
        assertTrue(pieces(text).last().contains("`<strong>`"))
        val actualHtml = text.replace("`<strong>`", "<strong>")
        assertTrue(actions(actualHtml).isEmpty())
        assertEquals(listOf(actualHtml), pieces(actualHtml))
    }

    @Test fun htmlTokensInsideCodeRemainLiteralWithoutDisablingIndependentActions() {
        listOf("`<strong>（代码）</strong>`", "``<span title=\"`\">[代码]</span>``",
            "```html\n<div>\n（代码）\n\n[代码]\n</div>\n```",
            "    <strong>（代码）</strong>").forEach { code ->
            val text = "$code\n\n（**点头**）\n\n**（挥手）**"
            assertEquals(code, listOf("（**点头**）", "**（挥手）**"), actions(text))
            assertEquals(code + "\n\n", pieces(text).first())
        }
    }

    @Test fun realHtmlOutsideLiteralCodeStillPreservesWholeMessageScope() {
        listOf("`<strong>`\n\n<span>（真 HTML）</span>\n\n（点头）",
            "<div>\n\n`<strong>`\n\n（真 HTML 内容）\n\n</div>\n\n（点头）",
            "代码 `<strong>`，实际 <strong>加粗</strong>（点头）").forEach { text ->
            assertTrue(actions(text).isEmpty())
            assertEquals(listOf(text), pieces(text))
        }
    }

    @Test(timeout = 15_000) fun manyInlineFormattedActionsDoNotRescanEveryFormattingSpan() {
        val text = "说话**（点头）**继续（轻轻**挥手**）。".repeat(2_000)
        assertEquals(4_000, actions(text).size)
    }

    @Test fun htmlBlockAndInlineTagsStayUntouched() {
        listOf("<div>\n（内容）\n\n[内容]\n</div>", "<span>（内容）</span>以及[其他]")
            .forEach { assertTrue(actions(it).isEmpty()); assertEquals(listOf(it), pieces(it)) }
    }

    @Test fun blankLinesInsideHtmlDoNotReclassifyItsBodyAsMarkdownActions() {
        val text = "正文\n\n<div class=\"panel\">\n第一行\n\n[标签]\n\n（说明）\n\n</div>\n\n结尾"
        assertTrue(actions(text).isEmpty())
        assertEquals(listOf(text), pieces(text))
    }

    @Test fun stickerReferenceAndImmutableTagSnapshotStayTogether() {
        val snapshot = stickerDraftText("st000123", listOf("（拥抱）", "[陪伴]"))
        assertEquals(listOf(snapshot), pieces(snapshot))
        assertTrue(actions(snapshot).isEmpty())
        assertTrue(actions("(表情包:st000123)").isEmpty())
    }

    @Test fun malformedStickerSnapshotIsNotPartiallyHidden() {
        assertTrue(actions("(表情包:st000123)\n[表情标签] {\"tags\": [\"(x)\"]").isEmpty())
    }

    @Test fun leadingTrailingWhitespaceCrlfAndEmojiAreNeverNormalized() {
        val text = "\r\n第一段😀\r\n软换行\r\n\r\n（🫶🏽拥抱）\r\n\r\n末尾  \r\n"
        val slices = split(text)
        assertEquals(listOf("（🫶🏽拥抱）"), actions(text))
        assertTrue(slices.size >= 3)
        assertTrue(pieces(text).first().startsWith("\r\n"))
        assertTrue(pieces(text).last().endsWith("  \r\n"))
    }

    @Test fun emptyAndWhitespaceOnlyContentAreLossless() {
        listOf("", " ", "\r\n\n\t  ").forEach { assertEquals(listOf(it), pieces(it)) }
    }

    @Test(timeout = 15_000) fun largeReplyScanDoesNotSearchEntireSuffixPerBracket() {
        val text = buildString {
            repeat(4_000) { append("正文（点头）还有话[挥手]。\n\n") }
            append("（".repeat(20_000))
        }
        assertEquals(8_000, split(text).count { it.action })
    }

    @Test(timeout = 15_000) fun concurrentConversationsUseIndependentParserState() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 32).map { index ->
                pool.submit<List<String>> { actions("对话$index（点头）\n\n下一段[挥手]") }
            }
            futures.forEach { assertEquals(listOf("（点头）", "[挥手]"), it.get(10, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }
}
