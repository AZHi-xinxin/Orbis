package me.rerere.rikkahub.ui.pages.orbis.market

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.market.ms
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real composition/measurement and production navigation widgets; synthetic, offline rows.
 * In particular, do not wait for a loading effect before checking the first destination frame. */
@RunWith(AndroidJUnit4::class)
class MarketListNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val request = mutableStateOf(MarketListRequest("messages", "", false, 0))
    private lateinit var current: MarketListState
    private val invalidFrames = mutableListOf<String>()
    private val notifications = (1..6).map { n -> buildJsonObject {
        put("kind", "favorite"); put("object", "notice-$n"); put("title", "合成通知$n")
        put("count", n); put("unread", 1)
    } }

    private fun mountInbox() {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        compose.setContent {
            val route = request.value
            val state = rememberMarketListState(route)
            SideEffect {
                current = state
                if (route.page != "messages" && state.rows.any { it.ms("kind") == "favorite" })
                    invalidFrames += route.page
            }
            MarketLetterTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    LetterHeading(route.page, { request.value = route.copy(page = "discover") },
                        { request.value = route.copy(page = "messages") })
                    LazyColumn(Modifier.weight(1f).testTag("market-list"), state=state.scroll) {
                        when (route.page) {
                            "messages" -> items(state.rows) { Text(it.ms("title")) }
                            "discover", "favorites", "mine" -> items(state.rows, key = { it.ms("id") }) {
                                LetterFeedCard(it, true, {})
                            }
                            "wishes" -> items(state.rows.chunked(2), key = { it.first().ms("id") }) {
                                Text(it.first().ms("title"))
                            }
                            else -> item { Text("合成账号页") }
                        }
                    }
                    LetterNavigation(route.page, true) { request.value = route.copy(page = it) }
                }
            }
        }
        compose.runOnIdle {
            current.rows = notifications
            current.cases = listOf(buildJsonObject { put("id", "case-in-inbox") })
            current.next = 100
            current.loading = false
        }
        compose.onNodeWithText("合成通知1").assertIsDisplayed()
    }

    private fun leaveInbox(label: String, destination: String, back: Boolean = false) {
        mountInbox()
        if (back) compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        else compose.onNodeWithText(label).performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(destination, current.request.page)
            assertTrue("No frame may render inbox rows as entries", invalidFrames.isEmpty())
            assertTrue(current.rows.isEmpty()); assertTrue(current.cases.isEmpty()); assertNull(current.next)
            assertEquals(current.request.loadsRows, current.loading)
        }
        compose.onNodeWithText("合成通知1").assertDoesNotExist()
    }

    @Test fun inboxToDiscover() = leaveInbox("发现", "discover")
    @Test fun inboxToWishes() = leaveInbox("心愿", "wishes")
    @Test fun inboxToFavorites() = leaveInbox("收藏", "favorites")
    @Test fun inboxToMine() = leaveInbox("创作", "mine")
    @Test fun inboxToAccount() = leaveInbox("我的", "account")
    @Test fun inboxTopBack() = leaveInbox("", "discover", back = true)

    @Test fun lateInboxResponseCannotReplaceAnotherRouteAndInboxRetainsItsOwnState() {
        mountInbox()
        lateinit var oldInbox: MarketListState
        compose.runOnIdle { oldInbox = current }
        compose.onNodeWithText("发现").performClick()
        compose.runOnIdle {
            // A blocking HTTP completion or pagination callback still holding the old state.
            oldInbox.rows = notifications
            oldInbox.next = 200
            oldInbox.loading = false
        }
        compose.runOnIdle { assertTrue(current.rows.isEmpty()); assertNull(current.next) }
        compose.onNodeWithText("信箱").performClick()
        compose.runOnIdle {
            assertSame(oldInbox, current)
            assertEquals(notifications,current.rows); assertEquals(200,current.next)
            assertFalse(current.loading)
        }
    }

    @Test fun detailReturnKeepsLoadedPagesAndExactPixelOffsetUntilRefreshOrExit() {
        mountInbox()
        compose.onNodeWithText("发现").performClick()
        lateinit var feed: MarketListState
        compose.runOnIdle {
            feed=current
            current.rows=(1..80).map { n -> buildJsonObject { put("id","post-$n"); put("title","合成作品$n"); put("summary","分页内容"); put("category","extra") } }
            current.next=80; current.loaded=true; current.loading=false
        }
        compose.onNodeWithTag("market-list").performScrollToIndex(55)
        val index=feed.scroll.firstVisibleItemIndex
        val offset=feed.scroll.firstVisibleItemScrollOffset
        compose.runOnIdle { request.value=request.value.copy(page="detail") }
        compose.runOnIdle { assertNotSame(feed,current); assertTrue(current.rows.isEmpty()) }
        compose.runOnIdle { request.value=request.value.copy(page="discover") }
        compose.runOnIdle {
            assertSame(feed,current); assertEquals(80,current.rows.size); assertEquals(80,current.next)
            assertTrue(current.loaded); assertEquals(index,current.scroll.firstVisibleItemIndex)
            assertEquals(offset,current.scroll.firstVisibleItemScrollOffset)
            request.value=request.value.copy(revision=1)
        }
        compose.runOnIdle {
            assertNotSame(feed,current); assertFalse(current.loaded); assertTrue(current.rows.isEmpty())
            assertEquals(0,current.scroll.firstVisibleItemIndex); assertEquals(0,current.scroll.firstVisibleItemScrollOffset)
        }
    }

    @Test fun filterRefreshAndWishModeHaveIndependentDataAndPagination() {
        mountInbox()
        compose.onNodeWithText("发现").performClick()
        val targets = listOf(
            MarketListRequest("discover", "grid", false, 0),
            MarketListRequest("discover", "grid", false, 1),
            MarketListRequest("wishes", "grid", false, 1),
            MarketListRequest("wishes", "grid", true, 1),
        )
        for (target in targets) {
            lateinit var old: MarketListState
            compose.runOnIdle {
                old = current
                old.rows = listOf(buildJsonObject { put("id", "synthetic-entry"); put("title", "之前的条目") })
                old.next = 50; old.loading = false
            }
            compose.runOnIdle { request.value = target }
            compose.runOnIdle {
                assertNotSame(old, current)
                assertTrue(current.rows.isEmpty()); assertNull(current.next); assertTrue(current.loading)
                old.rows = notifications
                assertTrue(current.rows.isEmpty())
            }
        }
        compose.runOnIdle { assertEquals("/wishes?offset=50&mode=mine", current.request.path(50)) }
    }
}
