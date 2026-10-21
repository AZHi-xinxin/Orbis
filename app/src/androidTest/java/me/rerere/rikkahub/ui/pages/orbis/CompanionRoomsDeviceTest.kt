package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.studyTool
import me.rerere.rikkahub.data.orbis.activities.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Synthetic isolated application only; never opens a human assistant or chat. */
@RunWith(AndroidJUnit4::class)
class CompanionRoomsDeviceTest {
    private val compose=createAndroidComposeRule<ComponentActivity>()
    private val isolated=object : ExternalResource() {
        override fun before() {
            val inst=InstrumentationRegistry.getInstrumentation()
            check(inst is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java,inst.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(isolated).around(compose)
    private class Storage : ActivityStorage {
        @Volatile var text: String?=null
        override fun read()=text
        override fun write(text:String) {this.text=text}
    }
    private fun picture(tag:String,name:String) {
        val bitmap=compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val root=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(root,"companion-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun petMovesAndRoomHotspotsChangeExactlyOneRealCareEvent() {
        val repo=PetRepository(Storage());repo.adopt("小墨","cat-black-v1")
        compose.mainClock.autoAdvance=false
        compose.setContent { MaterialTheme {
            val state by repo.state.collectAsState()
            CompanionWindow({}) {
                PetInteractiveHome(state,false,null) { action,decoration -> repo.act(action,UUID.randomUUID().toString(),decoration) }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {assertEquals(Lifecycle.State.RESUMED,compose.activity.lifecycle.currentState)}
        compose.onNodeWithContentDescription("摸摸小墨").assertIsDisplayed()
        val page=compose.onNodeWithTag("pet-destination").fetchSemanticsNode().boundsInRoot
        val scene=compose.onNodeWithTag("pet-room-scene").fetchSemanticsNode().boundsInRoot
        val nav=compose.onNodeWithTag("companion-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals("Room must reach both edges",page.width,scene.width,1f)
        // Accessibility text makes chrome taller on compact screens. Keep the
        // room prominent without forcing font scaling off to satisfy a ratio.
        val largeType=compose.activity.resources.configuration.fontScale>1.3f
        assertTrue("Room must remain prominent",scene.height>page.height*(if(largeType) .45f else .55f))
        assertEquals("Navigation pinned to bottom",page.bottom,nav.bottom,1f)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch)).assertCountEquals(0)
        val before=compose.onNodeWithContentDescription("摸摸小墨").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        compose.mainClock.advanceTimeBy(1050)
        compose.waitForIdle()
        val after=compose.onNodeWithContentDescription("摸摸小墨").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        val root=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        for((name,frame) in listOf("before" to before,"after" to after)) File(root,"companion-idle-$name.png").outputStream().use {frame.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertFalse("Idle image must actually animate",before.sameAs(after))
        compose.mainClock.autoAdvance=true
        picture("companion-window","pet-home")
        compose.onNodeWithText("玩具").assertIsDisplayed().performClick()
        compose.waitUntil {repo.snapshot().events.size==1}
        assertTrue(repo.snapshot().messy)
        compose.onNodeWithText("收拾").assertIsDisplayed().performClick()
        compose.waitUntil {repo.snapshot().events.size==2}
        assertFalse(repo.snapshot().messy)
        compose.onNodeWithText("添粮").assertIsDisplayed().performClick()
        compose.waitUntil {repo.snapshot().events.size==3}
        compose.onNodeWithContentDescription("摸摸小墨").assertIsDisplayed().performClick()
        compose.waitUntil {repo.snapshot().events.size==4}
        picture("companion-window","pet")
        compose.activityRule.scenario.recreate()
        assertEquals("No old event may replay after activity recreation",4,repo.snapshot().events.size)
    }

    @Test fun pressingTransparentPetBoundsDoesNotTintTheRoomAndStillCountsOneCareEvent() {
        val repo=PetRepository(Storage());repo.adopt("小墨","cat-black-v1")
        compose.setContent {MaterialTheme {
            val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,null) {action,decoration -> repo.act(action,UUID.randomUUID().toString(),decoration)}}
        }}
        compose.onNodeWithContentDescription("摸摸小墨").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val pet=compose.onNodeWithContentDescription("摸摸小墨")
        val before=pet.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        pet.performTouchInput {down(center)}
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val pressed=pet.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false)
        // The two top corners are transparent artwork, so the room there must not
        // acquire a rectangular press tint. Ignore the animated animal in the middle.
        assertEquals(before.width,pressed.width);assertEquals(before.height,pressed.height)
        for(x in listOf(before.width/20,before.width*19/20)) for(dx in -2..2) for(dy in -2..2) {
            assertEquals("Transparent pet bounds must preserve the room while pressed",
                before.getPixel(x+dx,before.height/20+dy),pressed.getPixel(x+dx,before.height/20+dy))
        }
        picture("companion-window","pet-held-no-rectangle")
        assertEquals(0,repo.snapshot().events.size)
        pet.performTouchInput {up()}
        compose.mainClock.autoAdvance=true
        compose.waitUntil {repo.snapshot().events.size==1}
    }

    // Run the entire class with Android system font_scale=1.0 and 1.8. A density
    // override outside Dialog does not exercise the dialog window's real font.
    @Test fun largeFontRoomKeepsActionsAndBottomNavigationVisible() {
        val repo=PetRepository(Storage());repo.adopt("小墨","cat-black-v1")
        compose.setContent {MaterialTheme {
            val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,null,"小序") {action,decoration -> repo.act(action,UUID.randomUUID().toString(),decoration)}}
        }}
        compose.onNodeWithText("换水").assertIsDisplayed().performClick()
        compose.waitUntil {repo.snapshot().events.size==1}
        compose.onNodeWithText("照料日记").assertIsDisplayed()
        compose.onNodeWithText("布置").assertIsDisplayed()
        val room=compose.onNodeWithTag("pet-room-scene").fetchSemanticsNode().boundsInRoot
        val nav=compose.onNodeWithTag("companion-navigation").fetchSemanticsNode().boundsInRoot
        val water=compose.onNodeWithText("换水").fetchSemanticsNode().boundsInRoot
        assertTrue(water.bottom<=room.bottom)
        assertTrue(room.bottom<nav.top)
        picture("companion-window","pet-large-font")
    }

    @Test fun petActionPosesStayInsideRoomAndClearOfSmallCaptions() {
        val repo=PetRepository(Storage());repo.adopt("小墨","cat-black-v1")
        compose.mainClock.autoAdvance=false
        compose.setContent {MaterialTheme {
            val state by repo.state.collectAsState()
            CompanionWindow({}) {PetInteractiveHome(state,false,null) {action,decoration -> repo.act(action,UUID.randomUUID().toString(),decoration)}}
        }}
        compose.mainClock.advanceTimeByFrame()
        for((index,label) in listOf("小床","玩具","添粮","换水").withIndex()) {
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            assertEquals("One care event per tap",index+1,repo.snapshot().events.size)
            val actor=compose.onNodeWithContentDescription("摸摸小墨").fetchSemanticsNode().boundsInRoot
            val room=compose.onNodeWithTag("pet-room-scene").fetchSemanticsNode().boundsInRoot
            picture("companion-window","pose-$index")
            assertTrue("$label pet remains inside the room",actor.left>=room.left && actor.right<=room.right && actor.top>=room.top && actor.bottom<=room.bottom)
            for(id in listOf("sleep","play","feed","water")) {
                val caption=compose.onNodeWithTag("pet-caption-$id",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                assertFalse("$label pose must not cover $id caption",actor.overlaps(caption))
            }
        }
    }

    @Test fun studyNavigationStaysPinnedAndSettingsAreOffTheDesk() {
        val owner="device-study-${UUID.randomUUID()}"
        compose.setContent {MaterialTheme {
            OrbisStudyPage(owner,"小序",true,{}, {})
        }}
        compose.waitUntil(10000) {compose.onAllNodesWithText("和小序一起").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch)).assertCountEquals(0)
        val initial=compose.onNodeWithTag("companion-navigation").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("study-content").performTouchInput {swipeUp()}
        val scrolled=compose.onNodeWithTag("companion-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(initial,scrolled)
        compose.onNodeWithText("学习足迹").assertIsDisplayed()
        compose.onNodeWithContentDescription("学习机设置").performClick()
        compose.onNodeWithText("启用此能力").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Switch)).assertCountEquals(0)
        compose.onNodeWithText("和小序一起").performScrollTo()
        picture("companion-window","study-large-font")
    }

    @Test fun darkRoomUsesTheSameFullScreenLayout() {
        val repo=PetRepository(Storage());repo.adopt("小墨","cat-black-v1")
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            CompanionWindow({}) {PetInteractiveHome(repo.snapshot(),false,null) {_,_->}}
        }}
        compose.onNodeWithText("添粮").assertIsDisplayed()
        picture("companion-window","pet-dark")
    }

    @Test fun focusPausesOnLeavingPageAndAiReadsTheSavedTime() = runBlocking {
        val storage=Storage();val repo=StudyRepository(storage);val visible=mutableStateOf(true)
        compose.setContent {MaterialTheme {
            val state by repo.state.collectAsState()
            Column(Modifier.fillMaxSize().testTag("focus-preview").verticalScroll(rememberScrollState()).padding(16.dp)) {
                if(visible.value) StudyFocusPanel(repo,state) else Text("离开学习页")
            }
        } }
        compose.onNodeWithText("开始专注").performScrollTo().performClick()
        compose.waitUntil {repo.state.value.focusSessions.isNotEmpty()}
        compose.onNodeWithText("暂停").assertExists()
        Thread.sleep(1200)
        compose.runOnIdle {visible.value=false}
        compose.waitUntil {repo.state.value.focusSessions.single().activeMillis>0}
        val saved=repo.state.value.focusSessions.single().activeMillis
        Thread.sleep(600)
        assertEquals(saved,repo.state.value.focusSessions.single().activeMillis)
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithText("继续").assertExists()
        Thread.sleep(500)
        assertEquals(saved,repo.state.value.focusSessions.single().activeMillis)
        compose.onNodeWithText("结束并保存").performScrollTo().performClick()
        compose.waitUntil {repo.state.value.focusSessions.single().finishedAt!=null}
        assertEquals(saved,StudyRepository(storage).snapshot().focusSessions.single().activeMillis)
        val result=studyTool({true}){repo}.execute(Json.parseToJsonElement("""{"action":"focus"}"""))
        val json=Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(saved,json["result"]!!.jsonObject["statistics"]!!.jsonObject["focusMillis"]!!.jsonPrimitive.long)
        picture("focus-preview","focus")
    }

    @Test fun backgroundPausesFocusAndResumeDoesNotCountAwayTime() {
        val repo=StudyRepository(Storage())
        compose.setContent {MaterialTheme {val state by repo.state.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) {StudyFocusPanel(repo,state)}
        } }
        compose.onNodeWithText("开始专注").performClick()
        compose.waitUntil {repo.state.value.focusSessions.isNotEmpty()}
        compose.onNodeWithText("暂停").assertExists()
        Thread.sleep(1000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil {repo.state.value.focusSessions.single().activeMillis>0}
        val saved=repo.state.value.focusSessions.single().activeMillis
        Thread.sleep(600)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("继续").assertExists()
        assertEquals(saved,repo.state.value.focusSessions.single().activeMillis)
    }

    @Test fun nativeDeskKeepsSingleQuestionDraftAndAiGradingAfterNavigation() {
        val owner="device-study-${UUID.randomUUID()}"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=OrbisActivities.study(context,owner)
        val session=repo.commitQuestions("语文",0,"ui-seed",listOf(
            StudyInputQuestion("written","说说今天学到了什么",answers=listOf("具体学习收获")),
            StudyInputQuestion("fill","一加一",answers=listOf("2"))),"每天一点")
        compose.setContent {MaterialTheme {OrbisStudyPage(owner,"小序",true,{}, {})} }
        compose.waitUntil(10000) {compose.onAllNodesWithText("和小序一起").fetchSemanticsNodes().isNotEmpty()}
        picture("companion-window","desk-home")
        compose.onNodeWithText("继续练习").performScrollTo().performClick()
        compose.onNodeWithText("主观题 · 1 / 2").assertExists()
        compose.onNodeWithText("参考答案：具体学习收获").assertDoesNotExist()
        compose.onNodeWithText("你的答案（自动保存草稿）").performScrollTo().performTextInput("我学会了分页读取")
        compose.waitUntil {repo.snapshot().sessions.single().attempts.first().answer==listOf("我学会了分页读取")}
        // Paging is fixed outside the answer scroller, including while the IME
        // is open. Do not scroll it into view or hide the keyboard in the test.
        compose.onNodeWithText("下一题").assertIsDisplayed().performClick()
        // Text input/IME window changes can finish after Compose's idle check on
        // compact devices. Await the new page, without repeating the tap or
        // weakening the draft/grade assertions below.
        compose.waitUntil(5000) {compose.onAllNodesWithText("填空题 · 2 / 2").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("填空题 · 2 / 2").assertExists()
        compose.onNodeWithText("上一题").assertIsDisplayed().performClick()
        compose.onNodeWithText("我学会了分页读取").assertExists()
        compose.onNodeWithText("提交答卷").performScrollTo().performClick()
        compose.waitUntil {repo.snapshot().sessions.single().submittedAt!=null}
        repo.judge(session.id,session.questionIds.first(),90,"具体，有进步")
        compose.onNodeWithText("90 分 · 具体，有进步").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("我学会了分页读取"),repo.snapshot().sessions.single().attempts.first().answer)
        compose.onNodeWithText("返回书桌，保留作答").performScrollTo().performClick()
        compose.onNodeWithText("连续学习 1 天").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("和小序一起").performScrollTo()
        // A screenshot of the actual dialog, not a browser mock or production content.
        val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"companion-desk.png").outputStream().use {screen.compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("学习足迹").performClick()
        compose.onNodeWithText("累计 1 天 · 连续 1 天").assertExists()
        picture("companion-window","study-history")
        compose.onNodeWithText("错题本").performClick()
        compose.onNodeWithText("独立练习这些错题").performScrollTo().assertIsDisplayed()
    }
}
