package me.rerere.rikkahub.data.orbis.activities

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test

class PetArtLifecycleDeviceTest {
    private fun fixture(test: (Context, File) -> Unit) {
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        val target=inst.targetContext;assertEquals(Application::class.java,target.applicationContext.javaClass)
        val root=File(target.cacheDir.canonicalFile,"pet-lifecycle-${UUID.randomUUID()}").apply {mkdir()}
        val context=object:ContextWrapper(target) {override fun getFilesDir()=root}
        try {test(context,File(target.filesDir,"pet-art-fixtures"))}
        finally {check(root.parentFile==target.cacheDir.canonicalFile);root.deleteRecursively()}
    }
    @Test fun allFortyOptionalPacksInstallDecodeAndReopen()=fixture {ctx,fixtures ->
        val catalog=PetArtDownloads.catalog(ctx);assertEquals(40,catalog.packages.size)
        catalog.packages.forEach { (id,spec) ->
            val root=PetArtDownloads.store(ctx).install(id,File(fixtures,"$id.zip"))
            spec.files.keys.filter {it.endsWith(".webp")}.forEach { name ->
                val bitmap=BitmapFactory.decodeFile(File(root,name).path)
                assertNotNull("$id/$name",bitmap);bitmap!!.recycle()
            }
            assertTrue(PetArtDownloads.store(ctx).ready(id))
        }
        assertTrue(PetArtDownloads.cleanUnused(ctx)>0)
        catalog.packages.keys.forEach {assertFalse(PetArtDownloads.store(ctx).ready(it))}
    }
    @Test fun cleanupProtectsEveryAdoptionAndCustomFiles()=fixture {ctx,fixtures ->
        val ids=listOf("cat-black-v1","dog-shiba-v1","bird-budgie-v1")
        ids.forEach {PetArtDownloads.store(ctx).install(it,File(fixtures,"$it.zip"))}
        for((owner,id) in listOf("a" to ids[0],"disabled-b" to ids[1])) {
            File(ctx.filesDir,"orbis-activities/$owner").apply {mkdirs()}
                .resolve("pet.json").writeText("""{"pet":{"appearance":"$id"}}""")
        }
        val custom=File(ctx.filesDir,"orbis-pet-assets/${ids[2]}").apply {mkdirs()}
        File(custom,"cat.png").writeText("custom pixels")
        assertTrue(PetArtDownloads.cleanUnused(ctx)>0)
        assertTrue(PetArtDownloads.store(ctx).ready(ids[0]));assertTrue(PetArtDownloads.store(ctx).ready(ids[1]))
        assertFalse(PetArtDownloads.store(ctx).ready(ids[2]))
        assertEquals("custom pixels",File(custom,"cat.png").readText())
    }
    @Test fun unreadableOrRecoveringArchiveStopsCleanup()=fixture {ctx,fixtures ->
        val id="cat-black-v1";PetArtDownloads.store(ctx).install(id,File(fixtures,"$id.zip"))
        val owner=File(ctx.filesDir,"orbis-activities/owner").apply {mkdirs()}
        File(owner,"pet.json.bak").writeText("pending recovery")
        assertEquals(0L,PetArtDownloads.cleanUnused(ctx));assertTrue(PetArtDownloads.store(ctx).ready(id))
        File(owner,"pet.json.bak").delete();File(owner,"pet.json").writeText("broken")
        assertThrows(Exception::class.java) {PetArtDownloads.cleanUnused(ctx)}
        assertTrue(PetArtDownloads.store(ctx).ready(id))
    }
}
