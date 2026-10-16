@file:Suppress("MissingPermission", "DEPRECATION")

package me.rerere.rikkahub.data.orbis.toy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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

data class ToyNearbyDevice(val selectionId: String, val name: String, val suffix: String,
    val yutuV2: Boolean = false)
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
                            val yutuV2 = YutuV2Info.matchesAdvertisement(name,
                                result.scanRecord?.getManufacturerSpecificData(YutuV2Info.MANUFACTURER_ID))
                            val existing = discovery.value.devices.filterNot { it.selectionId == id }
                            discoveryMutable.value = discovery.value.copy(devices = existing + ToyNearbyDevice(id, name, id.takeLast(5), yutuV2))
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
            val selected = discovery.value.devices.find { it.selectionId == selectionId }
            val label = selected?.name ?: "用户选择的 Toy"
            val profile = if (selected?.yutuV2 == true) ToyProfile.YUTU_V2 else ToyProfile.SVAKOM
            if (state.value.connected && (state.value.profile != profile || profile == ToyProfile.YUTU_V2)) {
                discoveryMutable.value = discovery.value.copy(message = "请先停止并断开当前设备，再选择另一种协议或另一台 YUTU。")
                return@launch
            }
            stopScan()
            val expected = ++sequence
            discoveryMutable.value = discovery.value.copy(connecting = true, message =
                if (profile == ToyProfile.YUTU_V2) "正在验证 YUTU V2 服务、通知与固件；连接不会启动设备。"
                else "正在连接并验证 FFE0 协议；连接后遵循你确认的当前档位。")
            connectJob = scope.launch {
                val link = GattToyTransport(profile)
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
                            val yutuService = gatt.getService(UUID.fromString(YutuV2Info.SERVICE))
                            if (yutuService != null || profile == ToyProfile.YUTU_V2) {
                                link.profile = ToyProfile.YUTU_V2
                                val service = yutuService
                                val op = service?.getCharacteristic(UUID.fromString(YutuV2Info.OPERATION))
                                val fn = service?.getCharacteristic(UUID.fromString(YutuV2Info.FUNCTION))
                                if (status != BluetoothGatt.GATT_SUCCESS || op == null || fn == null ||
                                    op.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                                    fn.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0 ||
                                    listOf(op, fn).any { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 }) {
                                    discovered.completeExceptionally(IllegalStateException("unsupported_yutu_profile"))
                                } else {
                                    link.operation = op; link.characteristic = fn; discovered.complete(Unit)
                                }
                                return@launch
                            }
                            val writable = runCatching { gatt.getService(SERVICE)?.characteristics.orEmpty() }.getOrDefault(emptyList()).filter {
                                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            }
                            val characteristic = writable.firstOrNull()
                            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) discovered.completeExceptionally(IllegalStateException("unsupported_device"))
                            else { link.characteristic = characteristic; discovered.complete(Unit) }
                        }
                    }
                    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        scope.launch { if (links[selectionId] === link && characteristic.uuid == link.pendingCharacteristic && !link.writingDescriptor) link.completeWrite(status) }
                    }
                    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        scope.launch { if (links[selectionId] === link && link.writingDescriptor && descriptor.characteristic.uuid == link.pendingCharacteristic) link.completeWrite(status) }
                    }
                    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                        val copy = value.copyOf()
                        scope.launch { if (links[selectionId] === link) link.receive(characteristic, copy) }
                    }
                    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                        if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(gatt, characteristic, characteristic.value?.copyOf() ?: return)
                    }
                }
                try {
                    links[selectionId] = link
                    link.gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
                    check(link.gatt != null)
                    withTimeout(15_000) { discovered.await() }
                    if (link.profile == ToyProfile.YUTU_V2) withTimeout(10_000) { link.prepareYutu() }
                    check(sequence == expected && links[selectionId] === link)
                    session.attach(label, link)
                    if (state.value.connected && links[selectionId] === link && sequence == expected) {
                        discoveryMutable.value = discovery.value.copy(connecting = false, devices = emptyList(), message =
                            if (link.profile == ToyProfile.YUTU_V2) "YUTU V2 固件 1.0.0 已连接，等待本人授权后的 AI 调用；不会自动启动。"
                            else "已加入手动选择的设备；可以继续添加同协议设备。")
                        candidates.clear()
                    } else error("connection_changed")
                } catch (e: Exception) {
                    link.close()
                    if (links[selectionId] === link) links.remove(selectionId)
                    session.linkLost(link, "连接已中断；请检查设备的实体状态。")
                    if (sequence == expected) discoveryMutable.value = discovery.value.copy(connecting = false,
                        message = if (link.profile == ToyProfile.YUTU_V2)
                            "YUTU 连接、通知或固件核验未通过；当前适配固件 1.0.0。未开启控制，请确认官方 App 已断开且设备已开机。"
                        else "连接失败、超时或协议不兼容；未开启控制。请检查自己的设备后重试。")
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    suspend fun setLevel(level: Int, expectedScope: String): ToyState = withContext(Dispatchers.Main.immediate) {
        ToyProtocol.legacyIntensityForLevel(level) // Reject invalid levels before checking or using a connection.
        if (level == 0) return@withContext stop()
        check(hasConnectionPermission()) { "蓝牙连接权限不可用，请本人重新授权。" }
        check(expectedScope == approvalScope()) { "设备连接已变化，请重新获取工具并批准当前连接。" }
        val expectedEpoch = activationEpoch
        session.setLevel(level) {
            expectedEpoch == activationEpoch && expectedScope == approvalScope() && hasConnectionPermission()
        }
    }
    suspend fun stop(): ToyState = withContext(Dispatchers.Main.immediate) {
        activationEpoch++ // Invalidate queued activation before waiting for the in-flight GATT write.
        sequence++
        connectJob?.cancel(); connectJob = null
        discoveryMutable.value = discovery.value.copy(connecting = false)
        session.stop()
    }

    internal suspend fun setYutu(command: YutuControlContract.Command, expectedScope: String): ToyState = withContext(Dispatchers.Main.immediate) {
        check(hasConnectionPermission() && expectedScope == approvalScope()) { "设备连接或授权已变化，请重新获取工具。" }
        val expectedEpoch = activationEpoch
        session.setYutu(command.strength, command.durationSeconds) {
            expectedEpoch == activationEpoch && expectedScope == approvalScope() && hasConnectionPermission()
        }
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

    private inner class GattToyTransport(override var profile: ToyProfile) : ToyTransport {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        var operation: BluetoothGattCharacteristic? = null
        var pendingCharacteristic: UUID? = null
        var writingDescriptor = false
        private val firmware = CompletableDeferred<String>()
        private var pending: CompletableDeferred<Unit>? = null
        override suspend fun write(packet: ByteArray) {
            if (profile == ToyProfile.YUTU_V2) {
                check(packet.size == 3 && packet[0] == 0xA0.toByte() && packet[1] == 0xA0.toByte() && (packet[2].toInt() and 0xff) in YutuControlContract.testedValues)
                check(characteristic?.uuid.toString() == YutuV2Info.FUNCTION)
            }
            writeTo(checkNotNull(characteristic), packet)
        }
        private suspend fun writeTo(target: BluetoothGattCharacteristic, packet: ByteArray) {
            check(hasConnectionPermission()) { "蓝牙连接权限不可用。" }
            check(pending == null) { "write_in_progress" }
            val active = checkNotNull(gatt)
            val completion = CompletableDeferred<Unit>()
            pending = completion
            pendingCharacteristic = target.uuid
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
            } finally { if (pending === completion) { pending = null; pendingCharacteristic = null } }
        }
        suspend fun prepareYutu() {
            val active = checkNotNull(gatt)
            for (target in listOf(checkNotNull(operation), checkNotNull(characteristic))) {
                val descriptor = checkNotNull(target.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
                check(active.setCharacteristicNotification(target, true))
                val completion = CompletableDeferred<Unit>()
                pending = completion; pendingCharacteristic = target.uuid; writingDescriptor = true
                try {
                    val accepted = if (Build.VERSION.SDK_INT >= 33)
                        active.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()) == BluetoothStatusCodes.SUCCESS
                    else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf(); active.writeDescriptor(descriptor) }
                    check(accepted) { "yutu_notify_rejected" }
                    completion.await()
                } finally { pending = null; pendingCharacteristic = null; writingDescriptor = false }
            }
            writeTo(checkNotNull(operation), YutuV2Info.firmwareQuery())
            check(firmware.await() == "1.0.0") { "yutu_firmware_unverified" }
        }
        fun receive(target: BluetoothGattCharacteristic, packet: ByteArray) {
            if (profile != ToyProfile.YUTU_V2 || target.service?.uuid.toString() != YutuV2Info.SERVICE ||
                target.uuid.toString() !in setOf(YutuV2Info.OPERATION, YutuV2Info.FUNCTION)) return
            val value = YutuV2Info.parseNotification(packet)
            if (value is YutuV2Info.Value.Firmware) firmware.complete(value.version)
        }
        fun completeWrite(status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(IllegalStateException("write_failed"))
        }
        fun failPending() {
            pending?.completeExceptionally(IllegalStateException("disconnected"))
            firmware.completeExceptionally(IllegalStateException("disconnected"))
        }
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
