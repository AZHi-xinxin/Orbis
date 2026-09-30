package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisPersonalNicknameTest {
    @Test fun nicknameAllowsEmptyAndEightyCharactersButRejectsLongInputAsAWhole() {
        assertTrue(orbisNicknameWithinLimit(""))
        assertTrue(orbisNicknameWithinLimit("名".repeat(80)))
        assertFalse(orbisNicknameWithinLimit("名".repeat(81)))
        val pasted = "synthetic transcript ".repeat(20_000)
        assertFalse(orbisNicknameWithinLimit(pasted))
        assertEquals(420_000, pasted.length)
    }
}
