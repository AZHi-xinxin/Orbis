package me.rerere.rikkahub.ui.pages.chat

import me.rerere.asr.ASRTermCorrectionRule
import me.rerere.asr.ASRTermCorrectionSettings
import me.rerere.asr.asrDevicePronunciation
import me.rerere.asr.correctDeviceAsrTranscript
import me.rerere.asr.isAsrPhoneticCorrectionAvailable
import me.rerere.asr.validateAsrCorrectionRules
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Pure synthetic strings through Android's offline ICU. No recorder, service, storage or network. */
class AsrRegisteredNameDeviceTest {
    @Test fun registeredNamesUseRealOfflinePronunciationAndKeepRaw() {
        assumeTrue(isAsrPhoneticCorrectionAvailable())
        val rules = listOf(ASRTermCorrectionRule("阿止", phoneticMatch = true), ASRTermCorrectionRule("沈止戈", phoneticMatch = true))
        assertNull(validateAsrCorrectionRules(rules, ::asrDevicePronunciation))
        val raw = "阿直，神指歌，神之歌，沈直歌，未登记的名字。"
        val result = correctDeviceAsrTranscript(raw, ASRTermCorrectionSettings(true, rules))
        assertEquals("阿止，沈止戈，沈止戈，沈止戈，未登记的名字。", result.corrected)
        assertEquals(raw, result.original)
        // Another complete registered name proves this is a surname rule, not a private full-name special case.
        val generic = ASRTermCorrectionSettings(true, listOf(ASRTermCorrectionRule("沈明", phoneticMatch = true)))
        val genericRaw = "神鸣、沈鸣、陈鸣、神指歌"
        val genericResult = correctDeviceAsrTranscript(genericRaw, generic)
        assertEquals("沈明、沈明、陈鸣、神指歌", genericResult.corrected)
        assertEquals(genericRaw, genericResult.original)
    }

    @Test fun realOfflinePronunciationRefusesCollisionsAndRespectsDisabledRules() {
        assumeTrue(isAsrPhoneticCorrectionAvailable())
        val rules = listOf(ASRTermCorrectionRule("陈明", phoneticMatch = true), ASRTermCorrectionRule("晨鸣", phoneticMatch = true))
        assertNotNull(validateAsrCorrectionRules(rules, ::asrDevicePronunciation))
        assertEquals("陈鸣", correctDeviceAsrTranscript("陈鸣", ASRTermCorrectionSettings(true, rules)).corrected)
        assertEquals("阿直", correctDeviceAsrTranscript("阿直", ASRTermCorrectionSettings(false,
            listOf(ASRTermCorrectionRule("阿止", phoneticMatch = true)))).corrected)
        val surnameCollision = listOf(ASRTermCorrectionRule("沈明", phoneticMatch = true),
            ASRTermCorrectionRule("神明", listOf("旧名")))
        assertNotNull(validateAsrCorrectionRules(surnameCollision, ::asrDevicePronunciation))
        val raw = "神鸣、沈鸣、沈明、神明"
        assertEquals(raw, correctDeviceAsrTranscript(raw, ASRTermCorrectionSettings(true, surnameCollision)).corrected)
        assertEquals("神鸣", correctDeviceAsrTranscript("神鸣", ASRTermCorrectionSettings(false,
            listOf(ASRTermCorrectionRule("沈明", phoneticMatch = true)))).corrected)
    }
}
