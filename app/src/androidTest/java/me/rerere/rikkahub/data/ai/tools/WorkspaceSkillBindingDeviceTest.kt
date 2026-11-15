package me.rerere.rikkahub.data.ai.tools
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
class WorkspaceSkillBindingDeviceTest {
 @Test fun actualToolRegistersOwnWorkspaceKeepsCurrentCatalogueAndLoadsReferences()=runBlocking {
  val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner);val ctx=inst.targetContext
  val root=Files.createTempDirectory(ctx.cacheDir.toPath(),"skills-native-").toFile();val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
  val owner=UUID.randomUUID().toString();val workspace=UUID.randomUUID().toString();val bindings=File(ctx.filesDir,"orbis-workspace-skills/$owner")
  val diagnostics=File(ctx.noBackupFilesDir,"orbis-self-check/$owner.json")
  try {
   val mounted=File(root,"mounted-skills").apply {mkdirs()};val manager=WorkspaceManager(File(root,"workspaces"),bindMounts=listOf(me.rerere.workspace.WorkspaceBindMount(mounted,"/skills")));val repo=WorkspaceRepository(db.workspaceDao(),manager,RootfsInstaller(manager),SettingsStore(ctx,AppScope().also {it.cancel()}))
   repo.createNative(workspace,"synthetic skill");repo.writeNativeText(workspace,"/workspace/skills/demo/SKILL.md","---\nname: sample\ndescription: Synthetic test\n---\nOnly a synthetic document.",false)
   repo.writeNativeText(workspace,"/workspace/skills/demo/references/guide.md","synthetic reference",false)
   var allowed=true;val tool=workspaceSkillTool(ctx,owner,workspace,repo){allowed};val before=tool.parameters().toString();val prompt=tool.systemPrompt(Model(),emptyList())
   val result=Json.parseToJsonElement((tool.execute(buildJsonObject{put("operation","register");put("path","/workspace/skills/demo")}).single() as UIMessagePart.Text).text).jsonObject
   assertEquals(1,result.mi("registered"));assertEquals(before,tool.parameters().toString());assertEquals(prompt,tool.systemPrompt(Model(),emptyList()));assertTrue(tool.isApprovalCurrent())
   val next=workspaceSkillTool(ctx,owner,workspace,repo){allowed};assertTrue(next.systemPrompt(Model(),emptyList()).contains("sample"));assertFalse(next.systemPrompt(Model(),emptyList()).contains("Only a synthetic document"))
   val reference=next.execute(buildJsonObject{put("operation","read");put("name","sample");put("path","references/guide.md")});assertEquals("synthetic reference",(reference.single() as UIMessagePart.Text).text)
   assertTrue(workspaceSkillTool(ctx,UUID.randomUUID().toString(),workspace,repo){true}.systemPrompt(Model(),emptyList()).isEmpty())
   // Native file access rejects symlink traversal even if a directory scan sees it.
   val outside=File(root,"outside").apply{mkdirs()};File(outside,"SKILL.md").writeText("---\nname: outside\ndescription: secret\n---\nno")
   android.system.Os.symlink(outside.path,File(manager.filesDir(workspace),"escape").path)
   suspend fun result(args:JsonObject)=Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
   val denied=result(buildJsonObject{put("operation","register");put("path","/workspace/escape")})
   assertEquals("failed",denied.ms("status"));assertEquals("unchanged",denied.ms("binding_state"))
   assertEquals("skill_path_denied",denied.ms("reason_code"))
   me.rerere.rikkahub.data.orbis.diagnostics.DiagnosticJournal.recordSkillFailure(ctx,owner,denied.ms("reason_code"),"orbis_skill_register","register",denied.ms("diagnostic_id"))
   val diagnostic=me.rerere.rikkahub.data.orbis.diagnostics.DiagnosticJournal.read(ctx,owner).single()
   assertEquals(denied.ms("diagnostic_id"),diagnostic.reference);assertEquals("tool",diagnostic.source)
   assertEquals("register",diagnostic.operation);assertNull(diagnostic.httpStatus)
   // These previously escaped Tool.execute and interrupted the entire model/tool loop.
   for(path in listOf("/workspace/data", "/root/skills", "/workspace/../secret")) {
    val failure=result(buildJsonObject{put("operation","register");put("path",path)})
    assertEquals("failed",failure.ms("status"));assertEquals("unchanged",failure.ms("binding_state"))
    if(path=="/workspace/data") assertEquals("skill_directory_missing",failure.ms("reason_code"))
   }
   assertEquals("failed",result(buildJsonObject{put("action","register");put("path","/workspace/skills/demo")}).ms("status"))
   assertEquals("failed",result(buildJsonObject{put("operation","read");put("name","not-bound")}).ms("status"))
   val remaining=Json.parseToJsonElement((tool.execute(buildJsonObject{put("operation","list")}).single() as UIMessagePart.Text).text).jsonObject
   assertEquals(listOf("sample"),remaining["items"]!!.jsonArray.map {it.jsonObject.ms("name")})
   // Exact reported request also succeeds once that directory really contains a valid skill.
   repo.writeNativeText(workspace,"/workspace/data/SKILL.md","---\nname: data-sample\ndescription: Reported path synthetic skill\n---\nData body.",false)
   assertEquals(1,result(buildJsonObject{put("operation","register");put("path","/workspace/data")}).mi("registered"))
   assertEquals(1,result(buildJsonObject{put("operation","register");put("path","/workspace/data")}).mi("already_bound"))
   // Root-level SKILL.md must work too; listing /workspace itself previously failed.
   repo.writeNativeText(workspace,"/workspace/SKILL.md","---\nname: root-sample\ndescription: Root directory synthetic skill\n---\nRoot body.",false)
   assertEquals(1,result(buildJsonObject{put("operation","register");put("path","/workspace/")}).mi("registered"))
   assertEquals(before,tool.parameters().toString());assertEquals(prompt,tool.systemPrompt(Model(),emptyList()))
   // Host /skills resolves to the existing library, not an empty rootfs mount point.
   val mountedSkill=File(mounted,"日子").apply{mkdirs()}
   File(mountedSkill,"SKILL.md").writeText("---\nname: 日子\ndescription: Mounted synthetic skill\n---\nOriginal mounted body.")
   File(mountedSkill,"reference.md").writeText("first")
   assertEquals(1,result(buildJsonObject{put("operation","register");put("path","/skills/日子")}).mi("registered"))
   assertEquals(1,result(buildJsonObject{put("operation","register");put("path","/skills")}).mi("already_bound"))
   suspend fun mountedRead()=(tool.execute(buildJsonObject{put("operation","read");put("name","日子");put("path","reference.md")}).single() as UIMessagePart.Text).text
   assertEquals("first",mountedRead());File(mountedSkill,"reference.md").writeText("updated at source");assertEquals("updated at source",mountedRead())
   assertFalse(File(manager.filesDir(workspace),"日子").exists())
   android.system.Os.symlink(outside.path,File(mounted,"escape").path)
   assertEquals("skill_path_denied",result(buildJsonObject{put("operation","register");put("path","/skills/escape")}).ms("reason_code"))
   android.system.Os.symlink(File(outside,"SKILL.md").path,File(mountedSkill,"secret.md").path)
   assertEquals("failed",result(buildJsonObject{put("operation","read");put("name","日子");put("path","secret.md")}).ms("status"))
   assertTrue(result(buildJsonObject{put("operation","unbind");put("name","日子")}).mb("removed"))
   assertTrue(File(mountedSkill,"SKILL.md").isFile);assertEquals("updated at source",File(mountedSkill,"reference.md").readText())
   allowed=false;assertFalse(tool.isApprovalCurrent())
   assertEquals("skill_permission_changed",result(buildJsonObject{put("operation","list")}).ms("reason_code"))
  }finally{db.close();root.deleteRecursively();bindings.deleteRecursively();diagnostics.delete()}
 }
}
