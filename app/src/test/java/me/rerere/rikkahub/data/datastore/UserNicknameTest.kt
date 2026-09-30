package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.OrbisAppearance
import org.junit.Assert.*
import org.junit.Test

class UserNicknameTest {
    @Test fun mergesIntoLatestDisplayWithoutReplacingOtherPreferences() {
        val latest = DisplaySetting(userNickname = "Old", userAvatar = Avatar.Emoji("☁"),
            showUserAvatar = false, enableAutoScroll = false,
            orbisAppearance = OrbisAppearance(userBubbleOpacity = .23f, assistantBubbleOpacity = .87f, eventOpacity = .31f),
            deepSeekAppearance = OrbisAppearance(userBubbleOpacity = .41f, assistantBubbleOpacity = .69f, composerOpacity = .52f))
        assertEquals(latest.copy(userNickname = "New"), latest.withValidatedUserNickname("  New  "))
        assertEquals("Old", latest.userNickname)
    }

    @Test fun allowsEmptyOrEightyCharactersAndRejectsOversizeAndControlsWithoutTruncating() {
        val original = DisplaySetting(userNickname = "Original")
        assertEquals("", original.withValidatedUserNickname("  ").userNickname)
        assertEquals("名".repeat(80), original.withValidatedUserNickname("名".repeat(80)).userNickname)
        for (invalid in listOf("名".repeat(81), "a\nb", "a\tb", "a\u0000b")) {
            try {
                original.withValidatedUserNickname(invalid)
                fail("Invalid nickname must be rejected as a whole")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals("Original", original.userNickname)
    }
}
