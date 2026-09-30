package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test

class OrbisRoleOpacityTest {
    private fun assertRoles(appearance: OrbisAppearance, user: Float, assistant: Float) {
        assertEquals(user, appearance.bubbleOpacityForRole(true), 0f)
        assertEquals(assistant, appearance.bubbleOpacityForRole(false), 0f)
    }

    @Test fun legacyCustomizedValuesAreInheritedSeparatelyForBothThemes() {
        val old = Json.decodeFromString<DisplaySetting>("""{
            "userNickname":"Synthetic",
            "orbisAppearance":{"bubbleOpacity":0.37,"eventOpacity":0.18},
            "deepSeekAppearance":{"bubbleOpacity":0.81,"composerOpacity":0.45}
        }""")
        assertRoles(old.appearanceForStyle(false), .37f, .37f)
        assertRoles(old.appearanceForStyle(true), .81f, .81f)
        assertNull(old.orbisAppearance.userBubbleOpacity)
        assertNull(old.deepSeekAppearance.assistantBubbleOpacity)
        assertEquals(.18f, old.orbisAppearance.eventOpacity, 0f)
        assertEquals(.45f, old.deepSeekAppearance.composerOpacity, 0f)
    }

    @Test fun defaultOrbisAndDeepSeekAppearancesAreUnchangedUntilEdited() {
        assertRoles(OrbisAppearance(), .92f, .92f)
        assertRoles(deepSeekDefaultAppearance(), 1f, 1f)
    }

    @Test fun firstRoleEditDoesNotChangeOtherRoleOrLegacyValue() {
        val old = OrbisAppearance(bubbleOpacity = .43f)
        val user = old.copy(userBubbleOpacity = .17f)
        assertRoles(user, .17f, .43f)
        val assistant = old.copy(assistantBubbleOpacity = .89f)
        assertRoles(assistant, .43f, .89f)
        assertEquals(.43f, user.bubbleOpacity, 0f)
        assertEquals(.43f, assistant.bubbleOpacity, 0f)
    }

    @Test fun zeroIsAnExplicitOverrideNotAMissingPreference() {
        assertRoles(OrbisAppearance(bubbleOpacity = .88f, userBubbleOpacity = 0f, assistantBubbleOpacity = 1f), 0f, 1f)
    }

    @Test fun bothOverridesRoundTripWithUnrelatedFieldsUnchanged() {
        val original = DisplaySetting(userNickname = "Synthetic", userAvatar = Avatar.Emoji("☁"),
            orbisAppearance = OrbisAppearance(bubbleOpacity = .72f, userBubbleOpacity = .4f,
                assistantBubbleOpacity = .8f, composerOpacity = .3f, eventOpacity = .1f),
            deepSeekAppearance = deepSeekDefaultAppearance().copy(userBubbleOpacity = .9f, assistantBubbleOpacity = .2f))
        val restored = Json.decodeFromString<DisplaySetting>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertRoles(restored.appearanceForStyle(false), .4f, .8f)
        assertRoles(restored.appearanceForStyle(true), .9f, .2f)
    }

    @Test fun normalizationClampsOverridesAndFallsBackOnInvalidValues() {
        assertRoles(OrbisAppearance(userBubbleOpacity = -2f, assistantBubbleOpacity = 3f).normalized(), 0f, 1f)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val value = OrbisAppearance(bubbleOpacity = .61f, userBubbleOpacity = invalid, assistantBubbleOpacity = invalid)
            assertRoles(value, .61f, .61f)
            assertRoles(value.normalized(), .61f, .61f)
            assertRoles(value.copy(bubbleOpacity = invalid).normalized(), .92f, .92f)
        }
    }

    @Test fun changingOneThemePreservesOtherThemeAndEventComposerAndNickname() {
        val original = DisplaySetting(userNickname = "Synthetic", orbisAppearance = OrbisAppearance(eventOpacity = .19f),
            deepSeekAppearance = deepSeekDefaultAppearance().copy(composerOpacity = .55f, eventOpacity = .3f))
        val changed = original.withAppearanceForStyle(true) { it.copy(userBubbleOpacity = .25f, assistantBubbleOpacity = .66f) }
        assertEquals(original, changed.copy(deepSeekAppearance = original.deepSeekAppearance))
        assertEquals(.55f, changed.deepSeekAppearance.composerOpacity, 0f)
        assertEquals(.3f, changed.deepSeekAppearance.eventOpacity, 0f)
        assertRoles(changed.appearanceForStyle(true), .25f, .66f)
        assertRoles(changed.appearanceForStyle(false), .92f, .92f)
    }

    @Test fun resettingCurrentThemeClearsOnlyItsOverrides() {
        val original = DisplaySetting(userNickname = "Synthetic",
            orbisAppearance = OrbisAppearance(userBubbleOpacity = .3f, assistantBubbleOpacity = .4f),
            deepSeekAppearance = deepSeekDefaultAppearance().copy(userBubbleOpacity = .6f, assistantBubbleOpacity = .7f))
        val reset = original.withAppearanceForStyle(true) { deepSeekDefaultAppearance() }
        assertEquals(original, reset.copy(deepSeekAppearance = original.deepSeekAppearance))
        assertRoles(reset.appearanceForStyle(true), 1f, 1f)
        assertRoles(reset.appearanceForStyle(false), .3f, .4f)
    }
}
