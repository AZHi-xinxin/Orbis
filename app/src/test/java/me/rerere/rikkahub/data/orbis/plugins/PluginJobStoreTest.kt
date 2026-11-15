package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginJobStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private fun store()=PluginJobStore(File(temp.root,"jobs/jobs.json"))
    private fun job(interval: Long?=null)=PluginJob(pluginId="plugin",hash="approved-hash",conversationId="original-chat",
        arguments=buildJsonObject {put("mode","check")},nextAt=1_000,intervalMinutes=interval)
    @Test fun oneShotIsClaimedOnceAcrossProcessReloadEvenWithoutCompletion() {
        val j=store().add(job(),0)
        assertNull(store().claim(j.id,999))
        val claim=store().claim(j.id,1000)!!
        assertEquals("running",claim.status)
        assertNull(store().claim(j.id,1001));assertNull(store().claim(j.id,Long.MAX_VALUE-1))
        assertEquals("original-chat",store().list().single().conversationId)
    }
    @Test fun periodicClaimsCannotRepeatOccurrenceAndCancelledRunCannotResurrect() {
        val j=store().add(job(15),0)
        store().claim(j.id,1000)!!
        assertNull(store().claim(j.id,1001));assertNull(store().claim(j.id,900_999))
        val next=store().claim(j.id,901_000)!!
        assertEquals(901_000L,next.lastAt)
        store().pause(j.id);store().finish(j.id,901_000,"completed")
        assertFalse(store().list().single().enabled);assertEquals("paused",store().list().single().status)
        assertNull(store().claim(j.id,9_000_000))
    }
    @Test fun oldCompletionCannotOverwriteNewOccurrence() {
        val j=store().add(job(15),0)
        store().claim(j.id,1000);store().claim(j.id,901_000)
        store().finish(j.id,1000,"completed")
        assertEquals("running",store().list().single().status)
    }
    @Test fun schedulesAreBoundedAndCancelledJobsCanBeRemoved() {
        assertThrows(IllegalArgumentException::class.java) {store().add(job(1),0)}
        assertThrows(IllegalArgumentException::class.java) {store().add(job().copy(nextAt=-1),0)}
        repeat(10) {store().add(job(),0)}
        assertThrows(IllegalStateException::class.java) {store().add(job(),0)}
        store().delete(store().list().first().id)
        assertEquals(9,store().list().size)
    }
}
