@file:Suppress("MissingPermission", "DEPRECATION")

package me.rerere.rikkahub.data.orbis.toy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class ToyNearbyDevice(val selectionId: String, val name: String, val suffix: String)
data class ToyDiscoveryState(val scanning: Boolean = false, val connecting: Boolean = false,
    val devices: List<ToyNearbyDevice> = emptyList(), val message: String = "")

/** User-driven BLE only. Never stores an address, polls a server, auto-reconnects or scans from AI. */
class OrbisToyController private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = ToySession(scope)
    val state = session.state
    private val discoveryMutable = MutableStateFlow(ToyDiscoveryState())
    val discovery = discoveryMutable.asStateFlow()
    private val candidates = linkedMapOf<String, BluetoothDevice>()
    private var scannerCallback: ScanCallback? = null
    private var scanTimer: Job? = null
    private var connectJob: Job? = null
    private val links = linkedMapOf<String, GattToyTransport>()
    private val processScope = UUID.randomUUID().toString()
    private var sequence = 0L
    private var activationEpoch = 0L

    fun approvalScope(): String? = state.value.takeIf { it.connected }?.let { "$processScope:${it.connectionRevision}" }
    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun hasScanPermissions(): Boolean = ToyPermissionPolicy.canScan(Build.VERSION.SDK_INT, ::isPermissionGranted)
    private fun hasConnectionPermission(): Boolean = ToyPermissionPolicy.canConnect(Build.VERSION.SDK_INT, ::isPermissionGranted)
    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Call only from a human tap after the Android permission result. */
    fun startScan() {
        scope.launch {
            if (!hasScanPermissions()) { discoveryMutable.value = discoveryMutable.value.copy(message = "请先授予扫描所需权限。"); return@launch }
            if (discovery.value.connecting) return@launch
            links.entries.removeAll { it.value.gatt == null }
            stopScan()
            val bluetooth = runCatching { adapter()?.takeIf { it.isEnabled } }.getOrNull()
            if (bluetooth == null) { discoveryMutable.value = ToyDiscoveryState(message = "请先在手机系统中打开蓝牙，并检查权限。"); return@launch }
            val scanner = runCatching { bluetooth.bluetoothLeScanner }.getOrNull()
            if (scanner == null) { discoveryMutable.value = ToyDiscoveryState(message = "此设备暂不能扫描低功耗蓝牙。"); return@launch }
            candidates.clear()
            discoveryMutable.value = ToyDiscoveryState(scanning = true, message = "扫描 15 秒；请确认设备名称与机身编号后手动选择。")
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val callback = this
                    scope.launch {
                        if (scannerCallback !== callback || !hasScanPermissions()) return@launch
                        try {
                            val device = result.device
                            val id = device.address
                            if (links.containsKey(id)) return@launch
                            if (candidates.size >= 40 && !candidates.containsKey(id)) return@launch
                            candidates[id] = device
                            val name = (result.scanRecord?.deviceName ?: device.name ?: "未命名 BLE 设备")
                                .filterNot(Char::isISOControl).take(60)
                            val existing = discovery.value.devices.filterNot { it.selectionId == id }
                            discoveryMutable.value = discovery.value.copy(devices = existing + ToyNearbyDevice(id, name, id.takeLast(5)))
                        } catch (_: SecurityException) {
                            stopScan(); discoveryMutable.value = discovery.value.copy(message = "蓝牙权限已失效，请重新授权。")
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    val callback = this
                    scope.launch {
                        if (scannerCallback !== callback) return@launch
                        stopScan(); discoveryMutable.value = discovery.value.copy(message = "蓝牙扫描失败（$errorCode），请稍后重试。")
                    }
                }
            }
            scannerCallback = callback
            try { scanner.startScan(callback) }
            catch (_: Exception) { stopScan(); discoveryMutable.value = discovery.value.copy(message = "无法开始扫描，请检查系统蓝牙权限。"); return@launch }
            scanTimer = scope.launch { delay(15_000); stopScan() }
        }
    }

    fun stopScan() {
        scanTimer?.cancel(); scanTimer = null
        val callback = scannerCallback
        scannerCallback = null
        if (callback != null) try { adapter()?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
        discoveryMutable.value = discovery.value.copy(scanning = false)
    }

    /** This method is intentionally absent from the AI tool catalogue. */
    fun connectSelected(selectionId: String) {
        scope.launch {
            if (!hasConnectionPermission() || discovery.value.connecting) return@launch
            links.entries.removeAll { it.value.gatt == null }
            if (links.containsKey(selectionId)) return@launch
            val device = candidates[selectionId] ?: return@launch
            val label = discovery.value.devices.find { it.selectionId == selectionId }?.name ?: "用户选择的 Toy"
            stopScan()
            val expected = ++sequence
            discoveryMutable.value = discovery.value.copy(connecting = true, message = "正在连接并验证 FFE0 协议；连接后遵循你确认的当前强度。")
            connectJob = scope.launch {
                val link = GattToyTransport()
                val discovered = CompletableDeferred<Unit>()
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        scope.launch {
                            if (links[selectionId] !== link) { runCatching { gatt.close() }; return@launch }
                            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                                if (runCatching { gatt.discoverServices() }.getOrDefault(false).not())
                                    discovered.completeExceptionally(IllegalStateException("discovery_failed"))
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                                link.failPending()
                                discovered.completeExceptionally(IllegalStateException("disconnected"))
                                links.remove(selectionId)
                                session.linkLost(link, "蓝牙连接中断，无法确认设备已停止；请检查实体开关。")
                                link.close()
                            }
                        }
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        scope.launch {
                            if (links[selectionId] !== link) return@launch
                            val writable = runCatching { gatt.getService(SERVICE)?.characteristics.orEmpty() }.getOrDefault(emptyList()).filter {
                                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            }
                            val characteristic = writable.firstOrNull()
                            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) discovered.completeExceptionally(IllegalStateException("unsupported_device"))
                            else { link.characteristic = characteristic; discovered.complete(Unit) }
                        }
                    }
                    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        scope.launch { if (links[selectionId] === link && characteristic.uuid == link.characteristic?.uuid) link.completeWrite(status) }
                    }
                }
                try {
                    links[selectionId] = link
                    link.gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
                    check(link.gatt != null)
                    withTimeout(15_000) { discovered.await() }
                    check(sequence == expected && links[selectionId] === link)
                    session.attach(label, link)
                    if (state.value.connected && links[selectionId] === link && sequence == expected) {
                        discoveryMutable.value = discovery.value.copy(connecting = false, devices = emptyList(), message = "已加入手动选择的设备；可以继续添加其它设备。")
                        candidates.clear()
                    } else error("connection_changed")
                } catch (e: Exception) {
                    link.close()
                    if (links[selectionId] === link) links.remove(selectionId)
                    session.linkLost(link, "连接已中断；请检查设备的实体状态。")
                    if (sequence == expected) discoveryMutable.value = discovery.value.copy(connecting = false,
                        message = "连接失败、超时或协议不兼容；未开启控制。请检查自己的设备后重试。")
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    suspend fun set(intensity: Int, expectedScope: String): ToyState = withContext(Dispatchers.Main.immediate) {
        if (intensity == 0) return@withContext stop()
        check(hasConnectionPermission()) { "蓝牙连接权限不可用，请本人重新授权。" }
        check(expectedScope == approvalScope()) { "设备连接已变化，请重新获取工具并批准当前连接。" }
        val expectedEpoch = activationEpoch
        session.set(intensity) {
            expectedEpoch == activationEpoch && expectedScope == approvalScope() && hasConnectionPermission()
        }
    }
    suspend fun stop(): ToyState = withContext(Dispatchers.Main.immediate) {
        activationEpoch++ // Invalidate queued activation before waiting for the in-flight GATT write.
        session.stop()
    }
    fun stopAndDisconnect(reason: String = "已尝试停止并断开。") {
        scope.launch {
            sequence++; activationEpoch++
            stopScan(); connectJob?.cancel(); connectJob = null
            session.stop(disconnect = true, reason = reason)
            links.values.toList().forEach { it.close() }; links.clear()
            candidates.clear()
            discoveryMutable.value = ToyDiscoveryState(message = reason)
        }
    }

    private inner class GattToyTransport : ToyTransport {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        private var pending: CompletableDeferred<Unit>? = null
        override suspend fun write(packet: ByteArray) {
            check(hasConnectionPermission()) { "蓝牙连接权限不可用。" }
            check(pending == null) { "write_in_progress" }
            val active = checkNotNull(gatt)
            val target = checkNotNull(characteristic)
            val completion = CompletableDeferred<Unit>()
            pending = completion
            try {
                val type = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    active.writeCharacteristic(target, packet.copyOf(), type) == BluetoothStatusCodes.SUCCESS
                } else {
                    target.writeType = type; target.value = packet.copyOf(); active.writeCharacteristic(target)
                }
                check(accepted) { "write_rejected" }
                completion.await()
            } finally { if (pending === completion) pending = null }
        }
        fun completeWrite(status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(IllegalStateException("write_failed"))
        }
        fun failPending() { pending?.completeExceptionally(IllegalStateException("disconnected")) }
        override fun close() {
            failPending()
            val old = gatt; gatt = null
            runCatching { old?.disconnect() }; runCatching { old?.close() }
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        @Volatile private var instance: OrbisToyController? = null
        fun get(context: Context): OrbisToyController = instance ?: synchronized(this) {
            instance ?: OrbisToyController(context).also { instance = it }
        }
        fun requiredScanPermissions(): Array<String> = ToyPermissionPolicy.scanPermissions(Build.VERSION.SDK_INT).toTypedArray()
    }
}
