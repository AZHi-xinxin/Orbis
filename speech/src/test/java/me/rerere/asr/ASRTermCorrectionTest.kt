package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ASRTermCorrectionTest {
    private fun settings(vararg rules: ASRTermCorrectionRule) = ASRTermCorrectionSettings(true, rules.toList())
    private val soundGroups = mapOf("a" to "阿啊", "zhi" to "止直纸知支指之", "shen" to "沈神", "ge" to "歌戈",
        "ming" to "明鸣", "wang" to "王汪", "chen" to "陈晨", "lü" to "吕旅", "lu" to "路")
    private val pronunciation: (Char) -> String? = { ch -> soundGroups.entries.firstOrNull { ch in it.value }?.key }
    // Android 10's CLDR Han-Latin maps standalone 沈 to chen, unlike its surname reading.
    private val legacySurnamePronunciation: (Char) -> String? = { ch -> if (ch == '沈') "chen" else pronunciation(ch) }

    @Test fun oldOrDisabledConfigurationLeavesRecognitionUntouched() {
        assertEquals(ASRTermCorrectionSettings(), Json.decodeFromString<ASRTermCorrectionSettings>("{}"))
        assertEquals("阿纸", correctAsrTranscript("阿纸", ASRTermCorrectionSettings()).corrected)
    }

    @Test fun correctsOnlySuppliedTranscriptAndKeepsExactOriginal() {
        val result = correctAsrTranscript("阿纸，你在吗？", settings(ASRTermCorrectionRule("阿止", listOf("阿纸", "阿知"))))
        assertEquals("阿纸，你在吗？", result.original)
        assertEquals("阿止，你在吗？", result.corrected)
        assertTrue(result.changed)
    }

    @Test fun longestAliasWinsAndReplacementNeverCascades() {
        val result = correctAsrTranscript("阿纸哥哥，阿纸。", settings(
            ASRTermCorrectionRule("阿止", listOf("阿纸")),
            ASRTermCorrectionRule("哥哥", listOf("阿纸哥哥")),
            ASRTermCorrectionRule("错误", listOf("阿止")),
        ))
        assertEquals("哥哥，阿止。", result.corrected)
    }

    @Test fun aliasesAreLiteralAndLatinWordsDoNotMatchInsideOtherWords() {
        val result = correctAsrTranscript("Al Alice Al_1 A.l [Al]", settings(
            ASRTermCorrectionRule("艾尔", listOf("Al")), ASRTermCorrectionRule("点名", listOf("A.l")),
        ))
        assertEquals("艾尔 Alice Al_1 点名 [艾尔]", result.corrected)
    }

    @Test fun emptyAndMalformedRulesCannotInsertOrDeleteText() {
        assertEquals("原文", correctAsrTranscript("原文", settings(
            ASRTermCorrectionRule("", listOf("原")), ASRTermCorrectionRule("名", listOf("")),
        )).corrected)
    }

    @Test fun duplicateAliasOwnersAreRejectedAndRoundTripIsCompatible() {
        assertNotNull(validateAsrCorrectionRules(listOf(
            ASRTermCorrectionRule("甲", listOf("错名")), ASRTermCorrectionRule("乙", listOf("错名")),
        )))
        val value = settings(ASRTermCorrectionRule("阿止", listOf("阿纸")))
        assertNull(validateAsrCorrectionRules(value.rules))
        assertEquals(value, Json.decodeFromString<ASRTermCorrectionSettings>(Json.encodeToString(ASRTermCorrectionSettings.serializer(), value)))
    }

    @Test fun oldRuleJsonDoesNotSilentlyEnablePhoneticMatching() {
        val old = Json.decodeFromString<ASRTermCorrectionRule>("""{"target":"阿止","aliases":["阿纸"]}""")
        assertFalse(old.phoneticMatch)
        assertEquals("阿直", correctAsrTranscript("阿直", settings(old), pronunciation).corrected)
        assertEquals("阿止", correctAsrTranscript("阿纸", settings(old), pronunciation).corrected)
    }

    @Test fun registeredCompleteNamesMatchSameSyllablesWithoutGuessingUnregisteredNames() {
        val value = settings(ASRTermCorrectionRule("阿止", phoneticMatch = true), ASRTermCorrectionRule("沈止戈", phoneticMatch = true))
        val raw = "阿直，神指歌和神之歌都在吗？阿明、别的名字不变。"
        val result = correctAsrTranscript(raw, value, pronunciation)
        assertEquals("阿止，沈止戈和沈止戈都在吗？阿明、别的名字不变。", result.corrected)
        assertEquals(raw, result.original)
        assertNull(validateAsrCorrectionRules(value.rules, pronunciation))
    }

    @Test fun phoneticMatchingIsOptionalAndCannotRunWithoutAnOfflineReader() {
        val value = settings(ASRTermCorrectionRule("阿止", listOf("阿纸"), phoneticMatch = true))
        assertEquals("阿直、阿止", correctAsrTranscript("阿直、阿纸", value).corrected)
        assertEquals("阿直、阿纸", correctAsrTranscript("阿直、阿纸", value.copy(enabled = false), pronunciation).corrected)
    }

    @Test fun homophoneTargetCollisionRejectsSaveAndFailsClosedAtRuntime() {
        val value = settings(ASRTermCorrectionRule("阿止", phoneticMatch = true), ASRTermCorrectionRule("阿知", listOf("旧名")))
        assertNotNull(validateAsrCorrectionRules(value.rules, pronunciation))
        assertEquals("阿直、阿知、阿止", correctAsrTranscript("阿直、阿知、阿止", value, pronunciation).corrected)
    }

    @Test fun malformedDuplicateAliasesNeverChooseFirstOwnerOrFallThroughToPhonetics() {
        val value = settings(ASRTermCorrectionRule("阿止", listOf("神之歌"), phoneticMatch = true),
            ASRTermCorrectionRule("沈止戈", listOf("神之歌"), phoneticMatch = true))
        assertEquals("神之歌", correctAsrTranscript("神之歌", value, pronunciation).corrected)
    }

    @Test fun exactAliasesStillWinAndPhoneticReplacementDoesNotCascade() {
        val value = settings(ASRTermCorrectionRule("阿止", phoneticMatch = true), ASRTermCorrectionRule("明", listOf("阿直")))
        assertEquals("明、阿止", correctAsrTranscript("阿直、啊纸", value, pronunciation).corrected)
    }

    @Test fun incompleteNamesUnknownReadingsLatinAndFuzzySoundsAreNotCorrected() {
        val value = settings(ASRTermCorrectionRule("沈止戈", phoneticMatch = true), ASRTermCorrectionRule("阿止", phoneticMatch = true))
        val raw = "神、指歌、神指、神明歌、阿？直、a zhi、沈止戈"
        assertEquals(raw, correctAsrTranscript(raw, value, pronunciation).corrected)
        assertNotNull(validateAsrCorrectionRules(listOf(ASRTermCorrectionRule("未知", phoneticMatch = true)), pronunciation))
    }

    @Test fun phoneticRuleValidationRejectsSingleLongAndNonHanTargets() {
        listOf("阿", "七个汉字太长了", "AI", "阿-止", "阿止1").forEach { name ->
            assertNotNull(validateAsrCorrectionRules(listOf(ASRTermCorrectionRule(name, phoneticMatch = true)), pronunciation))
        }
    }

    @Test fun vowelDiaeresisAndSyllableBoundariesRemainDistinct() {
        val value = settings(ASRTermCorrectionRule("吕明", phoneticMatch = true))
        assertEquals("吕明、路明", correctAsrTranscript("旅鸣、路明", value, pronunciation).corrected)
    }

    @Test fun phoneticRulesRoundTripWithoutChangingCallerSettings() {
        val value = settings(ASRTermCorrectionRule("阿止", phoneticMatch = true))
        val encoded = Json.encodeToString(ASRTermCorrectionSettings.serializer(), value)
        correctAsrTranscript("阿直", value, pronunciation)
        assertEquals(value, Json.decodeFromString<ASRTermCorrectionSettings>(encoded))
        assertEquals(encoded, Json.encodeToString(ASRTermCorrectionSettings.serializer(), value))
    }

    @Test fun registeredSurnameWorksWithLegacyDefaultForBothHomophonesAndSameGlyph() {
        val value = settings(ASRTermCorrectionRule("沈止戈", phoneticMatch = true),
            ASRTermCorrectionRule("沈明", phoneticMatch = true))
        val raw = "神指歌、神之歌、沈直歌、神鸣、沈鸣、陈鸣、沈、阿明"
        val result = correctAsrTranscript(raw, value, legacySurnamePronunciation)
        assertEquals("沈止戈、沈止戈、沈止戈、沈明、沈明、陈鸣、沈、阿明", result.corrected)
        assertEquals(raw, result.original)
        assertNull(validateAsrCorrectionRules(value.rules, legacySurnamePronunciation))
    }

    @Test fun surnameExceptionDoesNotReinterpretGivenNameGlyphsOrUnregisteredWords() {
        val value = settings(ASRTermCorrectionRule("王沈", phoneticMatch = true))
        val raw = "汪晨、汪神、神鸣、沈鸣、沈止戈"
        assertEquals("王沈、汪神、神鸣、沈鸣、沈止戈",
            correctAsrTranscript(raw, value, legacySurnamePronunciation).corrected)
        assertEquals("chen", legacySurnamePronunciation('沈'))
        assertNotNull(validateAsrCorrectionRules(listOf(ASRTermCorrectionRule("沈未知", phoneticMatch = true)),
            legacySurnamePronunciation))
    }

    @Test fun surnameNormalizedTargetsStillRejectHomophoneCollisions() {
        val value = settings(ASRTermCorrectionRule("沈明", phoneticMatch = true), ASRTermCorrectionRule("神明", listOf("旧名")))
        assertNotNull(validateAsrCorrectionRules(value.rules, legacySurnamePronunciation))
        assertEquals("神鸣、沈鸣、沈明、神明",
            correctAsrTranscript("神鸣、沈鸣、沈明、神明", value, legacySurnamePronunciation).corrected)
    }

    @Test fun contextDependentMatchesFailClosedInsteadOfChoosingRuleOrder() {
        val rules = listOf(ASRTermCorrectionRule("沈明", phoneticMatch = true), ASRTermCorrectionRule("陈明", phoneticMatch = true))
        for (ordered in listOf(rules, rules.reversed())) {
            val raw = "沈鸣、神鸣、陈鸣"
            val result = correctAsrTranscript(raw, ASRTermCorrectionSettings(true, ordered), legacySurnamePronunciation)
            assertEquals("沈鸣、沈明、陈明", result.corrected)
            assertEquals(raw, result.original)
        }
    }

    @Test fun surnameSupportDoesNotEnableRulesOrOverrideExplicitAliases() {
        val rule = ASRTermCorrectionRule("沈明", listOf("神鸣"))
        assertEquals("沈鸣、沈明", correctAsrTranscript("沈鸣、神鸣", settings(rule), legacySurnamePronunciation).corrected)
        assertEquals("沈鸣、神鸣", correctAsrTranscript("沈鸣、神鸣", settings(rule).copy(enabled = false),
            legacySurnamePronunciation).corrected)
        val explicit = settings(rule.copy(phoneticMatch = true), ASRTermCorrectionRule("王明", listOf("沈鸣")))
        assertEquals("王明、沈明", correctAsrTranscript("沈鸣、神鸣", explicit, legacySurnamePronunciation).corrected)
        assertEquals("沈鸣", correctAsrTranscript("沈鸣", settings(rule.copy(phoneticMatch = true))).corrected)
    }
}
