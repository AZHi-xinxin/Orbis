@file:Suppress("MissingPermission")
package me.rerere.rikkahub.data.orbis.toy.lab

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.orbis.toy.OrbisToyController
import me.rerere.rikkahub.data.orbis.toy.ToyPermissionPolicy
import java.io.File
import java.util.UUID

internal data class LabNearby(val id: String, val name: String)
internal data class LabState(
    val scanning: Boolean = false, val connecting: Boolean = false, val connected: Boolean = false,
    val nearby: List<LabNearby> = emptyList(), val archive: LabArchive? = null,
    val archiveId: String? = null, val sharedWith: String? = null, val grantRevision: Long = 0,
    val message: String = "先选择你的设备。连接时只读取通道，不发送控制指令。",
)

/** A separate connection and storage domain; it never joins or rewrites the direct-control session. */
internal class ToyLabController internal constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LabState())
    val state = mutable.asStateFlow()
    private val session = ToyLabSession(scope)
    val runState = session.state
    private val candidates = linkedMapOf<String,BluetoothDevice>()
    private var selected: BluetoothDevice? = null
    private var link: ToyLabBleLink? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimer: Job? = null
    private var connectJob: Job? = null
    private val edits = Mutex()
    private suspend fun edit(block: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) { edits.withLock { block() } }
    private var selectionEpoch = 0L
    fun selectionScope(): String = "$processId:$selectionEpoch"
    private var epoch = 0L
    private var approvalEpoch = 0L
    private val processId = UUID.randomUUID().toString()
    private var foregroundManaged = false
    private fun watchForeground() {
        foregroundManaged = true
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    stopScan()
                    if (state.value.connected || state.value.connecting || runState.value.busy) scope.launch { stop() }
                }
            })
        }
    }
    private val root = File(context.noBackupFilesDir,"orbis-toy-lab")
    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter
    fun hasScanPermissions() = ToyPermissionPolicy.canScan(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED
    }
    private fun hasConnectPermission() = ToyPermissionPolicy.canConnect(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED
    }
    fun approvalScope(owner: String): String? = state.value.takeIf { it.sharedWith == owner && selected != null }
        ?.let { "$processId:$approvalEpoch:${it.grantRevision}" }
    fun sharedArchive(owner: String): LabArchive? = state.value.takeIf { it.sharedWith == owner }?.archive
    fun startScan() {
        if (!hasScanPermissions()) { message("请先授予附近设备权限。"); return }
        if (state.value.connecting || runState.value.busy) { message("请先结束当前连接或试调。"); return }
        stopScan(); candidates.clear()
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { message("蓝牙未打开或本机不支持 BLE 扫描。"); return }
        mutable.value = state.value.copy(scanning = true, nearby = emptyList(),message = "正在寻找附近设备（15秒）")
        var callbackRef: ScanCallback? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { scope.launch {
                if (scanCallback !== callbackRef || !hasScanPermissions()) return@launch
                val existing = candidates.entries.firstOrNull { it.value.address == result.device.address }
                if (existing != null || candidates.size >= 40) return@launch
                val id = UUID.randomUUID().toString()
                val name = (result.scanRecord?.deviceName ?: result.device.name ?: "未命名设备").filterNot { it.isISOControl() }.take(120)
                candidates[id] = result.device
                mutable.value = state.value.copy(nearby = state.value.nearby + LabNearby(id,name))
            } }
            override fun onScanFailed(errorCode: Int) { scope.launch { stopScan(); message("扫描失败（$errorCode），请检查权限和蓝牙。") } }
        }
        callbackRef = callback
        scanCallback = callback
        try { scanner.startScan(callback); scanTimer = scope.launch { delay(15000); stopScan() } }
        catch (_: Exception) { stopScan(); message("无法开始扫描，请检查系统蓝牙权限。") }
    }
    fun stopScan() {
        scanTimer?.cancel(); scanTimer = null
        val old = scanCallback; scanCallback = null
        if (old != null) runCatching { adapter()?.bluetoothLeScanner?.stopScan(old) }
        mutable.value = state.value.copy(scanning = false)
    }
    fun choose(id: String) {
        val device = candidates[id] ?: return
        val name = state.value.nearby.firstOrNull { it.id == id }?.name ?: "我的设备"
        stopScan()
        scope.launch {
            stop()
            replaceSelectedDevice(device)
            connectJob = scope.launch { try { connect(name, first = true) } catch (e: Exception) { if (e !is CancellationException) message(e.message ?: "连接失败") } }
        }
    }
    // Called after stop(); also rotates the emergency-stop binding, not just the ordinary grant.
    private fun replaceSelectedDevice(device: BluetoothDevice) {
        ++selectionEpoch
        ++epoch
        ++approvalEpoch
        session.clearResult()
        localConfirmedRevisions.clear()
        selected = device
        mutable.value = state.value.copy(archive = null,archiveId = null,sharedWith = null,
            grantRevision = state.value.grantRevision + 1,nearby = emptyList())
        candidates.clear()
    }
    suspend fun reconnect(owner: String? = null) = withContext(Dispatchers.Main.immediate) {
        if (owner != null) require(state.value.sharedWith == owner) { "人类尚未将此设备交给当前AI。" }
        check(!state.value.connecting && !runState.value.busy)
        connectJob = currentCoroutineContext()[Job]
        try { connect(state.value.archive?.report?.name ?: "我的设备", first = false) } finally { connectJob = null }
    }
    private suspend fun connect(name: String, first: Boolean) {
        if (state.value.connected) return
        check(hasConnectPermission()) { "缺少附近设备连接权限。" }
        check(!OrbisToyController.get(context).state.value.connected) { "原直连正在连接设备，请先在原入口停止并断开，再使用独立调试台。" }
        val device = selected ?: error("请本人先在调试台选择设备，AI不能扫描附近设备。")
        val expected = ++epoch
        mutable.value = state.value.copy(connecting = true,message = "正在连接并只读检查…")
        try {
            val opened = ToyLabBleLink.connect(context,device,name)
            if (expected != epoch) { opened.close(); return }
            val old = state.value.archive
            if (!first && old != null && old.report.fingerprint() != opened.report.fingerprint()) {
                opened.close(); mutable.value = state.value.copy(sharedWith = null)
                error("型号、固件或通道已改变，请本人重新选择设备并核对新档案。")
            }
            link = opened
            opened.onLost = { scope.launch { if (link === opened) { link = null; mutable.value = state.value.copy(connected = false,message = "设备已断开；不能据此确认物理停止。") } } }
            mutable.value = state.value.copy(connected = true,archive = old ?: LabArchive(report = opened.report),
                message = "已连接，完成只读检查。尚未发送控制指令；实际兼容性待本人试调。")
        } catch (e: Exception) {
            mutable.value = state.value.copy(connected = false,message = if (e is CancellationException) "连接已取消" else e.message?.take(300) ?: "无法连接")
            if (e is CancellationException) throw e
        } finally { mutable.value = state.value.copy(connecting = false) }
    }
    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        ++epoch; ++approvalEpoch
        val current = currentCoroutineContext()[Job]
        if (connectJob !== current) connectJob?.cancelAndJoin()
        connectJob = null
        session.stop()
        val closeFailed = runCatching { link?.close() }.isFailure; link = null
        mutable.value = state.value.copy(connected = false,connecting = false,message = if (closeFailed) "断开请求失败，不能确认已停止，请检查实体开关。" else runState.value.trial?.let { runState.value.message } ?: "已请求停止并断开。请本人确认实物是否停止。")
    }
    suspend fun share(owner: String?) = withContext(Dispatchers.Main.immediate) {
        stop()
        check(state.value.archive != null) { "先连接并检查设备。" }
        mutable.value = state.value.copy(sharedWith = owner, grantRevision = state.value.grantRevision + 1,
            message = if (owner == null) "已收回AI权限；旧的工具授权不再有效。" else "已允许所选助手读取、配置此设备并请求试调；下一条新消息生效。")
    }
    suspend fun install(profile: LabProfile, owner: String? = null, expectedRevision: Int? = null) = edit {
        if (owner != null) check(state.value.sharedWith == owner) { "未获当前设备授权。" }
        check(!state.value.connecting && !runState.value.busy) { "先停止试调再修改方案。" }
        val archive = state.value.archive ?: error("先完成设备检查。")
        if (owner != null) check(expectedRevision == (archive.latest()?.revision ?: 0)) { "方案版本已改变，请重新查询。" }
        val next = archive.addProfile(profile, if (owner == null) "人类" else "AI",System.currentTimeMillis())
        persist(next)
        ++epoch
        mutable.value = state.value.copy(archive = next,message = "方案已安装为第${next.latest()!!.revision}版，尚未据此验证物理反应。")
    }
    suspend fun start(action: String, seconds: Int, owner: String? = null, expectedScope: String? = null, expectedRevision: Int? = null) = withContext(Dispatchers.Main.immediate) {
        if (owner != null) check(expectedScope != null && approvalScope(owner) == expectedScope) { "授权或方案已改变，请重新获取工具。" }
        check(!foregroundManaged || ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "请回到Orbis前台再试调。" }
        val archive = state.value.archive ?: error("尚无设备档案")
        val revision = archive.latest() ?: error("请先安装适配方案")
        if (owner != null) check(expectedRevision == revision.revision) { "方案版本已改变，请重新读取档案。" }
        val verifiedHere = localConfirmedRevisions.contains(revision.profile.digest())
        require(seconds in 1..if (verifiedHere) revision.profile.maxRunSeconds else minOf(10,revision.profile.maxRunSeconds)) {
            "尚未在本机确认反应和停止的方案，单次试调最多10秒。"
        }
        val active = link ?: error("尚未连接。请先连接已选设备。")
        val expected = epoch
        session.start(active,revision,action,seconds,valid = { epoch == expected && link === active &&
            (owner == null || state.value.sharedWith == owner) },onClosed = {
            if (link === active) { link = null; mutable.value = state.value.copy(connected = false) }
        })
    }
    private val localConfirmedRevisions = mutableSetOf<String>()
    suspend fun observe(reaction: String, note: String, stopped: String) = edit {
        check(!runState.value.busy) { "等本次试调结束后再记录。" }
        val trial = runState.value.trial ?: error("还没有试调结果")
        val archive = state.value.archive ?: error("没有档案")
        check(archive.revisions.any { it.revision == trial.revision }) { "这是另一份档案的试调，请重新试调。" }
        val observation = LabObservation(trial,reaction,note,stopped,System.currentTimeMillis()).also { it.validate() }
        val next = archive.copy(observations = archive.observations.filterNot { it.trial.id == trial.id } + observation)
        next.validate(); persist(next)
        if (reaction == "有反应" && stopped == "确认已停" && trial.writeSucceeded && trial.stopRequestSucceeded)
            localConfirmedRevisions.add(archive.revisions.single { it.revision == trial.revision }.profile.digest())
        mutable.value = state.value.copy(archive = next,message = "已保存你的观察；没有自动发送到聊天。")
    }
    suspend fun saveReport() { edit { persist(state.value.archive ?: error("还没有检查结果")); message("档案已保存在本机") } }
    suspend fun importArchive(text: String) = edit {
        check(!runState.value.busy && !state.value.connecting) { "请先停止当前试调。" }
        val current = state.value.archive ?: error("请先选择设备并完成只读检查，再导入对应档案。")
        require(text.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "档案超过512KiB。" }
        val root = ToyLabJson.parseToJsonElement(text).jsonObject
        val isProfile = "functions" in root
        val imported = if (isProfile) current.addProfile(decodeLabProfile(root, current.report), "信道台导入", System.currentTimeMillis())
            else decodeLabArchive(text)
        require(current.report.fingerprint() == imported.report.fingerprint()) { "导入档案的型号、固件或通道与所选设备不符，未安装。" }
        stop(); session.clearResult(); localConfirmedRevisions.clear()
        // Imported observations retain attribution, but never become local approvals or physical proof.
        val next = if (isProfile) imported else imported.copy(report = current.report,
            revisions = imported.revisions.map { it.copy(author = "导入 · "+it.author.take(90)) },
            observations = imported.observations.map { it.copy(origin = "imported") })
        mutable.value = state.value.copy(sharedWith = null,archiveId = null)
        persist(next)
        mutable.value = state.value.copy(archive = next,message = if (isProfile)
            "方案已导入，原本机观察记录保留；新方案尚未实测，请重新确认授权。"
            else "已导入。历史记录来自文件；本机授权与验证需重新确认。")
    }
    suspend fun exportArchive(): String = withContext(Dispatchers.IO) { ToyLabJson.encodeToString(state.value.archive ?: error("暂无档案")) }
    private suspend fun persist(archive: LabArchive) {
        val expectedEpoch = epoch
        archive.validate()
        val encoded = ToyLabJson.encodeToString(archive).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 512 * 1024) { "档案已达512KiB，请导出后另建档案。" }
        val id = state.value.archiveId ?: UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            check(root.isDirectory || root.mkdirs())
            val file = AtomicFile(File(root,"$id.json")); val out = file.startWrite()
            try { out.write(encoded); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        }
        check(epoch == expectedEpoch) { "设备或授权在保存期间改变，未应用旧结果。" }
        mutable.value = state.value.copy(archiveId = id)
    }
    suspend fun savedArchives(): List<Pair<String,String>> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }.take(40).mapNotNull { f ->
            runCatching { require(f.length() <= 512 * 1024); f.nameWithoutExtension to decodeLabArchive(f.readText()).report.name }.getOrNull()
        }
    }
    suspend fun loadArchive(id: String) {
        UUID.fromString(id)
        val text = withContext(Dispatchers.IO) { val f = File(root,"$id.json"); require(f.length() <= 512 * 1024); f.readText() }
        importArchive(text)
    }
    fun message(text: String) { mutable.value = state.value.copy(message = text.take(500)) }
    companion object {
        @Volatile private var instance: ToyLabController? = null
        fun get(context: Context): ToyLabController = instance ?: synchronized(this) { instance ?: ToyLabController(context).also { instance = it; it.watchForeground() } }
    }
}
