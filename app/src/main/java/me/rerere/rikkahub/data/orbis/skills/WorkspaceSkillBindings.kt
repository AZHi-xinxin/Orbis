package me.rerere.rikkahub.data.orbis.skills

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.orbis.market.marketAtomic
import me.rerere.rikkahub.data.orbis.market.ms

internal class WorkspaceSkillFailure(
    val code: String,
    val hint: String,
    val bindingState: String = "unchanged",
    cause: Throwable? = null,
) : IllegalStateException(hint, cause)

/** References remain in the workspace or host's mounted skill library. Never copies or executes scripts. */
internal class WorkspaceSkillBindings(root:File,owner:String,private val workspace:String,private val persist:(File,ByteArray)->Unit=::marketAtomic,private val stillAllowed:()->Boolean={true}) {
    private val file=File(root,"${UUID.fromString(owner)}/${UUID.fromString(workspace)}.json")
    companion object { private val lock=Mutex() }
    fun list():List<JsonObject> = try {
        if(!file.exists()) emptyList() else Json.parseToJsonElement(file.readText()).jsonArray.map {it.jsonObject}
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) {
        throw WorkspaceSkillFailure("skill_registry_unreadable", "暂时无法读取技能绑定清单，未覆盖原清单；请稍后list核对。", cause=e)
    }
    private fun save(rows:List<JsonObject>) {
        if(!stillAllowed()) throw WorkspaceSkillFailure("skill_permission_changed", "技能绑定权限或工作区已变化，请人类核对当前AI的授权与工作区。")
        try {
            persist(file,JsonArray(rows).toString().toByteArray())
            check(list()==rows) {"skill_binding_verification_failed"}
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            // Atomic persistence can commit before its receipt/readback fails. Never invite replay.
            throw WorkspaceSkillFailure("skill_binding_write_unknown", "绑定保存结果未能核对，请先list查看实际清单，不要直接重复登记或解绑。", "unknown", e)
        }
    }
    suspend fun remove(name:String):JsonObject=lock.withLock {
        val before=list();val after=before.filter {it.ms("name")!=name};save(after)
        buildJsonObject {put("removed",after.size!=before.size);put("name",name);put("source_files_unchanged",true);put("effective","next_user_turn")}
    }
    private fun directory(value:String):String {
        val p=value.removeSuffix("/SKILL.md").trimEnd('/')
        require(listOf("/workspace","/skills").any {p==it || p.startsWith("$it/")} && p.length<=1024 && p.split('/').drop(1).all {it.isNotEmpty() && it!="." && it!=".." && !it.contains('\\') && it.none(Char::isISOControl)}) {"只可登记当前工作区 /workspace/ 或宿主技能库 /skills/ 内的技能目录"}
        return p
    }
    suspend fun register(path:String,children:suspend (String)->List<String>,read:suspend (String)->ByteArray):JsonObject=lock.withLock {
        val dir=directory(path)
        val names=try { children(dir) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            val (code,hint)=when {
                e.message=="Skill library is not mounted" -> "skill_library_not_mounted" to "当前工作区没有挂载宿主技能库 /skills；未扫描空的占位目录。请人类核对技能库挂载后再登记。"
                e.message?.startsWith("Path does not exist:")==true -> "skill_directory_missing" to "指定目录不存在，请先用工作区文件工具确认实际路径；不要重复登记不存在的目录。"
                e.message?.startsWith("Path is not a directory:")==true -> "skill_path_not_directory" to "指定位置不是技能目录；请给包含SKILL.md的目录，或该SKILL.md文件路径。"
                e.message?.contains("escapes workspace")==true -> "skill_path_denied" to "路径越出了当前工作区，不能登记或读取该位置。"
                else -> "skill_directory_unavailable" to "无法列出指定技能目录，请核对当前工作区、目录是否存在及可读权限。"
            }
            throw WorkspaceSkillFailure(code,hint,cause=e)
        }
        val candidates=if("SKILL.md" in names) listOf(dir) else names.mapNotNull {name->
            if(name.contains('/')||name.startsWith('.')||name.contains('\\')) null else "$dir/$name"
        }.take(33)
        require(candidates.size<=32){"一次最多登记32个技能目录，请分批"}
        val rows=list().associateBy {it.ms("name")}.toMutableMap();val receipts=mutableListOf<JsonObject>()
        for(source in candidates) {
            try {
                val bytes=read("$source/SKILL.md");require(bytes.size<=128*1024){"SKILL.md超过128KiB"}
                val metadata=SkillFrontmatterParser.parse(bytes.toString(Charsets.UTF_8))
                val name=metadata["name"].orEmpty();val description=metadata["description"].orEmpty()
                require(name.length in 1..100 && name !in setOf(".","..") && name.none {it.isWhitespace()||it.isISOControl()||it in "/\\"} && description.isNotBlank() && description.length<=2000){"缺少有效name/description"}
                val prior=rows[name]
                if(prior!=null && prior.ms("path")!=source) {
                    receipts+=buildJsonObject {
                        put("name",name);put("path",source);put("state","skipped")
                        put("reason_code","skill_name_conflict");put("bound_path",prior.ms("path"))
                        put("reason","同名技能已绑定其他目录，原绑定仍可使用；如需切换，请先unbind此name，再register新路径。")
                    }
                    continue
                }
                val item=buildJsonObject {put("name",name);put("description",description);put("path",source);put("workspace",workspace)}
                rows[name]=item;receipts+=buildJsonObject {put("name",name);put("path",source);put("state",if(prior==item) "already_bound" else "bound")}
            } catch(e:CancellationException){throw e} catch(e:Exception) {
                receipts+=buildJsonObject {put("path",source);put("state","skipped");put("reason",when(e){is IllegalArgumentException->e.message?.take(200)?:"格式无效";else->"没有可读取的SKILL.md"})}
            }
        }
        require(rows.size<=64){"当前AI最多绑定64个工作区技能"}
        if(receipts.any {it.ms("state")=="bound"}) save(rows.values.toList())
        val bound=receipts.count {it.ms("state")=="bound"};val existing=receipts.count {it.ms("state")=="already_bound"}
        val skipped=receipts.count {it.ms("state")=="skipped"};val conflicts=receipts.count {it.ms("reason_code")=="skill_name_conflict"}
        buildJsonObject {
            put("registered",bound);put("already_bound",existing);put("skipped",skipped);put("conflicts",conflicts)
            put("status",when {receipts.isEmpty()->"empty";skipped==0->"success";bound+existing>0->"partial";conflicts==skipped->"conflict";else->"no_skills_registered"})
            put("path",dir);put("directory_entries",names.size);put("candidates_scanned",candidates.size)
            if(receipts.isEmpty()) {
                put("reason_code",if(names.isEmpty()) "skill_directory_empty" else "skill_no_candidates")
                put("message",if(names.isEmpty()) "目录已读取，但当前挂载位置没有条目；未新增绑定。若预期有技能，请核对宿主技能库与当前工作区挂载。" else "目录已读取，但没有可登记的技能候选；请给包含SKILL.md的目录。")
            }
            put("name_policy","one_name_one_directory_per_ai_workspace")
            put("conflict_help","同一AI的同一工作区内，一个技能名只绑定一个目录；冲突不会替换原绑定。切换需先unbind，再register，无需复制技能文件。")
            put("items",JsonArray(receipts));put("effective","next_user_turn");put("source_files_unchanged",true)
            put("read_help","下一次新消息只提供名称和说明；按需调用orbis_skill_register(operation=read,name=技能名)。参考文件仍在原目录。")
        }
    }
    suspend fun read(name:String,path:String,read:suspend (String)->ByteArray):String {
        val item=list().singleOrNull {it.ms("name")==name}?:error("此技能未绑定给当前AI，请先list核对")
        val relative=path.ifBlank {"SKILL.md"}
        require(!relative.startsWith('/') && relative.split('/').none {it==".."||it=="."||it.isBlank()||it.contains('\\')||it.any(Char::isISOControl)}) {"参考路径必须在这个技能目录内"}
        val data=read(directory(item.ms("path"))+"/"+relative);require(data.size<=128*1024){"文件较大，请用workspace_read_file分块读取"}
        return if(relative=="SKILL.md") SkillFrontmatterParser.extractBody(data.toString(Charsets.UTF_8)) else data.toString(Charsets.UTF_8)
    }
}
