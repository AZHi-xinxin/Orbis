package me.rerere.rikkahub.data.orbis.plugins

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.lover.connect.CompanionNativeTools
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisSentinelTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository
import me.rerere.rikkahub.data.orbis.schedule.*
import me.rerere.rikkahub.data.orbis.sentinel.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

internal fun pluginNativePermission(method: String): String? = when(method) {
    "schedule.list", "schedule.read" -> "schedule.read"
    "schedule.add", "schedule.update", "schedule.delete" -> "schedule.write"
    "alarm.set", "alarm.cancel", "alarm.list" -> "alarm"
    "notify.send" -> "notify"
    "wake" -> "wake"
    "sentinel.create", "sentinel.pause", "sentinel.resume", "sentinel.read", "sentinel.delete" -> "sentinel"
    "memory.save" -> "memory.write"
    "memory.search" -> "memory.read"
    "system.status" -> "device.status"
    "system.steps" -> "device.steps"
    "system.weather" -> "device.weather"
    "system.foreground" -> "device.foreground"
    "jobs.add", "jobs.list", "jobs.cancel" -> "background.run"
    else -> null
}

/** Only explicit capabilities, never a generic tool name/owner supplied by JavaScript. */
internal class PluginNativeBridge(private val context: Context, private val owner: String,
    private val conversationId: String?, private val id: String, private val hash: String,
    private val operationId: String, private val checkPermission: (String) -> Unit) {
    private suspend fun conversation(): String {
        val target=conversationId ?: error("plugin_conversation_required")
        val repo=GlobalContext.get().get<ConversationRepository>()
        check(repo.getConversationById(Uuid.parse(target))?.assistantId?.toString()==owner) { "plugin_conversation_required" }
        return target
    }
    suspend fun execute(method: String, args: JsonObject): JsonElement {
        val permission=pluginNativePermission(method) ?: error("plugin_capability_unknown")
        checkPermission(permission)
        fun text(key: String)=args[key]?.jsonPrimitive?.contentOrNull ?: error("plugin_host_arguments_invalid")
        if(method.startsWith("jobs.")) return pluginJobRequest(context,owner,conversation(),id,hash,method.substringAfter('.'),args)
        if(method=="system.foreground") return Json.parseToJsonElement(com.lover.connect.DeviceContextSnapshot.pluginForeground(context).toString())
        if(method.startsWith("schedule.")) {
            val action=method.substringAfter('.').let { if(it=="add") "create" else it }
            val request=parseOrbisScheduleRequest(action,args)
            checkPermission(permission)
            return executeOrbisScheduleRequest(OrbisScheduleStore.open(context),request)
        }
        if(method.startsWith("memory.")) {
            conversation() // binds this write/read to a live assistant conversation
            check(args.keys.all { it in if(method=="memory.save") setOf("body","summary","tags","keywords","important","state") else setOf("query","limit","offset") }) { "plugin_host_arguments_invalid" }
            checkPermission(permission)
            return GlobalContext.get().get<OrbisMemoryRepository>().execute(owner,
                JsonObject(args+ ("action" to JsonPrimitive(if(method=="memory.save") "store" else "read"))), operationId)
        }
        if(method=="wake" || method.startsWith("sentinel.")) {
            val target=conversation()
            val controller=OrbisSentinels.open(context)
            val origin=OrbisSentinelPlugin(id,hash,permission)
            val action=if(method=="wake") "create" else method.substringAfter('.')
            val input=if(method=="wake") {
                check(args.keys.all { it in setOf("reason","due_at_ms") }) { "plugin_host_arguments_invalid" }
                buildJsonObject {
                    put("type","once"); put("prompt",text("reason"))
                    put("due_at_ms",args["due_at_ms"] ?: JsonPrimitive(System.currentTimeMillis()+2_000))
                }
            } else args
            if(action!="create") {
                val rule=controller.rules.get(text("rule_id")) ?: error("plugin_host_scope_denied")
                check(rule.assistantId==owner && rule.conversationId==target && rule.plugin?.id==id && rule.plugin.hash==hash) { "plugin_host_scope_denied" }
            } else {
                // Screen capture, location and app monitoring need separate human workflows.
                // This version exposes only time, inactivity and basic phone facts.
                val type=input["type"]?.jsonPrimitive?.content
                check(type in setOf("once","ritual","agreement","low_battery","screen_on","night_usage","geofence")) { "plugin_sentinel_type_unsupported" }
                if(type in setOf("low_battery","screen_on","night_usage","geofence")) checkPermission("device.observe")
                check(input["action"]?.jsonPrimitive?.contentOrNull.let { it==null || it=="wake" }) { "plugin_host_arguments_invalid" }
            }
            checkPermission(permission)
            val tool=createOrbisSentinelTools(controller,owner,target,origin).first { it.name=="orbis_sentinel_$action" }
            return Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text)
        }
        val name=when(method) {
            "alarm.set"->"set_alarm"; "alarm.cancel"->"cancel_alarm"; "alarm.list"->"get_alarms"
            "notify.send"->"send_notification"; "system.steps"->"get_steps"; "system.weather"->"get_weather"
            "system.status"->"get_battery"; else->error("plugin_capability_unknown")
        }
        val native=CompanionNativeTools(context)
        val revision=native.authorizationRevision(name)
        checkPermission(permission)
        val result=Json.parseToJsonElement(native.execute(name,args.toString(),revision).toJson()).jsonObject
        if(method!="system.status") return result
        return buildJsonObject {
            put("battery",result)
            put("screen_on",context.getSystemService(PowerManager::class.java).isInteractive)
            put("locked",context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            put("foreground_api","orbis.system.foreground()"); put("fall_event_supported",false)
        }
    }
}

/** Called at admission AND immediately before delivery; a restored backup has no grants. */
internal fun pluginSentinelAllowed(context: Context, rule: OrbisSentinelRule): Boolean {
    val origin=rule.plugin ?: return true
    return runCatching {
        val koin=GlobalContext.get()
        val host=PluginHost(context,rule.assistantId,koin.get<SettingsStore>(),koin.get<WorkspaceRepository>())
        host.runnable(rule.plugin.id,origin.hash) && host.hasPermission(origin.id,origin.hash,origin.permission) &&
            (rule.type !in setOf(OrbisSentinelType.LOW_BATTERY,OrbisSentinelType.SCREEN_ON,OrbisSentinelType.NIGHT_USAGE,OrbisSentinelType.GEOFENCE) ||
                host.hasPermission(origin.id,origin.hash,"device.observe"))
    }.getOrDefault(false)
}

internal suspend fun pausePluginSentinels(context: Context, owner: String, id: String) {
    val jobs=pluginJobs(context,owner)
    jobs.list().filter {it.pluginId==id && it.enabled}.forEach {job ->
        jobs.pause(job.id);runCatching {PluginJobScheduler.cancel(context,owner,job.id)}
    }
    val runtime=OrbisSentinels.open(context)
    val rules=runtime.rules.refresh().rules.filter { it.assistantId==owner && it.plugin?.id==id }
    if(rules.isEmpty()) return
    rules.forEach { rule ->
        runtime.withRuleEdit(rule.id,rule.binding) { runtime.rules.pause(rule.id,rule.binding,System.currentTimeMillis()) }
    }
    runtime.reschedule()
}
