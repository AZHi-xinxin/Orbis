package me.rerere.rikkahub.data.orbis.health

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.time.*
import java.util.concurrent.TimeUnit

@Serializable internal data class HealthSyncSettings(val enabled:Boolean=false,val serverId:String="",val binding:String="",
    val generation:String="",val completedSlot:String="",val lastAttempt:Long=0,val lastSuccess:Long=0,val status:String="尚未同步")
internal fun healthSyncGrantCurrent(live:HealthSyncSettings,grant:HealthSyncSettings)=live.enabled&&
    live.serverId==grant.serverId&&live.binding==grant.binding&&live.generation==grant.generation
internal fun healthSyncBinding(server:McpServerConfig):String {
    // Bind the human's choice to the destination and static auth. OAuth token refresh is allowed.
    val value=server.serverUrl+"\n"+server.commonOptions.headers.toString()+"\n"+server.commonOptions.oauth?.clientId.orEmpty()
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}
internal fun supportsHealthSync(server:McpServerConfig)=server.commonOptions.enable &&
    server.commonOptions.tools.any{it.name=="read_health_data" && it.enable && !it.needsApproval}

internal object AndroidHealthSync {
    private const val WORK="orbis-health-cloud-sync-v1"
    private val mutex=Mutex()
    private fun file(context:Context)=AtomicFile(File(context.noBackupFilesDir,"orbis-health/sync-v1.json"))
    @Synchronized fun read(context:Context):HealthSyncSettings {
        val f=file(context)
        return if(!f.baseFile.exists()&&!File(f.baseFile.path+".bak").exists())HealthSyncSettings()
        else f.openRead().use{healthJson.decodeFromString(it.readBytes().toString(Charsets.UTF_8))}
    }
    @Synchronized private fun save(context:Context,value:HealthSyncSettings){
        val f=file(context);f.baseFile.parentFile?.mkdirs();val out=f.startWrite()
        try{out.write(healthJson.encodeToString(value).toByteArray());out.fd.sync();f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
        healthChanges.value++
    }
    @Synchronized fun configure(context:Context,server:McpServerConfig?,enabled:Boolean) {
        require(!enabled || server!=null&&supportsHealthSync(server)){"health_sync_server_unavailable"}
        val old=read(context)
        save(context,if(!enabled)old.copy(enabled=false,status="自动同步已关闭") else HealthSyncSettings(true,server!!.id.toString(),healthSyncBinding(server),generation=java.util.UUID.randomUUID().toString(),status="来源已选择 · 等待首次同步"))
        schedule(context)
    }
    fun schedule(context:Context,nextOnly:Boolean=false){
        val state=read(context);val manager=WorkManager.getInstance(context)
        if(!state.enabled){manager.cancelAllWorkByTag(WORK);return}
        val now=ZonedDateTime.now()
        val target=if(!nextOnly&&state.completedSlot!=healthSyncSlot(now).key)now else nextHealthSyncTime(now,state.completedSlot)
        val delay=Duration.between(now,target).toMillis().coerceAtLeast(0)
        val request=OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInputData(workDataOf("generation" to state.generation))
            .addTag(WORK).setInitialDelay(delay,TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // Distinct durable slots avoid cancelling the executing worker while scheduling its
        // successor. No 15-minute idle polling; normal operation wakes at most twice per day.
        manager.enqueueUniqueWork("$WORK/${state.generation}/${healthSyncSlot(target).key}",ExistingWorkPolicy.KEEP,request)
    }
    /** Native-only call: no model, no AI tool to change this opt-in, no upload of local records. */
    suspend fun sync(context:Context,manual:Boolean=false):Boolean=mutex.withLock {
        val cfg=read(context);if(!cfg.enabled)return@withLock false
        val now=ZonedDateTime.now();val slot=healthSyncSlot(now)
        if(!manual && (cfg.completedSlot==slot.key || System.currentTimeMillis()-cfg.lastAttempt<10*60_000))return@withLock true
        val admitted=synchronized(this) {
            if(!healthSyncGrantCurrent(read(context),cfg))false else {
                save(context,cfg.copy(lastAttempt=System.currentTimeMillis(),status="正在读取设备记录…"));true
            }
        }
        if(!admitted)return@withLock false
        try{
            val koin=GlobalContext.get()
            val store=koin.get<SettingsStore>()
            withTimeout(10_000){store.settingsFlow.first{!it.init}}
            fun selected():McpServerConfig {
                check(healthSyncGrantCurrent(read(context),cfg)){"health_sync_disabled"}
                return store.settingsFlow.value.mcpServers.singleOrNull{it.id.toString()==cfg.serverId}
                ?.takeIf{supportsHealthSync(it)&&healthSyncBinding(it)==cfg.binding}?:error("health_sync_binding_changed")
            }
            val server=selected();val mcp=koin.get<McpManager>()
            val parts=withTimeout(45_000){
                if(mcp.getClient(server)==null)mcp.addClient(server)
                mcp.getStatus(server).first{it is McpStatus.Connected || it is McpStatus.Error || it is McpStatus.NeedsAuthorization}
                mcp.callTool(server.id,"read_health_data",buildJsonObject{put("data_type","")})
            }
            val texts=parts.filterIsInstance<UIMessagePart.Text>();require(parts.size==texts.size&&texts.size==1){"health_cloud_format"}
            val through=if(manual)now.toLocalDate() else slot.through
            val days=parseHealthCloudData(texts.single().text,now.zone,through.minusDays(6),through)
            selected() // The destination/permission must still match before any write.
            val live=read(context);check(healthSyncGrantCurrent(live,cfg))
            val repo=openHealth(context)
            var changed=0;var protected=0
            for((date,values) in days.toSortedMap()) {
                val day=repo.day(date);val patch=healthSyncPatch(day,values)
                protected+=values.keys.count{day.fields[it]?.actor=="human"}
                if(patch.isNotEmpty()) {
                    synchronized(this) {
                        check(read(context).let{healthSyncGrantCurrent(it,cfg)})
                        selected()
                        repo.write(date,day.revision,patch,"mcp","device:hcmcp","已授权设备同步 · 按原始时间戳和手机时区归日")
                    }
                    changed+=patch.size
                }
            }
            val status=if(days.isEmpty())"服务已连接，近7天暂无可用记录" else "已同步 ${days.size} 天，更新 $changed 项"+(if(protected>0)"；保留 $protected 项手动校正" else "")
            synchronized(this) {
                val current=read(context)
                if(healthSyncGrantCurrent(current,cfg))
                    save(context,current.copy(lastAttempt=System.currentTimeMillis(),lastSuccess=System.currentTimeMillis(),
                        completedSlot=if(manual)current.completedSlot else slot.key,status=status))
            };true
        }catch(cancel:CancellationException){
            if(cancel !is TimeoutCancellationException)throw cancel
            failed(context,cfg);false
        }catch(_:Exception){ failed(context,cfg);false }
    }
    @Synchronized private fun failed(context:Context,cfg:HealthSyncSettings) {
        val live=read(context)
        if(healthSyncGrantCurrent(live,cfg))
            save(context,live.copy(status="同步未完成；原记录保留。请检查网络、MCP连接或重新选择来源。"))
    }
}

internal class HealthSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        try {
            if(AndroidHealthSync.read(applicationContext).generation!=inputData.getString("generation"))
                return@withContext Result.success()
            val ok=AndroidHealthSync.sync(applicationContext)
            if(!ok&&runAttemptCount<3&&AndroidHealthSync.read(applicationContext).enabled)Result.retry()
            else {AndroidHealthSync.schedule(applicationContext,nextOnly=true);Result.success()}
        } catch(cancel:CancellationException){throw cancel}catch(_:Exception){Result.failure()}
    }
}
