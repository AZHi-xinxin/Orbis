package me.rerere.rikkahub.data.orbis.skills
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
class WorkspaceSkillBindingsTest {
 @Test fun conflictsShowBothPathsAndNeverReplaceTheWorkingBinding()=runBlocking {
  val s=store();s.register("/workspace/skills/play",{listOf("SKILL.md")},{skill("play").toByteArray()})
  val r=s.register("/skills/play",{listOf("SKILL.md")},{skill("play").toByteArray()})
  assertEquals("conflict",r.ms("status"));assertEquals(1,r.mi("conflicts"));assertEquals(0,r.mi("registered"))
  val item=(r["items"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
  assertEquals("skill_name_conflict",item.ms("reason_code"));assertEquals("/workspace/skills/play",item.ms("bound_path"))
  assertEquals("/skills/play",item.ms("path"));assertEquals("/workspace/skills/play",s.list().single().ms("path"))
 }
 @Test fun emptyMissingAndUnreadableDirectoriesAreDistinctAndDoNotWrite()=runBlocking {
  val s=WorkspaceSkillBindings(folder.root,owner,workspace,persist={_,_->error("must not write")})
  val empty=s.register("/skills",{emptyList()},{error("must not read")})
  assertEquals("empty",empty.ms("status"));assertEquals("skill_directory_empty",empty.ms("reason_code"))
  assertEquals(0,empty.mi("directory_entries"));assertEquals(0,empty.mi("candidates_scanned"))
  for((error,expected) in listOf("Path does not exist: /skills" to "skill_directory_missing", "Directory listing failed: /skills" to "skill_directory_unavailable")) {
   val failure=runCatching{s.register("/skills",{throw java.io.IOException(error)},{error("must not read")})}.exceptionOrNull() as WorkspaceSkillFailure
   assertEquals(expected,failure.code)
  }
 }
 @get:Rule val folder=TemporaryFolder()
 private val owner=UUID.randomUUID().toString();private val workspace=UUID.randomUUID().toString()
 private fun store(who:String=owner,space:String=workspace)=WorkspaceSkillBindings(folder.root,who,space,persist={file,bytes->file.parentFile.mkdirs();file.writeBytes(bytes)})
 private fun skill(name:String="sample")="---\nname: $name\ndescription: A synthetic skill\n---\nRead [guide](references/guide.md)."
 @Test fun registerSnapshotOwnScopePersistenceReferencesAndUnbind()=runBlocking {
  val s=store();val files=mapOf("/workspace/example/SKILL.md" to skill().toByteArray(),"/workspace/example/references/guide.md" to "unchanged reference".toByteArray())
  suspend fun read(path:String)=files.getValue(path)
  val frozen=s.list();val receipt=s.register("/workspace/example",{listOf("SKILL.md","references")},::read)
  assertEquals(1,receipt.mi("registered"));assertTrue(frozen.isEmpty());assertEquals(1,store().list().size)
  assertTrue(store(UUID.randomUUID().toString()).list().isEmpty());assertTrue(store(space=UUID.randomUUID().toString()).list().isEmpty())
  assertEquals("unchanged reference",s.read("sample","references/guide.md",::read))
  assertEquals(1,s.register("/workspace/example",{listOf("SKILL.md")},::read).mi("already_bound"))
  assertTrue(s.remove("sample").mb("removed"));assertTrue(s.list().isEmpty());assertEquals(2,files.size)
 }
 @Test fun malformedMissingDuplicateNamesAndPathEscapeDoNotOverwrite()=runBlocking {
  val s=store();val first=s.register("/workspace/a",{listOf("SKILL.md")},{skill().toByteArray()});assertEquals(1,first.mi("registered"))
  assertEquals(1,s.register("/workspace/b",{listOf("SKILL.md")},{skill().toByteArray()}).mi("skipped"))
  assertEquals(1,s.register("/workspace/missing",{listOf("SKILL.md")},{error("missing")}).mi("skipped"))
  assertEquals("/workspace/a",s.list().single().ms("path"))
  for(path in listOf("/skills-other/x","/skills/../secrets","/workspace/../secrets","/workspace/a/../../secret"))assertTrue(runCatching{s.register(path,{error("must not list")},{error("must not read")})}.isFailure)
  assertTrue(runCatching{s.read("sample","../other.md"){error("must not read")}}.isFailure)
  assertTrue(runCatching{s.read("foreign",""){error("must not read")}}.isFailure)
 }
 @Test fun revokedPermissionDuringReadDoesNotSaveBindings()=runBlocking {
  var allowed=true
  val s=WorkspaceSkillBindings(folder.root,owner,workspace,persist={file,bytes->file.parentFile.mkdirs();file.writeBytes(bytes)},stillAllowed={allowed})
  assertTrue(runCatching{s.register("/workspace/a",{listOf("SKILL.md")},{allowed=false;skill().toByteArray()})}.isFailure)
  assertTrue(s.list().isEmpty())
 }
 @Test fun parentScanReturnsIndividualReceiptsAndSupportsChineseNames()=runBlocking {
  val r=store().register("/workspace/skills",{listOf("one","two","empty")},{path->when{path.contains("/one/")->skill("工作手册").toByteArray();path.contains("/two/")->"bad".toByteArray();else->error("missing")}})
  assertEquals(1,r.mi("registered"));assertEquals(2,r.mi("skipped"));assertEquals("next_user_turn",r.ms("effective"))
 }
 @Test fun workspaceRootAndRootSkillFileAreBothValidLocations()=runBlocking {
  val s=store()
  for(path in listOf("/workspace", "/workspace/", "/workspace/SKILL.md")) {
   val result=s.register(path,{directory->assertEquals("/workspace",directory);listOf("SKILL.md")},{source->assertEquals("/workspace/SKILL.md",source);skill().toByteArray()})
   assertEquals(1,result.mi("registered")+result.mi("already_bound"))
  }
  assertEquals("/workspace",s.list().single().ms("path"))
 }
 @Test fun committedWriteWithoutReceiptIsReportedUnknownAndCanBeReconciled()=runBlocking {
  val s=WorkspaceSkillBindings(folder.root,owner,workspace,persist={file,bytes->
   file.parentFile.mkdirs();file.writeBytes(bytes);throw java.io.IOException("synthetic failure after commit")
  })
  val failed=runCatching{s.register("/workspace/demo",{listOf("SKILL.md")},{skill().toByteArray()})}.exceptionOrNull() as WorkspaceSkillFailure
  assertEquals("skill_binding_write_unknown",failed.code);assertEquals("unknown",failed.bindingState)
  assertEquals("sample",s.list().single().ms("name"))
 }
 @Test fun corruptRegistryIsPreservedInsteadOfBeingReplacedWithAnEmptyList()=runBlocking {
  val registry=File(folder.root,"$owner/$workspace.json").apply{parentFile.mkdirs();writeText("invalid registry")}
  val failed=runCatching{store().register("/workspace/demo",{listOf("SKILL.md")},{skill().toByteArray()})}.exceptionOrNull() as WorkspaceSkillFailure
  assertEquals("skill_registry_unreadable",failed.code);assertEquals("unchanged",failed.bindingState)
  assertEquals("invalid registry",registry.readText())
 }
 @Test fun mountedSkillsRemainReferencesAndOwnerScopedWithoutDuplicatingFiles()=runBlocking {
  val s=store();val files=mutableMapOf(
   "/skills/日子/SKILL.md" to skill("日子").toByteArray(),
   "/skills/日子/references/guide.md" to "first revision".toByteArray(),
   "/skills/mail/SKILL.md" to skill("mail").toByteArray())
  val r=s.register("/skills",{listOf("日子","mail")},{files.getValue(it)})
  assertEquals(2,r.mi("registered"));assertEquals(3,files.size)
  assertEquals("first revision",s.read("日子","references/guide.md"){files.getValue(it)})
  files["/skills/日子/references/guide.md"]="changed at source".toByteArray()
  assertEquals("changed at source",s.read("日子","references/guide.md"){files.getValue(it)})
  assertEquals(1,s.register("/skills/日子/SKILL.md",{listOf("SKILL.md")},{files.getValue(it)}).mi("already_bound"))
  assertTrue(store(UUID.randomUUID().toString()).list().isEmpty())
  assertEquals(1,s.register("/workspace/日子",{listOf("SKILL.md")},{skill("日子").toByteArray()}).mi("skipped"))
  assertTrue(s.remove("日子").mb("removed"));assertTrue(files.containsKey("/skills/日子/SKILL.md"))
 }

}
