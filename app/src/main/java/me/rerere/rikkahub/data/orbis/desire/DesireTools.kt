package me.rerere.rikkahub.data.orbis.desire

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.activities.AndroidActivityStorage
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository

internal fun openDesires(context: Context, owner: String) = LocalDesireRepository(AndroidActivityStorage(context.filesDir,owner,"desires"))
internal fun localDesireTool(context: Context, owner: String, memory: OrbisMemoryRepository, enabled: () -> Boolean) = Tool(
    name="orbis_desire",
    description="当前助手的本机愿望，依附已存在的一条本机记忆，与ST分开。action=list/read/put/archive/restore/delete/plan_draft。先用本机记忆工具保存记忆，再用put绑定memory_id，text最多200字，新建expected_revision=0，修改/归档/删除需最新revision。归档仅改变愿望，不删除父记忆；delete清除当前愿望，历史仍可审计。list分页每20条。plan_draft只返回可用于日程/计划工具的文字，不代表计划已创建；确认实际创建后再归档。当前被系统召回的非置顶记忆可在原预算内附带愿望，同轮冻结，不额外改写稳定前缀。超过10条未完成愿望时，本机星图提示。人类星图只看数量，不显示愿望或记忆正文。",
    parameters={ InputSchema.Obj(properties=buildJsonObject {
        listOf("action","memory_id","text").forEach { put(it,buildJsonObject { put("type","string") }) }
        listOf("expected_revision","offset").forEach { put(it,buildJsonObject { put("type","integer") }) }
    },required=listOf("action")) },
    needsApproval={ it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("list","read","plan_draft") },
    hostApproval=HostToolApproval("orbis:desire","local-desire-v1","本机愿望"),isApprovalCurrent={enabled()},
    execute={ raw ->
        try {
            val out=withContext(Dispatchers.IO) {
                check(enabled()) { "desire_disabled" }
                val a=raw.jsonObject
                fun text(k:String)=a[k]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("desire_invalid_parameters")
                fun integer(k:String)=a[k]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("desire_invalid_parameters")
                val action=text("action")
                val keys=when(action) { "list"->setOf("action","offset");"read","plan_draft"->setOf("action","memory_id");"put"->setOf("action","memory_id","text","expected_revision");"archive","restore","delete"->setOf("action","memory_id","expected_revision");else->error("desire_invalid_parameters") }
                require(a.keys.all { it in keys }) { "desire_invalid_parameters" }
                val store=openDesires(context,owner); val state=store.snapshot()
                val ids=(state.entries.map { it.memoryId } + listOfNotNull(a["memory_id"]?.jsonPrimitive?.content)).distinct()
                val parents=memory.availableDesireParents(owner,ids)
                fun current(): LocalDesire { val id=text("memory_id");check(id in parents) { "desire_parent_unavailable" };return state.entries.firstOrNull { it.memoryId==id && it.state!="deleted" } ?: error("desire_not_found") }
                buildJsonObject {
                    put("ok",true)
                    when(action) {
                        "list"->{ val offset=if("offset" in a) integer("offset") else 0;require(offset in 0..500) { "desire_invalid_parameters" }
                            val list=state.entries.filter { it.state!="deleted" && it.memoryId in parents }.sortedByDescending { it.updatedAt }
                            put("entries",desireJson.encodeToJsonElement(list.drop(offset).take(20)));put("next_offset",if(offset+20<list.size) JsonPrimitive(offset+20) else JsonNull)
                            put("pending_count",pendingDesires(state.entries,parents)) }
                        "read"->put("entry",desireJson.encodeToJsonElement(current()))
                        "plan_draft"->{ val entry=current();put("draft_text",entry.text);put("plan_created",false);put("next_step","调用已启用的计划或日程工具，成功后再归档此愿望。") }
                        "put"->{val id=text("memory_id");check(id in parents) { "desire_parent_unavailable" };put("entry",desireJson.encodeToJsonElement(store.put(id,text("text"),integer("expected_revision"))))}
                        else -> put("entry",desireJson.encodeToJsonElement(store.transition(text("memory_id"),when(action){"archive"->"archived";"restore"->"active";else->"deleted"},integer("expected_revision"))))
                    }
                }
            };listOf(UIMessagePart.Text(out.toString()))
        } catch(e:CancellationException){throw e}
        catch(e:Exception){listOf(UIMessagePart.Text(buildJsonObject {put("ok",false);put("code",e.message?.takeIf{it.matches(Regex("desire_[a-z_]+"))}?:"desire_storage_unavailable");put("automatic_retry",false)}.toString()))}
    },
)
