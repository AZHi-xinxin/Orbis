package me.rerere.rikkahub.data.orbis.character

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterCoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    // Non-image fixture: Android decoding is deliberately a separate instrumented test.
    private val png = "synthetic-only-image-content".toByteArray()
    private fun draft() = CharacterDraft(CharacterPack(sha256 = characterSha(png), width = 100, height = 200), CharacterInteractions(), png)
    private fun richDraft() = draft().copy(interactions = CharacterInteractions(lines = mapOf("head" to listOf("private synthetic line"))))
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it.first)); z.write(it.second); z.closeEntry() } } }.toByteArray()

    @Test fun dayBoundariesUsePhoneHour() {
        assertEquals(RoomLight.NIGHT, roomLight(5, RoomMode.AUTO)); assertEquals(RoomLight.MORNING, roomLight(6, RoomMode.AUTO))
        assertEquals(RoomLight.MORNING, roomLight(9, RoomMode.AUTO)); assertEquals(RoomLight.DAY, roomLight(10, RoomMode.AUTO))
        assertEquals(RoomLight.DAY, roomLight(16, RoomMode.AUTO)); assertEquals(RoomLight.DUSK, roomLight(17, RoomMode.AUTO))
        assertEquals(RoomLight.DUSK, roomLight(18, RoomMode.AUTO)); assertEquals(RoomLight.NIGHT, roomLight(19, RoomMode.AUTO))
        assertEquals(RoomLight.NIGHT, roomLight(0, RoomMode.AUTO)); assertEquals(RoomLight.NIGHT, roomLight(23, RoomMode.AUTO))
    }
    @Test fun fixedRoomNeverFollowsHour() { (0..23).forEach { assertEquals(RoomLight.DAY, roomLight(it, RoomMode.DAY)); assertEquals(RoomLight.NIGHT, roomLight(it, RoomMode.NIGHT)) } }
    @Test fun explicitBindingWinsOverListOrder() { assertEquals(owner,resolveCharacterOwner(owner,listOf(other,owner),true)) }
    @Test fun absentOwnerDoesNotFallBackToOtherAssistant() { assertNull(resolveCharacterOwner(owner,listOf(other),true)) }
    @Test fun loadingSettingsDoesNotResolveEvenMatchingOwner() { assertNull(resolveCharacterOwner(owner,listOf(owner),false)) }
    @Test fun noBindingDoesNotUseFirstAssistant() { assertNull(resolveCharacterOwner(null,listOf(owner),true)) }
    @Test fun malformedBindingDoesNotResolve() { assertNull(resolveCharacterOwner("../$owner",listOf("../$owner"),true)) }
    @Test fun bindingStillResolvesAfterNewStoreInstance() { assertEquals(owner,resolveCharacterOwner(owner,listOf(owner),true)); assertNull(resolveCharacterOwner(other,listOf(owner),true)) }
    @Test fun fitExcludesHorizontalLetterbox() { val f = characterFrame(1000f, 1000f, 100, 200); assertEquals(250f, f.left); assertNull(f.point(249f, 500f)); assertEquals(CharacterPoint(.5f, .5f), f.point(500f, 500f)) }
    @Test fun fitExcludesVerticalLetterbox() { val f = characterFrame(1000f, 1000f, 200, 100); assertEquals(250f, f.top); assertNull(f.point(500f, 249f)); assertEquals(CharacterPoint(0f, 0f), f.point(0f, 250f)) }
    @Test fun resizingDoesNotChangeHit() { listOf(300f to 800f, 800f to 300f).forEach { (w,h) -> val f = characterFrame(w,h,100,200); assertEquals("head", characterHit(defaultCharacterRegions(), f.point(f.left + f.width * .5f, f.top + f.height * .1f)!!)!!.id) } }
    @Test fun emptySpaceDoesNotHitBody() { assertNull(characterHit(defaultCharacterRegions(), CharacterPoint(.01f, .99f))) }
    @Test fun overlapsUseDocumentOrder() { val r = defaultCharacterRegions().first(); assertEquals("head", characterHit(listOf(r, r.copy(id="other")), CharacterPoint(.5f,.1f))!!.id) }
    @Test fun nonFinitePointNeverHits() { assertNull(characterHit(defaultCharacterRegions(), CharacterPoint(Float.NaN,.1f))) }
    @Test fun zeroSizedViewDoesNotHit() { assertNull(characterFrame(0f, 0f, 100, 200).point(0f,0f)) }
    @Test fun cooldownIsBounded() { var now = 0L; val gate = CharacterTapGate { now }; assertTrue(gate.accept()); now=499; assertFalse(gate.accept()); now=500; assertTrue(gate.accept()) }
    @Test fun defaultPackValid() { CharacterRules.validate(draft().pack) }
    @Test fun rejectsInvalidRegions() { listOf(Float.NaN, -1f, 2f).forEach { x -> assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(draft().pack.copy(regions = listOf(defaultCharacterRegions().first().copy(x=x)))) } } }
    @Test fun rejectsOffCanvasRectangle() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(draft().pack.copy(regions=listOf(defaultCharacterRegions().first().copy(x=.9f)))) } }
    @Test fun duplicateRegionIdsReject() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(draft().pack.copy(regions=listOf(defaultCharacterRegions().first(),defaultCharacterRegions().first()))) } }
    @Test fun unknownRegionLineRejects() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(CharacterProfile(pack=draft().pack,interactions=CharacterInteractions(lines=mapOf("unknown" to listOf("test"))))) } }
    @Test fun longLineRejects() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(CharacterProfile(pack=draft().pack,interactions=CharacterInteractions(lines=mapOf("head" to listOf("x".repeat(241)))))) } }
    @Test fun portablePackRejectsImagePath() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(draft().pack.copy(image="../secret.png")) } }
    @Test fun ownerPathTraversalRejects() { assertThrows(IllegalArgumentException::class.java) { CharacterStore(folder.root, "../$owner") } }
    @Test fun assistantsAreIsolatedAcrossRestart() { val a=CharacterStore(folder.root, owner); a.install(richDraft(),0); assertNull(CharacterStore(folder.root,other).read().pack); assertEquals(richDraft().interactions,CharacterStore(folder.root,owner).read().interactions) }
    @Test fun staleSaveNeverOverwrites() { val store=CharacterStore(folder.root,owner); val p=store.install(draft(),0); store.save(p.copy(room=RoomMode.NIGHT),1); assertThrows(IllegalArgumentException::class.java) { store.save(p,1) }; assertEquals(RoomMode.NIGHT,store.read().room) }
    @Test fun brokenInstallLeavesOldRole() { val store=CharacterStore(folder.root,owner); val p=store.install(richDraft(),0); assertThrows(IllegalArgumentException::class.java) { store.install(draft().copy(png="broken".toByteArray()),1) }; assertEquals(p,store.read()) }
    @Test fun tamperedInstalledImageRejects() { val store=CharacterStore(folder.root,owner); val p=store.install(draft(),0); store.imageFile(p.pack!!).writeText("changed"); assertThrows(IllegalArgumentException::class.java) { store.imageBytes(p.pack!!) } }
    @Test fun exportExcludesPrivateLinesByDefault() { val out=ByteArrayOutputStream(); CharacterArchive.write(out,richDraft(),false); assertTrue(CharacterArchive.read(out.toByteArray().inputStream()).interactions.lines.isEmpty()) }
    @Test fun explicitLineExportRoundTrips() { val out=ByteArrayOutputStream(); CharacterArchive.write(out,richDraft(),true); val restored=CharacterArchive.read(out.toByteArray().inputStream()); assertEquals(richDraft().pack,restored.pack); assertEquals(richDraft().interactions,restored.interactions) }
    @Test fun zipTraversalRejectedWithoutWriting() { assertThrows(IllegalArgumentException::class.java) { CharacterArchive.read(zip("../portrait.png" to png).inputStream()) } }
    @Test fun scriptEntryRejected() { assertThrows(IllegalArgumentException::class.java) { CharacterArchive.read(zip("script.js" to png).inputStream()) } }
    @Test fun oversizedInflatedEntryRejected() { assertThrows(IllegalArgumentException::class.java) { CharacterArchive.read(zip("manifest.json" to ByteArray(CharacterRules.JSON_BYTES+1)).inputStream()) } }
    @Test fun missingPortraitRejected() { assertThrows(IllegalArgumentException::class.java) { CharacterArchive.read(zip("interactions.json" to "{}".toByteArray()).inputStream()) } }
    @Test fun unknownVersionRejected() { assertThrows(IllegalArgumentException::class.java) { CharacterRules.validate(draft().pack.copy(format="orbis.character/9")) } }
    @Test fun movingExportToOtherOwnerDoesNotCarryIdentity() { val a=CharacterStore(folder.root,owner); a.install(richDraft(),0); val out=ByteArrayOutputStream(); a.export(out,false); val b=CharacterStore(folder.root,other); b.install(CharacterArchive.read(out.toByteArray().inputStream()),0); assertTrue(b.read().interactions.lines.isEmpty()); assertEquals(1L,b.read().revision) }
}
