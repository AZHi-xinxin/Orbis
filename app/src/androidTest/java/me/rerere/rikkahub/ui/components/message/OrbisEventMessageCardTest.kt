package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Synthetic presentation only, no real chat, event inbox or sender. */
@RunWith(AndroidJUnit4::class)
class OrbisEventMessageCardTest {
    @get:Rule val compose = createShellComposeRule()
    private val now = Instant.parse("2026-09-22T18:00:00Z")
    private val zone = ZoneId.of("UTC")
    private val receipt = Instant.parse("2026-09-22T16:03:00Z").toEpochMilli()

    @Test fun collapsedIsOnlyLightHeaderAndOpeningMarksReadWithoutChangingOriginal() {
        val original = "  原文\n**不是格式指令** <span>不执行 HTML</span>  "
        val initial = OrbisEventMetadata("receipt-test", "lc_sentinel", "event-test", receipt)
        val updates = mutableListOf<OrbisEventMetadata>()
        compose.setContent { MaterialTheme {
            var event by remember { mutableStateOf(initial) }
            OrbisEventMessageCard(event, original, onUpdate = { event = it; updates += it },
                now = now, zone = zone,
                onOpacityChange = { error("No appearance write while toggling") })
        } }
        compose.onNodeWithText("16:03").assertExists()
        compose.onNodeWithText("系统消息", substring = true).assertDoesNotExist()
        compose.onNodeWithText("▸", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-details").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-read").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-appearance").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-opacity-slider").assertDoesNotExist()
        compose.onNodeWithText("未读", substring = true).assertDoesNotExist()
        compose.onNodeWithText("不是你的发言", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-collapse").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(0, updates.size) }
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertTextEquals(original)
        compose.runOnIdle {
            assertEquals(initial.copy(collapsed = false, read = true), updates.single())
        }
        compose.onNodeWithTag("orbis-event-appearance").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("orbis-event-opacity-slider").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.runOnIdle { assertEquals(initial.copy(collapsed = true, read = true), updates.last()) }
    }

    @Test fun savedExpandedReadStateIsRenderedWithoutAnotherWrite() {
        val initial = OrbisEventMetadata("receipt-test", "self_reminder", "event-test", receipt,
            read = true, collapsed = false)
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(initial, "已保存的合成事件", onUpdate = { error("Rendering must not write") },
                now = now, zone = zone,
                onOpacityChange = { error("Rendering must not write appearance") })
        } }
        compose.onNodeWithTag("orbis-event-original").assertTextEquals("已保存的合成事件")
        compose.onNodeWithText("16:03").assertExists()
        compose.onNodeWithText("接收时间（未提供触发时间）", substring = true).assertExists()
        compose.onNodeWithText("自主提醒", substring = true).assertExists()
        compose.onNodeWithText("标为未读").assertDoesNotExist()
    }

    @Test fun opacityEditorCancelDoesNotPersistDraft() {
        var saves = 0
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(expanded(), "synthetic", onUpdate = {}, onOpacityChange = { saves++ })
        } }
        compose.onNodeWithTag("orbis-event-appearance").performClick()
        compose.onNodeWithTag("orbis-event-opacity-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("orbis-event-opacity-value").assertTextEquals("背景不透明度 0%")
        compose.onNodeWithTag("orbis-event-opacity-cancel").performClick()
        compose.onNodeWithTag("orbis-event-opacity-slider").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("orbis-event-appearance").performClick()
        compose.onNodeWithTag("orbis-event-opacity-value").assertTextEquals("背景不透明度 22%")
    }

    @Test fun savingGlobalOpacityIsUsedByAnotherEvent() {
        var appearance by mutableStateOf(OrbisAppearance())
        var event by mutableStateOf(expanded())
        var saves = 0
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(event, "synthetic", onUpdate = {}, appearance = appearance,
                onOpacityChange = { saves++; appearance = appearance.copy(eventOpacity = it) })
        } }
        compose.onNodeWithTag("orbis-event-appearance").performClick()
        compose.onNodeWithTag("orbis-event-opacity-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("orbis-event-opacity-save").performClick()
        compose.onNodeWithTag("orbis-event-opacity-slider").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(0f, appearance.eventOpacity, 0f)
            event = expanded().copy(recordId = "another-synthetic-receipt", eventId = "another-event")
        }
        compose.onNodeWithTag("orbis-event-appearance").performClick()
        compose.onNodeWithTag("orbis-event-opacity-value").assertTextEquals("背景不透明度 0%")
    }

    @Test fun failedSaveStaysOpenAndCanRetryWithoutExposingException() {
        var attempts = 0
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(expanded(), "synthetic", onUpdate = {}, onOpacityChange = {
                attempts++
                if (attempts == 1) throw IllegalStateException("synthetic-private-diagnostic")
            })
        } }
        compose.onNodeWithTag("orbis-event-appearance").performClick()
        compose.onNodeWithTag("orbis-event-opacity-save").performClick()
        compose.onNodeWithText("未能保存，请重试。").assertExists()
        compose.onNodeWithText("synthetic-private-diagnostic", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-opacity-save").performClick()
        compose.onNodeWithTag("orbis-event-opacity-slider").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun zeroBackgroundKeepsCustomTextOpaqueAndControlClickable() {
        var event by mutableStateOf(expanded())
        compose.setContent { MaterialTheme {
            Box(Modifier.background(Color.Black)) {
                OrbisEventMessageCard(event, "opaque text", onUpdate = { event = it },
                    onOpacityChange = {}, appearance = OrbisAppearance(eventOpacity = 0f, chatTextColor = -1))
            }
        } }
        val image = compose.onNodeWithTag("orbis-event-details").captureToImage().toPixelMap()
        // The bottom padding is outside all text/icon shapes, but inside the detail surface.
        val background = image[image.width / 2, image.height - 3]
        assertTrue(background.red < .025f && background.green < .025f && background.blue < .025f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("orbis-event-original")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.White, layouts.single().layoutInput.style.color)
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertTextEquals("opaque text")
    }

    @Test fun enlargedFontKeepsCompactHeaderAndExpandedTextReachable() {
        var event by mutableStateOf(expanded().copy(collapsed = true))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { MaterialTheme {
                Box(Modifier.requiredWidth(240.dp)) {
                    OrbisEventMessageCard(event, "合成事件原文", onUpdate = { event = it }, onOpacityChange = {},
                        now = now, zone = zone)
                }
            } }
        }
        compose.onNodeWithTag("orbis-event-collapse").assertHeightIsAtLeast(48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("orbis-event-time", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val characterBounds = layout.layoutInput.text.indices.map(layout::getBoundingBox)
        // A paragraph may keep the full available width while Text wraps its actual glyphs.
        // didOverflowWidth then compares 224px to 57px despite every character fitting.
        // Verify the rendered line and every character, not that paragraph-width heuristic.
        val charactersFit = characterBounds.all {
            it.left >= 0f && it.right <= layout.size.width.toFloat() &&
                it.top >= 0f && it.bottom <= layout.size.height.toFloat()
        }
        assertTrue(
            "Timestamp layout: text=${layout.layoutInput.text.text}, lines=${layout.lineCount}, " +
                "size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                "font=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, " +
                "density=${layout.layoutInput.density.density}, scale=${layout.layoutInput.density.fontScale}, " +
                "lineRight=${layout.getLineRight(0)}, lineEnd=${layout.getLineEnd(0, visibleEnd = true)}, " +
                "ellipsized=${layout.isLineEllipsized(0)}, characterBounds=$characterBounds",
            layout.lineCount == 1 && !layout.didOverflowHeight &&
                layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width.toFloat() &&
                layout.getLineEnd(0, visibleEnd = true) == layout.layoutInput.text.length &&
                !layout.isLineEllipsized(0) && charactersFit,
        )
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertIsDisplayed()
        compose.onNodeWithTag("orbis-event-appearance").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("orbis-event-opacity-save").assertIsDisplayed()
    }

    @Test fun occurrenceTimestampIsVisibleAndAccessibleWithHonestSourceInExpandedDetails() {
        var event by mutableStateOf(expanded().copy(collapsed = true,
            occurredAt = Instant.parse("2026-09-22T15:42:38Z").toEpochMilli()))
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(event, "原文不改", onUpdate = { event = it }, onOpacityChange = {},
                now = now, zone = zone)
        } }
        compose.onNodeWithTag("orbis-event-time", useUnmergedTree = true).assertTextEquals("15:42")
        compose.onNodeWithText("16:03").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-collapse").assertContentDescriptionEquals(
            "来源提供的触发时间 · 2026-09-22 15:42:38，点按展开消息")
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertTextEquals("原文不改")
        compose.onNodeWithText("来源提供的触发时间 · 2026-09-22 15:42:38", substring = true).assertExists()
        compose.onNodeWithTag("orbis-event-collapse").assertContentDescriptionEquals(
            "来源提供的触发时间 · 2026-09-22 15:42:38，点按折叠消息")
    }

    @Test fun failedPresentationWriteDoesNotPretendToReadAndCanRetry() {
        val original = expanded().copy(collapsed = true, read = false)
        var event by mutableStateOf(original)
        var attempts = 0
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(event, "not read until saved", onOpacityChange = {}, onUpdate = {
                attempts++
                if (attempts == 1) throw IllegalStateException("synthetic-private-error")
                event = it
            }, now = now, zone = zone)
        } }
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithText("未更新显示状态；正在回复时请等回复结束后再试。若有恢复提示，请先处理恢复记录。").assertExists()
        compose.onNodeWithText("synthetic-private-error", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, event) }
        compose.onNodeWithTag("orbis-event-collapse").assertIsEnabled().performClick()
        compose.onNodeWithTag("orbis-event-original").assertTextEquals("not read until saved")
        compose.onNodeWithText("未更新显示状态；正在回复时请等回复结束后再试。若有恢复提示，请先处理恢复记录。").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(original.copy(collapsed = false, read = true), event)
        }
    }

    @Test fun presentationUpdateAwaitsPersistenceAndPreventsRepeatedClicks() {
        val gate = CompletableDeferred<Unit>()
        val original = expanded().copy(collapsed = true, read = false)
        var event by mutableStateOf(original)
        var calls = 0
        compose.setContent { MaterialTheme {
            OrbisEventMessageCard(event, "saved text", onOpacityChange = {}, onUpdate = {
                calls++
                gate.await()
                event = it
            }, now = now, zone = zone)
        } }
        compose.onNodeWithTag("orbis-event-collapse").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, calls)
            assertEquals(original, event)
            gate.complete(Unit)
        }
        compose.onNodeWithTag("orbis-event-original").assertTextEquals("saved text")
        compose.onNodeWithTag("orbis-event-collapse").assertIsEnabled()
    }

    private fun expanded() = OrbisEventMetadata("synthetic-receipt", "lc_sentinel", "synthetic-event", receipt,
        read = true, collapsed = false)
}
