package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.activities.*
import org.junit.Assert.*
import org.junit.Test

private class ActivityToolStorage : ActivityStorage {
    var saved: String? = null
    override fun read() = saved
    override fun write(text: String) { saved = text }
}
class OrbisActivityToolsTest {
    private suspend fun call(tool: Tool, input: String): JsonObject =
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text).jsonObject
    @Test fun disabledToolsNeverOpenStorageAndReturnWithoutThrowing() = runBlocking {
        var opens = 0
        val pet = petTool({ false }) { opens++; error("must_not_open") }
        val study = studyTool({ false }) { opens++; error("must_not_open") }
        for (tool in listOf(pet, study)) {
            val result = call(tool, """{"action":"status"}""")
            assertFalse(result.getValue("ok").jsonPrimitive.boolean)
            assertEquals("module_disabled", result.getValue("error").jsonPrimitive.content)
        }
        assertEquals(0, opens)
    }
    @Test fun malformedCallDoesNotPoisonNextCallOrOtherOwner() = runBlocking {
        val first = PetRepository(ActivityToolStorage()); first.adopt("甲")
        val second = PetRepository(ActivityToolStorage()); second.adopt("乙")
        val tool = petTool({ true }) { first }
        assertFalse(call(tool, """{"action":"feed"}""").getValue("ok").jsonPrimitive.boolean)
        assertTrue(call(tool, """{"action":"play","operation_id":"one"}""").getValue("ok").jsonPrimitive.boolean)
        assertTrue(first.snapshot().messy); assertFalse(second.snapshot().messy)
        call(tool, """{"action":"decorate","operation_id":"two","decoration":"rose"}""")
        assertFalse(call(tool, """{"action":"decorate","operation_id":"two","decoration":"sky"}""").getValue("ok").jsonPrimitive.boolean)
        assertEquals("rose", first.snapshot().decoration)
    }
    @Test fun studyFailureIsLocalAndCanImmediatelyReadProgress() = runBlocking {
        val repo = StudyRepository(ActivityToolStorage())
        val tool = studyTool({ true }) { repo }
        val failed = call(tool, """{"action":"commit","subject":"数学","expected_revision":0,"operation_id":"x","questions":[]}""")
        assertFalse(failed.getValue("ok").jsonPrimitive.boolean)
        val next = call(tool, """{"action":"read_progress","subject":"数学"}""")
        assertTrue(next.getValue("ok").jsonPrimitive.boolean)
        assertEquals(0L, repo.progress("数学").revision)
    }
    @Test fun aiCanReadEntireLibraryAnswersAndFocusBeyondFirstPage() = runBlocking {
        val repo=StudyRepository(ActivityToolStorage())
        val questions=(1..63).map {StudyInputQuestion("written","题目$it",answers=listOf("要点$it"))}
        val s=repo.commitQuestions("语文",0,"many",questions,"计划")
        repo.draft(s.id,s.questionIds.first(),listOf("作答"));repo.submit(s.id)
        val f=repo.startFocus("语文");repo.checkpointFocus(f.id,65000,true)
        val tool=studyTool({true}){repo}
        for(action in listOf("library","pending","session")) {
            var offset=0;var count=0
            do {
                val result=call(tool,"""{"action":"$action","session_id":"${s.id}","offset":$offset,"limit":20}""").getValue("result").jsonObject
                count+=result.getValue("items").jsonArray.size
                val next=result["next_offset"]
                offset=if(next==JsonNull) -1 else next!!.jsonPrimitive.int
            } while(offset>=0)
            assertEquals(63,count)
        }
        val focus=call(tool,"""{"action":"focus"}""").getValue("result").jsonObject
        assertEquals(65000L,focus.getValue("statistics").jsonObject.getValue("focusMillis").jsonPrimitive.long)
        assertEquals(1,focus.getValue("records").jsonObject.getValue("items").jsonArray.size)
        val session=call(tool,"""{"action":"session","session_id":"${s.id}"}""").getValue("result").jsonObject
        val entry=session.getValue("items").jsonArray.first().jsonObject
        assertEquals("作答",entry.getValue("attempt").jsonObject.getValue("answer").jsonArray.single().jsonPrimitive.content)
        assertEquals("要点1",entry.getValue("reference").jsonObject.getValue("answers").jsonArray.single().jsonPrimitive.content)
    }
    @Test fun missingJudgeFieldIsAccurateAndNextValidGradingStillWorks() = runBlocking {
        val storage=ActivityToolStorage();val repo=StudyRepository(storage);val tool=studyTool({true}){repo}
        val s=repo.commitQuestions("语文",0,"q",listOf(StudyInputQuestion("written","说明",answers=listOf("要点"))),"")
        repo.submit(s.id);val before=storage.saved
        val bad=call(tool,"""{"action":"judge","question_id":"${s.questionIds.single()}","score":80,"comment":"评价"}""")
        assertEquals("missing_session_id",bad.getValue("error").jsonPrimitive.content)
        assertEquals(before,storage.saved)
        val good=call(tool,"""{"action":"judge","session_id":"${s.id}","question_id":"${s.questionIds.single()}","score":80,"comment":"评价"}""")
        assertTrue(good.getValue("ok").jsonPrimitive.boolean)
        assertEquals(80,repo.snapshot().sessions.single().attempts.single().judgements.single().score)
    }
    @Test fun wrongbookWithoutSubjectIncludesAllSubjectsAndRejectsInvalidPagingLocally() = runBlocking {
        val repo=StudyRepository(ActivityToolStorage());val tool=studyTool({true}){repo}
        for(subject in listOf("数学","英语")) {
            val s=repo.commitQuestions(subject,0,subject.hashCode().toString().replace('-','_'),listOf(StudyInputQuestion("fill","填写",answers=listOf("yes"))),"")
            repo.submit(s.id)
        }
        val all=call(tool,"""{"action":"wrongbook"}""").getValue("result").jsonObject
        assertEquals(2,all.getValue("total").jsonPrimitive.int)
        assertFalse(call(tool,"""{"action":"library","offset":-1}""").getValue("ok").jsonPrimitive.boolean)
        assertTrue(call(tool,"""{"action":"overview"}""").getValue("ok").jsonPrimitive.boolean)
    }
}
