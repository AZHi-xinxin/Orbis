package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.YearMonth
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisGardenKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Pure visual components and in-memory callbacks only. Never mounts OrbisLocalGarden,
 * Koin, a store, a WebView, a file picker, a model or a network client. The runner must
 * substitute Application before the Compose activity starts. No screenshots/files
 * are written, and synthetic density/font scaling does not change device settings.
 */
@RunWith(AndroidJUnit4::class)
class OrbisGardenCalendarDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolated = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    private class Fixture(initialMonth: YearMonth = YearMonth.of(2026, 9)) {
        val today: LocalDate = LocalDate.of(2026, 9, 28)
        val month = mutableStateOf(initialMonth)
        val selected = mutableStateOf<LocalDate?>(initialMonth.atDay(1))
        val kind = mutableStateOf(OrbisGardenKind.DIARY)
        val enabled = mutableStateOf(true)
        val days = mapOf(LocalDate.of(2026, 9, 23) to 3, today to 1)
        var monthCalls = 0
        var dayCalls = 0
        var todayCalls = 0
        var kindCalls = 0

        fun monthChanged(value: YearMonth) {
            monthCalls++
            month.value = value
            selected.value = value.atDay(1)
        }
        fun dayChanged(value: LocalDate) { dayCalls++; selected.value = value }
        fun todayClicked() {
            todayCalls++
            month.value = YearMonth.from(today)
            selected.value = today
        }
        fun kindChanged(value: OrbisGardenKind) { kindCalls++; kind.value = value }
    }

    private fun show(fixture: Fixture, width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, 560.dp).testTag("synthetic-garden-viewport")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            GardenCalendar(
                                fixture.month.value, fixture.selected.value, fixture.today,
                                fixture.days, fixture.kind.value, fixture.enabled.value,
                                fixture::monthChanged, fixture::dayChanged, fixture::todayClicked,
                            )
                            GardenKindRow(fixture.kind.value, fixture.enabled.value, fixture::kindChanged)
                        }
                    }
                }
            }
        }
    }

    @Test fun monthNavigationCrossesYearAndOnlyChangesTheNavigationState() {
        val fixture = Fixture(YearMonth.of(2026, 12))
        show(fixture)
        compose.onNodeWithTag("garden-calendar-next").performClick()
        compose.onNodeWithTag("garden-calendar-month").assertTextEquals("2027 年 1 月")
        compose.onNodeWithTag("garden-day-2027-01-01").assertIsSelected()
        compose.onNodeWithTag("garden-calendar-prev").performClick()
        compose.onNodeWithTag("garden-calendar-month").assertTextEquals("2026 年 12 月")
        compose.runOnIdle {
            assertEquals(YearMonth.of(2026, 12), fixture.month.value)
            assertEquals(2, fixture.monthCalls)
            assertEquals(0, fixture.dayCalls)
            assertEquals(0, fixture.todayCalls)
            assertEquals(0, fixture.kindCalls)
        }
    }

    @Test fun dateClickOnlySelectsTheDateAndDoesNotOpenAnEditorOrAlterCounts() {
        val fixture = Fixture()
        val countsBefore = fixture.days.toMap()
        show(fixture)
        compose.onNodeWithTag("garden-day-2026-09-23")
            .assertContentDescriptionEquals("9月23日，3 条日记")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("garden-day-2026-09-01").assertIsNotSelected()
        compose.onNodeWithTag("garden-editor-title").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LocalDate.of(2026, 9, 23), fixture.selected.value)
            assertEquals(1, fixture.dayCalls)
            assertEquals(0, fixture.monthCalls + fixture.todayCalls + fixture.kindCalls)
            assertEquals(countsBefore, fixture.days)
        }
    }

    @Test fun todayButtonReturnsToTheCurrentMonthAndSelectsToday() {
        val fixture = Fixture(YearMonth.of(2025, 2))
        show(fixture)
        compose.onNodeWithTag("garden-calendar-today").performClick()
        compose.onNodeWithTag("garden-calendar-month").assertTextEquals("2026 年 9 月")
        compose.onNodeWithTag("garden-day-2026-09-28").assertIsSelected()
            .assertContentDescriptionEquals("9月28日，1 条日记，今天")
        compose.runOnIdle {
            assertEquals(fixture.today, fixture.selected.value)
            assertEquals(1, fixture.todayCalls)
            assertEquals(0, fixture.monthCalls + fixture.dayCalls + fixture.kindCalls)
        }
    }

    @Test fun allFiveCategoriesIncludingSongsExposeTabAndSelectionSemantics() {
        val fixture = Fixture()
        show(fixture)
        OrbisGardenKind.entries.forEach { kind ->
            compose.onNodeWithTag("garden-kind-${kind.name}").performScrollTo()
                .assertTextContains(kind.label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .performClick().assertIsSelected()
            OrbisGardenKind.entries.filter { it != kind }.forEach { other ->
                compose.onNodeWithTag("garden-kind-${other.name}").assertIsNotSelected()
            }
            compose.onNodeWithTag("garden-day-2026-09-23")
                .assertContentDescriptionEquals("9月23日，3 条${kind.label}")
        }
        compose.runOnIdle {
            assertEquals(5, fixture.kindCalls)
            assertEquals(0, fixture.monthCalls + fixture.dayCalls + fixture.todayCalls)
        }
    }

    @Test fun disabledCalendarAndCategoryControlsDoNotInvokeCallbacks() {
        val fixture = Fixture().also { it.enabled.value = false }
        show(fixture)
        listOf("garden-calendar-prev", "garden-calendar-next", "garden-calendar-today",
            "garden-day-2026-09-23", "garden-kind-LETTER").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
                .performTouchInput { click(center) }
        }
        compose.runOnIdle {
            assertEquals(0, fixture.monthCalls + fixture.dayCalls + fixture.todayCalls + fixture.kindCalls)
            assertEquals(YearMonth.of(2026, 9), fixture.month.value)
            assertEquals(LocalDate.of(2026, 9, 1), fixture.selected.value)
            assertEquals(OrbisGardenKind.DIARY, fixture.kind.value)
        }
    }

    @Test fun leapMonthHasAReachableFebruary29AndNoFebruary30() {
        val fixture = Fixture(YearMonth.of(2024, 2))
        show(fixture)
        compose.onNodeWithTag("garden-day-2024-02-29").performScrollTo().assertIsDisplayed()
            .performClick().assertIsSelected()
        compose.onNodeWithTag("garden-day-2024-02-30").assertDoesNotExist()
        compose.onNodeWithTag("garden-calendar-next").performScrollTo().performClick()
        compose.onNodeWithTag("garden-calendar-month").assertTextEquals("2024 年 3 月")
        compose.onNodeWithTag("garden-day-2024-03-31").performScrollTo().assertIsDisplayed()
    }

    @Test fun narrowLargeTextKeepsNavigationDatesAndCategoriesReachableByTouch() {
        val fixture = Fixture()
        show(fixture, width = 280, fontScale = 2f)
        listOf("garden-calendar-next", "garden-calendar-prev", "garden-calendar-today").forEach { tag ->
            assertFitsViewport(tag)
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
        }
        listOf(1, 30).forEach { day ->
            val tag = "garden-day-2026-09-${day.toString().padStart(2, '0')}"
            assertFitsViewport(tag)
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(LocalDate.of(2026, 9, day), fixture.selected.value) }
        }
        OrbisGardenKind.entries.forEach { kind ->
            val tag = "garden-kind-${kind.name}"
            assertFitsViewport(tag)
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(kind, fixture.kind.value) }
        }
        compose.runOnIdle {
            assertEquals(2, fixture.monthCalls)
            assertEquals(1, fixture.todayCalls)
            assertEquals(2, fixture.dayCalls)
            assertEquals(5, fixture.kindCalls)
        }
    }

    private fun assertFitsViewport(tag: String) {
        val interaction = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = interaction.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("synthetic-garden-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("$tag has a nonzero touch target", bounds.width > 0f && bounds.height > 0f)
        assertTrue("$tag is inside the left edge", bounds.left >= viewport.left - 1f)
        assertTrue("$tag is inside the right edge", bounds.right <= viewport.right + 1f)
        assertTrue("$tag can scroll into view", bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
        // Density is fixed at 1: compare actual layout dimensions to clipped semantics,
        // so a partly clipped control cannot accidentally satisfy the viewport assertion.
        val unclipped = interaction.getUnclippedBoundsInRoot()
        assertEquals("$tag is not horizontally clipped", (unclipped.right - unclipped.left).value, bounds.width, 1.5f)
        assertEquals("$tag is not vertically clipped", (unclipped.bottom - unclipped.top).value, bounds.height, 1.5f)
    }
}
