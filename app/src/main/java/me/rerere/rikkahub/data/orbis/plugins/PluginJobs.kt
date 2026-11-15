package me.rerere.rikkahub.data.orbis.plugins

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable internal data class PluginJob(val id: String=UUID.randomUUID().toString(), val pluginId: String,
    val hash: String, val conversationId: String, val arguments: JsonObject, val nextAt: Long,
    val intervalMinutes: Long?=null, val enabled: Boolean=true, val status: String="scheduled", val lastAt: Long?=null)
@Serializable private data class PluginJobsState(val jobs: List<PluginJob> = emptyList())

/** Separate non-backed-up bindings. Intent is durable before WorkManager or script dispatch. */
internal class PluginJobStore(private val file: File) {
    private val lock=locks.getOrPut(file.absolutePath) { Any() }
    private fun load(): PluginJobsState {
        check(file.canonicalFile==file.absoluteFile) { "plugin_store_invalid" }
        if(!file.exists()) return PluginJobsState()
        check(file.length() in 1..2_000_000) { "plugin_store_invalid" }
        return pluginJson.decodeFromString<PluginJobsState>(file.readText()).also { state ->
            check(state.jobs.size<=120 && state.jobs.map {it.id}.distinct().size==state.jobs.size) { "plugin_store_invalid" }
        }
    }
    private fun save(jobs: List<PluginJob>) {
        val bytes=pluginJson.encodeToString(PluginJobsState(jobs)).toByteArray()
        check(bytes.size<=2_000_000) { "plugin_store_full" }
        check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "plugin_store_invalid" }
        val temp=File(file.parentFile,"${UUID.randomUUID()}.tmp")
        try { FileOutputStream(temp).use {it.write(bytes);it.fd.sync()}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temp.delete()}
    }
    fun list(): List<PluginJob> = synchronized(lock) {load().jobs}
    fun add(job: PluginJob, now: Long=System.currentTimeMillis()): PluginJob = synchronized(lock) {
        require(job.nextAt>=now && job.nextAt-now<=366L*86_400_000 && job.arguments.toString().toByteArray().size<=16*1024) { "plugin_host_arguments_invalid" }
        require(job.intervalMinutes==null || job.intervalMinutes in 15..43_200) { "plugin_job_interval_invalid" }
        val jobs=load().jobs
        check(jobs.size<120 && jobs.count {it.pluginId==job.pluginId}<10) { "plugin_job_limit" }
        check(jobs.none {it.id==job.id}) { "plugin_host_call_replayed" }
        save(jobs+job);job
    }
    fun pause(id: String) = synchronized(lock) {save(load().jobs.map {if(it.id==id) it.copy(enabled=false,status="paused") else it})}
    fun delete(id: String) = synchronized(lock) {save(load().jobs.filterNot {it.id==id})}
    fun claim(id: String, now: Long): PluginJob? = synchronized(lock) {
        val jobs=load().jobs;val job=jobs.firstOrNull {it.id==id && it.enabled} ?: return@synchronized null
        if(now<job.nextAt || job.status=="running" && job.intervalMinutes==null) return@synchronized null
        val next=job.copy(status="running",lastAt=now,nextAt=job.intervalMinutes?.let {now+it*60_000} ?: Long.MAX_VALUE)
        save(jobs.map {if(it.id==id) next else it});next
    }
    fun finish(id: String, at: Long, outcome: String) = synchronized(lock) {
        save(load().jobs.map { if(it.id==id && it.lastAt==at && it.enabled && it.status=="running")
            it.copy(status=outcome,enabled=it.intervalMinutes!=null) else it })
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}
internal fun pluginJobs(context: Context, owner: String): PluginJobStore {
    require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    return PluginJobStore(File(context.noBackupFilesDir.canonicalFile,"personal-plugin-jobs/$owner/jobs.json"))
}
internal object PluginJobScheduler {
    private fun name(owner: String,id: String)="orbis-plugin-$owner-$id"
    fun enqueue(context: Context,owner: String,job: PluginJob) {
        val data=workDataOf("owner" to owner,"job_id" to job.id)
        val delay=(job.nextAt-System.currentTimeMillis()).coerceAtLeast(0)
        val manager=WorkManager.getInstance(context)
        val operation=if(job.intervalMinutes==null) manager.enqueueUniqueWork(name(owner,job.id),ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PluginJobWorker>().setInputData(data).setInitialDelay(delay,TimeUnit.MILLISECONDS).build())
        else manager.enqueueUniquePeriodicWork(name(owner,job.id),ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PluginJobWorker>(job.intervalMinutes,TimeUnit.MINUTES)
                .setInputData(data).setInitialDelay(delay,TimeUnit.MILLISECONDS).build())
        operation.result.get(2,TimeUnit.SECONDS)
    }
    fun cancel(context: Context,owner: String,id: String) {WorkManager.getInstance(context).cancelUniqueWork(name(owner,id))}
}
internal suspend fun pluginJobRequest(context: Context, owner: String, conversation: String, pluginId: String,
    hash: String, action: String, args: JsonObject): JsonObject {
    val store=pluginJobs(context,owner)
    fun owned(id: String)=store.list().firstOrNull {it.id==id && it.pluginId==pluginId && it.conversationId==conversation}
        ?: error("plugin_host_scope_denied")
    return when(action) {
        "list"->buildJsonObject {put("jobs",pluginJson.encodeToJsonElement(store.list().filter {it.pluginId==pluginId && it.conversationId==conversation}.map {it.copy(arguments=JsonObject(emptyMap()))}))}
        "add"->{
            check(args.keys.all {it in setOf("due_at_ms","interval_minutes","arguments")}) {"plugin_host_arguments_invalid"}
            val now=System.currentTimeMillis()
            val interval=args["interval_minutes"]?.jsonPrimitive?.longOrNull
            check("interval_minutes" !in args || interval!=null) {"plugin_host_arguments_invalid"}
            val due=args["due_at_ms"]?.jsonPrimitive?.longOrNull
            check("due_at_ms" !in args || due!=null) {"plugin_host_arguments_invalid"}
            check(due!=null || interval!=null) {"plugin_host_arguments_invalid"}
            check("arguments" !in args || args["arguments"] is JsonObject) {"plugin_host_arguments_invalid"}
            val inputs=args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            val entry=openPlugins(context,owner).snapshot().entries.first {it.id==pluginId}
            PluginSchema.validateArguments(entry.active()!!.body.parameters,inputs)
            val job=store.add(PluginJob(pluginId=pluginId,hash=hash,conversationId=conversation,arguments=inputs,
                nextAt=due ?: (now+interval!!*60_000),intervalMinutes=interval),now)
            val requested=runCatching {PluginJobScheduler.enqueue(context,owner,job)}.isSuccess
            if(!requested) {store.pause(job.id);runCatching {PluginJobScheduler.cancel(context,owner,job.id)}}
            buildJsonObject {put("saved",true);put("job_id",job.id);put("scheduler_requested",requested)
                put("note",if(requested) "已保存后台任务并收到调度入队回执；Android可能推迟执行，不保证准点。"
                    else "未确认调度结果，已暂停此任务。先查list和使用记录，不重复创建或声称已运行。")}
        }
        "cancel"->{ val id=args["job_id"]?.jsonPrimitive?.content ?: error("plugin_host_arguments_invalid");owned(id)
            store.pause(id);runCatching {PluginJobScheduler.cancel(context,owner,id)}
            store.delete(id);buildJsonObject {put("cancelled",true);put("job_id",id)} }
        else->error("plugin_capability_unknown")
    }
}

class PluginJobWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val owner=inputData.getString("owner") ?: return@withContext Result.failure()
        val id=inputData.getString("job_id") ?: return@withContext Result.failure()
        val store=pluginJobs(applicationContext,owner)
        val job=store.claim(id,System.currentTimeMillis()) ?: run {
            if(store.list().none {it.id==id && it.enabled}) runCatching {PluginJobScheduler.cancel(applicationContext,owner,id)}
            return@withContext Result.success()
        }
        try {
            val koin=GlobalContext.get()
            val host=PluginHost(applicationContext,owner,koin.get<SettingsStore>(),koin.get<WorkspaceRepository>(),job.conversationId)
            val conversation=koin.get<ConversationRepository>().getConversationById(Uuid.parse(job.conversationId))
            if(conversation?.assistantId?.toString()!=owner || !host.runnable(job.pluginId,job.hash) || !host.hasPermission(job.pluginId,job.hash,"background.run")) {
                store.pause(id);runCatching {PluginJobScheduler.cancel(applicationContext,owner,id)};return@withContext Result.success()
            }
            val result=host.execute(job.pluginId,job.hash,job.arguments,jobGuard={
                check(store.list().any {it.id==id && it.enabled && it.status=="running" && it.lastAt==job.lastAt}) {"plugin_job_cancelled"}
                check(host.hasPermission(job.pluginId,job.hash,"background.run")) {"plugin_permission_denied"}
            })
            store.finish(id,job.lastAt!!,if(result["ok"]?.jsonPrimitive?.booleanOrNull==true) "completed" else "failed")
        } catch(cancelled: CancellationException) { store.finish(id,job.lastAt!!,"unknown");throw cancelled }
        catch(_: Exception) {store.finish(id,job.lastAt!!,"unknown")}
        // Never ask WorkManager to retry an uncertain execution. A later recurrence is a new run.
        Result.success()
    }
}
