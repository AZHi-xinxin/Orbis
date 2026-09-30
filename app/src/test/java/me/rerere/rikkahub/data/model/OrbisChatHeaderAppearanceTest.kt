package me.rerere.rikkahub.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisChatHeaderAppearanceTest {
    @Test fun `light default backdrop retains dark header and system icons`() {
        assertFalse(orbisChatUsesLightHeader(OrbisAppearance(), false, false))
        assertTrue(orbisChatLightStatusBarOverride(true, true, false, false)!!)
    }

    @Test fun `stars remain light foreground even with global light theme`() {
        assertTrue(orbisChatUsesLightHeader(OrbisAppearance(backgroundEnabled = true,
            backgroundStyle = OrbisBackgroundStyle.STARS), false, false))
        assertFalse(orbisChatLightStatusBarOverride(true, true, false, true)!!)
    }

    @Test fun `custom and inherited photos and dark theme use protected light foreground`() {
        assertTrue(orbisChatUsesLightHeader(OrbisAppearance(backgroundEnabled = true,
            backgroundImage = "synthetic.jpg"), false, false))
        assertTrue(orbisChatUsesLightHeader(OrbisAppearance(), false, true))
        assertTrue(orbisChatUsesLightHeader(OrbisAppearance(), true, false))
    }

    @Test fun `disabled stars preset and overridden assistant photo do not change light preset contrast`() {
        assertFalse(orbisChatUsesLightHeader(OrbisAppearance(backgroundEnabled = false,
            backgroundStyle = OrbisBackgroundStyle.STARS), false, false))
        assertFalse(orbisChatUsesLightHeader(OrbisAppearance(backgroundEnabled = true,
            backgroundStyle = OrbisBackgroundStyle.PAPER), false, true))
    }

    @Test fun `home other top route and release always return ownership to theme`() {
        assertNull(orbisChatLightStatusBarOverride(true, true, true, true))
        assertNull(orbisChatLightStatusBarOverride(true, false, false, true))
        assertNull(orbisChatLightStatusBarOverride(false, true, false, true))
    }

    @Test fun `open light drawer returns status icons to theme and closing restores star chat`() {
        assertNull(orbisChatLightStatusBarOverride(true, true, false, true, drawerVisible = true))
        assertFalse(orbisChatLightStatusBarOverride(true, true, false, true, drawerVisible = false)!!)
        // The route resolver uses the actual theme on null, so dark-theme drawers remain light-icon.
        assertFalse(orbisChatLightStatusBarOverride(true, true, false, true, drawerVisible = true) ?: false)
    }

    @Test fun `drawer remains owner during opening and closing animations`() {
        assertTrue(orbisChatDrawerVisible(false, false, true, -360f, 360))
        assertTrue(orbisChatDrawerVisible(false, true, false, -359f, 360))
        assertFalse(orbisChatDrawerVisible(false, false, false, -360f, 360))
    }

    @Test fun `dragged drawer counts as visible before the open threshold is reached`() {
        assertTrue(orbisChatDrawerVisible(false, false, false, -350f, 360))
        assertTrue(orbisChatDrawerVisible(false, false, false, 0f, 360))
        assertFalse(orbisChatDrawerVisible(false, false, false, -359.9f, 360))
    }

    @Test fun `unmeasured closed drawer does not steal ownership but permanent drawer does`() {
        assertFalse(orbisChatDrawerVisible(false, false, false, Float.NaN, 0))
        assertFalse(orbisChatDrawerVisible(false, false, false, 0f, 0))
        assertTrue(orbisChatDrawerVisible(true, false, false, Float.NaN, 0))
    }

    @Test fun `drawer state cannot keep ownership after another route or home becomes visible`() {
        assertNull(orbisChatLightStatusBarOverride(true, false, false, true, drawerVisible = true))
        assertNull(orbisChatLightStatusBarOverride(true, true, true, true, drawerVisible = true))
        assertNull(orbisChatLightStatusBarOverride(false, true, false, true, drawerVisible = true))
    }

    @Test fun `short light sheet keeps white icons over exposed dark chat`() {
        assertFalse(orbisSheetLightStatusBars(false, true, 800f, 80))
        assertFalse(orbisSheetLightStatusBars(false, true, null, 80))
        assertFalse(orbisSheetLightStatusBars(false, true, Float.NaN, 80))
    }

    @Test fun `sheet reaching status area uses its own palette and reverses when dragged down`() {
        assertTrue(orbisSheetLightStatusBars(false, true, 0f, 80))
        assertTrue(orbisSheetLightStatusBars(false, true, 80f, 80))
        assertFalse(orbisSheetLightStatusBars(false, true, 200f, 80))
    }

    @Test fun `dark full sheet and light underlying screen do not force one icon color globally`() {
        assertFalse(orbisSheetLightStatusBars(true, false, 0f, 80))
        assertTrue(orbisSheetLightStatusBars(true, false, 800f, 80))
        assertTrue(orbisSheetLightStatusBars(true, true, 800f, 80))
    }

    @Test fun `expanded short sheet uses actual placement offset rather than the external modifier origin`() {
        // Material 1.5's Expanded anchor is viewport minus measured content, clamped at zero.
        val expandedShortSheetOffset = maxOf(0f, 2608f - 1700f)
        assertFalse(orbisSheetLightStatusBars(false, true, expandedShortSheetOffset, 100))
        val expandedFullSheetOffset = maxOf(0f, 2608f - 2608f)
        assertTrue(orbisSheetLightStatusBars(false, true, expandedFullSheetOffset, 100))
    }

    @Test fun `hidden and uninitialized actual offsets preserve background until measured full-height coverage`() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 2608f).forEach { offset ->
            assertFalse(orbisSheetLightStatusBars(false, true, offset, 100))
        }
        // The same short menu can grow to full height on a smaller screen or large font.
        assertFalse(orbisSheetLightStatusBars(false, true, 908f, 100))
        assertTrue(orbisSheetLightStatusBars(false, true, 0f, 100))
    }
}
