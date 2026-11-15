package me.rerere.rikkahub.data.orbis.plugins

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.context.GlobalContext

/** Frozen list for this turn. Approval/revocation is still checked at every execution. */
internal class PluginResponseChecks(private val host: PluginHost, private val checks: List<Pair<String,String>>) {
    suspend fun apply(message: UIMessage): UIMessage {
        var text=message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        if(text.isBlank()) return message
        val warnings=mutableListOf<String>()
        for((id,hash) in checks) {
            try {
                val result=host.execute(id,hash,buildJsonObject { put("event","before_response"); put("text",text) },beforeResponse=true)
                val verdict=pluginCheckVerdict(result,text)
                if(verdict.passed) text=verdict.text else warnings+=verdict.feedback
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { warnings+="插件检查未完成，请查看插件使用记录。" }
        }
        if(warnings.isEmpty() && text==message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }) return message
        val presented=if(warnings.isEmpty()) text else "【待修改草稿 · 交稿前检查未通过】\n"+warnings.joinToString("\n")+"\n\n"+text
        // Keep reasoning, images and completed tools; replace only the visible text.
        val first=message.parts.indexOfFirst { it is UIMessagePart.Text }
        return message.copy(parts=message.parts.mapIndexedNotNull { index,part ->
            if(part is UIMessagePart.Text) if(index==first) part.copy(text=presented) else null else part
        })
    }
    companion object {
        suspend fun open(context: Context, owner: String, conversation: String): PluginResponseChecks? = withContext(Dispatchers.IO) {
            val koin=GlobalContext.getOrNull() ?: return@withContext null
            val settings=koin.getOrNull<SettingsStore>() ?: return@withContext null
            val workspaces=koin.getOrNull<WorkspaceRepository>() ?: return@withContext null
            val host=PluginHost(context,owner,settings,workspaces,conversation)
            if(!host.enabled()) return@withContext null
            val entries=host.repository.snapshot().entries.filter { it.active()?.body?.beforeResponse==true &&
                host.runnable(it.id,it.active()!!.hash) && host.hasPermission(it.id,it.active()!!.hash,"response.check") }
            check(entries.size<=4) { "plugin_response_check_limit" }
            entries.takeIf { it.isNotEmpty() }?.let { PluginResponseChecks(host,it.map { e->e.id to e.active()!!.hash }) }
        }
    }
}
internal data class PluginCheckVerdict(val passed: Boolean, val text: String, val feedback: String)
internal fun pluginCheckVerdict(result: JsonObject, original: String): PluginCheckVerdict {
    val value=result["result"] as? JsonObject
    val passed=result["ok"]?.jsonPrimitive?.booleanOrNull==true && value?.get("pass")?.jsonPrimitive?.booleanOrNull==true
    val replacement=value?.get("text")?.jsonPrimitive?.contentOrNull
    val validText=replacement==null || replacement.isNotBlank() && replacement.toByteArray().size<=PLUGIN_OUTPUT_BYTES
    return if(passed && validText) PluginCheckVerdict(true,replacement ?: original,"") else
        PluginCheckVerdict(false,original,value?.get("feedback")?.jsonPrimitive?.contentOrNull?.take(600)
            ?.takeIf { it.isNotBlank() } ?: "插件未返回通过回执，请查看使用记录并修改草稿。")
}
