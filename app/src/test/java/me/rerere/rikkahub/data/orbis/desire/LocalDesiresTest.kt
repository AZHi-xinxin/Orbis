package me.rerere.rikkahub.data.orbis.desire

import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import me.rerere.rikkahub.data.orbis.memory.*
import org.junit.Assert.*
import org.junit.Test

class LocalDesiresTest {
    private class Storage:ActivityStorage { var text:String?=null; override fun read()=text;override fun write(text:String){this.text=text} }
    @Test fun countsAtNineTenElevenAndArchivedParentRemainsUntouched() {
        val storage=Storage();val s=LocalDesireRepository(storage){42}
        (1..11).forEach { s.put("m$it","一起看看第 $it 个地方",0) }
        val ids=(1..11).map{"m$it"}.toSet()
        assertEquals(11,pendingDesires(s.snapshot().entries,ids));assertTrue(desiresOverflow(11));assertFalse(desiresOverflow(10));assertFalse(desiresOverflow(9))
        s.transition("m1","archived",1);assertEquals(10,pendingDesires(s.snapshot().entries,ids))
        assertEquals(9,pendingDesires(s.snapshot().entries,ids-"m2"))
        assertEquals("一起看看第 1 个地方",s.snapshot().entries.first{it.memoryId=="m1"}.text)
        assertEquals(12,s.snapshot().history.size)
        assertThrows(IllegalStateException::class.java){s.put("m1","stale",1)}
        assertEquals(10,pendingDesires(LocalDesireRepository(storage).snapshot().entries,ids))
    }
    @Test fun deleteKeepsHistoryAndCannotResurrectWithoutNewText() {
        val s=LocalDesireRepository(Storage()){1};s.put("a","愿望",0);s.transition("a","deleted",1)
        assertEquals("",s.snapshot().entries.single().text)
        assertThrows(IllegalStateException::class.java){s.transition("a","active",2)}
        assertEquals("愿望",s.snapshot().history.first().text)
        assertThrows(IllegalArgumentException::class.java){s.put("a","长".repeat(201),2)}
    }
    @Test fun onlyAlreadyRecalledNonPinnedNotesReceiveBoundedTailAnnotations() {
        val entries=listOf(LocalDesire("a","去海边"),LocalDesire("pin","不改前缀"),LocalDesire("unseen","不能额外召回"))
        val original=OrbisMemorySelection(listOf(OrbisMemorySelected("pin","稳定",1,"pinned"),OrbisMemorySelected("a","记得海浪",1,"original")))
        val result=attachLocalDesires(original,desireAnnotations(entries),OrbisMemoryMode.STANDARD)
        assertNull(result.entries.first().desire);assertEquals("想做的事：去海边",result.entries.last().desire)
        assertEquals(original.entries.map{it.text},result.entries.map{it.text})
        assertEquals(2,result.entries.size)
        val archived=attachLocalDesires(original,desireAnnotations(listOf(entries.first().copy(state="archived"))),OrbisMemoryMode.STANDARD)
        assertEquals("想做的事：已归档",archived.entries.last().desire)
    }
}
