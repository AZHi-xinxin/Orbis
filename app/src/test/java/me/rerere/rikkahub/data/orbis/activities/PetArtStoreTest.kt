package me.rerere.rikkahub.data.orbis.activities

import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PetArtStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private fun hash(bytes:ByteArray)=ByteArrayInputStream(bytes).use(::petArtSha256)
    private fun pack(id:String,entries:Map<String,ByteArray>):Pair<File,PetArtPackage> {
        val file=temp.newFile("$id.zip")
        ZipOutputStream(file.outputStream()).use {zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()} }
        return file to PetArtPackage(hash(file.readBytes()),file.length(),entries.mapValues {PetArtFile(hash(it.value),it.value.size.toLong())})
    }
    private fun store(packs:Map<String,PetArtPackage>)=PetArtStore(File(temp.root,"installed"),PetArtCatalog(1,"https://example.invalid/",packs))
    @Test fun verifiedPackCommitsOnceAndNeverKeepsArchiveCopy() {
        val (file,spec)=pack("cat",mapOf("cat.webp" to byteArrayOf(1,2,3)))
        val s=store(mapOf("cat" to spec));val dir=s.install("cat",file)
        assertTrue(s.ready("cat"));assertEquals(dir,s.install("cat",file))
        assertEquals(setOf("cat.webp",".complete"),dir.list()!!.toSet())
        assertEquals(1,dir.parentFile.listFiles()!!.size)
    }
    @Test fun wrongHashDoesNotTouchPreviouslyInstalledArt() {
        val (file,spec)=pack("cat",mapOf("cat.webp" to byteArrayOf(1,2,3)))
        val s=store(mapOf("cat" to spec));val dir=s.install("cat",file)
        file.writeBytes(ByteArray(file.length().toInt()))
        assertTrue(runCatching {s.install("cat",file)}.isFailure)
        assertArrayEquals(byteArrayOf(1,2,3),File(dir,"cat.webp").readBytes())
    }
    @Test fun traversalAndDuplicateOrUnknownEntriesNeverEscapeStaging() {
        val (file,spec)=pack("cat",mapOf("../outside.webp" to byteArrayOf(1)))
        val s=store(mapOf("cat" to spec))
        assertTrue(runCatching {s.install("cat",file)}.isFailure)
        assertFalse(File(temp.root,"outside.webp").exists());assertFalse(s.ready("cat"))
        assertTrue(s.directory("cat").parentFile.listFiles()!!.isEmpty())
    }
    @Test fun missingEntryDoesNotCommitOrLeaveAStagingDirectory() {
        val (file,base)=pack("cat",mapOf("cat.webp" to byteArrayOf(1)))
        val spec=base.copy(files=base.files+ ("room.webp" to PetArtFile(hash(byteArrayOf(2)),1)))
        val s=store(mapOf("cat" to spec));assertTrue(runCatching {s.install("cat",file)}.isFailure)
        assertFalse(s.ready("cat"));assertTrue(s.directory("cat").parentFile.listFiles()!!.isEmpty())
    }
    @Test fun cleanupKeepsAllAdoptedIdentitiesAndUnknownOrModifiedFiles() {
        val packs=listOf("cat","dog","bird").associateWith {pack(it,mapOf("cat.webp" to byteArrayOf(1,2,3)))}
        val s=store(packs.mapValues {it.value.second});packs.forEach {(id,p)->s.install(id,p.first)}
        val custom=File(s.directory("bird"),"cat.webp");custom.writeBytes(byteArrayOf(3,2,1))
        assertEquals(0L,s.removeUnused(setOf("cat","dog")))
        assertTrue(s.ready("cat"));assertTrue(s.ready("dog"));assertTrue(custom.exists())
        assertTrue(s.removeUnused(setOf("cat"))>0)
        assertTrue(s.ready("cat"));assertFalse(s.directory("dog").exists());assertTrue(custom.exists())
    }
    @Test fun cleanupDoesNotFollowSymlinks() {
        val (file,spec)=pack("cat",mapOf("cat.webp" to byteArrayOf(1,2,3)))
        val s=store(mapOf("cat" to spec));val dir=s.install("cat",file)
        val outside=temp.newFile("precious");outside.writeBytes(byteArrayOf(1,2,3))
        File(dir,"cat.webp").delete();java.nio.file.Files.createSymbolicLink(File(dir,"cat.webp").toPath(),outside.toPath())
        assertEquals(0L,s.removeUnused(emptySet()));assertTrue(outside.exists())
    }
    @Test fun offlineUpgradeKeepsAdoptedOldArtUntilNewPackCommits() {
        val (oldFile,oldSpec)=pack("old",mapOf("cat.webp" to byteArrayOf(1,2,3)))
        val oldStore=store(mapOf("cat" to oldSpec));val oldDir=oldStore.install("cat",oldFile)
        val (newFile,newSpec)=pack("new",mapOf("cat.webp" to byteArrayOf(4,5,6),"care.webp" to byteArrayOf(7)))
        val next=PetArtStore(File(temp.root,"installed"),PetArtCatalog(1,"https://example.invalid/",mapOf("cat" to newSpec),mapOf("cat" to listOf(oldSpec))))
        assertEquals(oldDir,next.availableDirectory("cat"))
        assertEquals(0L,next.removeSuperseded(setOf("cat")));assertTrue(oldDir.isDirectory)
        val current=next.install("cat",newFile)
        assertEquals(current,next.availableDirectory("cat"))
        assertTrue(next.removeSuperseded(setOf("cat"))>0)
        assertFalse(oldDir.exists());assertTrue(next.ready("cat"))
    }
    @Test fun modifiedOldArtIsNotDeletedDuringUpgrade() {
        val (oldFile,oldSpec)=pack("old",mapOf("cat.webp" to byteArrayOf(1,2,3)))
        val oldDir=store(mapOf("cat" to oldSpec)).install("cat",oldFile)
        val (newFile,newSpec)=pack("new",mapOf("cat.webp" to byteArrayOf(4,5,6)))
        val next=PetArtStore(File(temp.root,"installed"),PetArtCatalog(1,"https://example.invalid/",mapOf("cat" to newSpec),mapOf("cat" to listOf(oldSpec))))
        next.install("cat",newFile);File(oldDir,"cat.webp").writeBytes(byteArrayOf(9,9,9))
        assertEquals(0L,next.removeSuperseded(emptySet()));assertTrue(oldDir.exists())
    }
}
