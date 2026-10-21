package me.rerere.rikkahub.data.orbis.activities

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

private class CompanionStorage : ActivityStorage {
    var saved: String?=null;var fail=false;var writes=0
    override fun read()=saved
    override fun write(text:String) {check(!fail);saved=text;writes++}
}
class StudyCompanionTest {
    private val written=StudyInputQuestion("written","解释一下",answers=listOf("参考要点"))
    @Test fun learningDayCountsPendingOnceAndSurvivesRestart() {
        var now=Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val storage=CompanionStorage();val repo=StudyRepository(storage,{now},zone={ZoneId.of("UTC")})
        fun submit(key:String,answer:String="答题") {
            val s=repo.commitQuestions("语文",repo.progress("语文").revision,key,listOf(written),"")
            repo.draft(s.id,s.questionIds.single(),listOf(answer));repo.submit(s.id);repo.submit(s.id)
        }
        submit("a");submit("b")
        assertEquals(1,repo.statistics().cumulativeDays);assertEquals(2,repo.statistics().pendingCount)
        now+=86_400_000;assertEquals(1,repo.statistics().consecutiveDays);assertFalse(repo.statistics().studiedToday)
        submit("c");assertEquals(2,repo.statistics().consecutiveDays)
        val restarted=StudyRepository(storage,{now},zone={ZoneId.of("UTC")})
        assertEquals(repo.statistics(),restarted.statistics())
        val s=restarted.snapshot().sessions.first();restarted.judge(s.id,s.questionIds.single(),88,"评语")
        assertEquals(2,restarted.statistics().cumulativeDays)
        now+=2*86_400_000;assertEquals(0,restarted.statistics().consecutiveDays);assertEquals(2,restarted.statistics().cumulativeDays)
    }
    @Test fun emptyDraftAndBlankSubmissionAreNotLearningDays() {
        val repo=StudyRepository(CompanionStorage())
        val s=repo.commitQuestions("语文",0,"empty",listOf(written),"")
        assertEquals(0,repo.statistics().cumulativeDays)
        repo.draft(s.id,s.questionIds.single(),listOf("   "));repo.submit(s.id)
        assertEquals(0,repo.statistics().cumulativeDays)
    }
    @Test fun oldDocumentsLoadAndNewDatesKeepOriginalZone() {
        val storage=CompanionStorage();storage.saved="""{"version":1}"""
        var now=Instant.parse("2026-10-19T00:30:00Z").toEpochMilli()
        var zone=ZoneId.of("America/Los_Angeles")
        val repo=StudyRepository(storage,{now},zone={zone})
        val s=repo.commitQuestions("语文",0,"zone",listOf(written),"")
        repo.draft(s.id,s.questionIds.single(),listOf("回答"));repo.submit(s.id)
        assertEquals("2026-10-18",repo.snapshot().sessions.single().submittedDay)
        zone=ZoneId.of("Asia/Shanghai")
        assertEquals(listOf("2026-10-18"),repo.statistics().studyDays)
        assertFalse(repo.statistics().legacyDatesEstimated)
        val legacy=repo.snapshot().copy(sessions=repo.snapshot().sessions.map {it.copy(submittedDay=null,submittedZone=null)})
        assertTrue(StudyStatistics.from(legacy,now,zone).legacyDatesEstimated)
    }
    @Test fun monotonicTimerDoesNotCountPauseAndNeverExceedsOneDay() {
        var elapsed=100L;val clock=StudyFocusClock(1200){elapsed}
        elapsed+=80000;assertEquals(1200L,clock.value())
        clock.resume();elapsed+=3000;assertEquals(4200L,clock.pause())
        elapsed+=900000;assertEquals(4200L,clock.value())
        clock.resume();clock.resume();elapsed+=1000;assertEquals(5200L,clock.value())
        elapsed+=2*StudyFocusSession.MAX_MILLIS;assertEquals(StudyFocusSession.MAX_MILLIS,clock.pause())
    }
    @Test fun focusCheckpointsAreAbsoluteDurableAndNotAiGradingEvents() {
        val storage=CompanionStorage();var now=1_000L;val repo=StudyRepository(storage,{now})
        val session=repo.startFocus("英语")
        assertEquals(session,repo.startFocus("数学"))
        now+=30_000;repo.checkpointFocus(session.id,20_000)
        val writes=storage.writes;repo.checkpointFocus(session.id,20_000);assertEquals(writes,storage.writes)
        val restarted=StudyRepository(storage,{now})
        assertEquals(20_000L,restarted.statistics().focusMillis)
        now+=999_000;assertEquals(20_000L,restarted.statistics().focusMillis)
        restarted.checkpointFocus(session.id,25_000,true)
        restarted.checkpointFocus(session.id,25_000,true)
        assertEquals(25_000L,restarted.statistics().focusMillis);assertEquals(1,restarted.statistics().finishedFocusCount)
        assertEquals(0,restarted.statistics().cumulativeDays)
        assertThrows(IllegalArgumentException::class.java){restarted.checkpointFocus(session.id,26_000)}
        assertEquals(repo.snapshot().questions,restarted.snapshot().questions)
        val text=Json.encodeToString(restarted.snapshot());StudyRepository.validate(Json.decodeFromString<StudyState>(text))
    }
    @Test fun failedFocusWriteDoesNotClaimSuccessOrChangePriorAnswers() {
        val storage=CompanionStorage();val repo=StudyRepository(storage)
        val s=repo.commitQuestions("英语",0,"one",listOf(written),"计划")
        repo.draft(s.id,s.questionIds.single(),listOf("我的答案"))
        val focus=repo.startFocus("英语");val before=storage.saved
        storage.fail=true
        assertThrows(IllegalStateException::class.java){repo.checkpointFocus(focus.id,10000,true)}
        assertEquals(before,storage.saved);assertEquals(0L,repo.statistics().focusMillis)
        assertEquals(listOf("我的答案"),repo.snapshot().sessions.single().attempts.single().answer)
        storage.fail=false;repo.reload();repo.checkpointFocus(focus.id,10000,true)
        assertEquals(10000L,repo.statistics().focusMillis)
    }
    @Test fun ownerRepositoriesNeverShareFocusOrQuestions() {
        val first=StudyRepository(CompanionStorage());val second=StudyRepository(CompanionStorage())
        val f=first.startFocus("语文");first.checkpointFocus(f.id,42000,true)
        first.commitQuestions("语文",0,"a",listOf(written),"")
        assertEquals(0L,second.statistics().focusMillis);assertTrue(second.snapshot().questions.isEmpty())
    }
}
