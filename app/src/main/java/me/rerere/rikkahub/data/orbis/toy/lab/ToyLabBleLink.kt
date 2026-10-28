@file:Suppress("MissingPermission", "DEPRECATION")
package me.rerere.rikkahub.data.orbis.toy.lab

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Discovery and standard identity reads issue no characteristic/descriptor writes. */
internal class ToyLabBleLink private constructor(private val context: Context) : LabTransport {
    private var gatt: BluetoothGatt? = null
    private var open = true
    private val ready = CompletableDeferred<Unit>()
    private var pending: CompletableDeferred<ByteArray>? = null
    private var pendingKey: String? = null
    private val mutex = Mutex()
    override lateinit var report: LabDeviceReport
        private set
    var onLost: (() -> Unit)? = null
    private fun key(kind: String, c: BluetoothGattCharacteristic) = "$kind:${c.service.uuid}/${c.uuid}"
    private fun finish(kind: String, c: BluetoothGattCharacteristic, status: Int, value: ByteArray = byteArrayOf()) {
        if (!open || pendingKey != key(kind,c)) return
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(value.copyOf())
        else pending?.completeExceptionally(IllegalStateException("蓝牙操作失败（$status），未自动重试"))
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!open || g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) ready.completeExceptionally(IllegalStateException("设备拒绝读取服务"))
            } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = IllegalStateException("连接已断开或连接失败（$status）")
                ready.completeExceptionally(error); pending?.completeExceptionally(error)
                close(); onLost?.invoke()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!open || g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else ready.completeExceptionally(IllegalStateException("无法读取设备通道（$status）"))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt) finish("write", c, status)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g === gatt) finish("notify",d.characteristic,status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && g === gatt) finish("read",c,status,c.value ?: byteArrayOf())
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g === gatt) finish("read",c,status,value)
        }
    }
    private fun channel(service: String, characteristic: String): BluetoothGattCharacteristic {
        check(open) { "连接已关闭，请重新连接" }
        return checkNotNull(gatt?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))) { "通道不存在" }
    }
    private suspend fun operation(kind: String, c: BluetoothGattCharacteristic, start: () -> Boolean): ByteArray = mutex.withLock {
        val completion = CompletableDeferred<ByteArray>()
        pendingKey = key(kind,c); pending = completion
        try {
            check(start()) { "蓝牙未接受本次操作" }
            withTimeout(3000) { completion.await() }
        } catch (e: CancellationException) {
            // Android gives no transaction IDs; quarantine late callbacks by closing this GATT.
            close(); throw e
        } finally { pending = null; pendingKey = null }
    }
    override suspend fun write(packet: LabPacket) {
        packet.validate(report)
        val c = channel(packet.service,packet.characteristic)
        val bytes = packet.bytes()
        val type = if (packet.mode == "response") BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        operation("write",c) {
            if (Build.VERSION.SDK_INT >= 33) gatt?.writeCharacteristic(c,bytes,type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = bytes; gatt?.writeCharacteristic(c) == true }
        }
    }
    override suspend fun subscribe(channel: LabNotify) {
        channel.validate(report)
        val c = channel(channel.service,channel.characteristic)
        val descriptor = checkNotNull(c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))) { "缺少通知描述符" }
        operation("notify",c) {
            check(gatt?.setCharacteristicNotification(c,true) == true) { "无法开启通知" }
            if (Build.VERSION.SDK_INT >= 33) gatt?.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf(); gatt?.writeDescriptor(descriptor) == true }
        }
    }
    override fun close() {
        if (!open) return
        open = false
        pending?.completeExceptionally(IllegalStateException("连接已关闭"))
        val old = gatt; gatt = null
        try { old?.disconnect() } finally { old?.close() }
    }
    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice, name: String): ToyLabBleLink = withContext(Dispatchers.Main.immediate) {
            val link = ToyLabBleLink(context)
            try {
                withTimeout(15000) {
                    link.gatt = checkNotNull(device.connectGatt(context,false,link.callback,BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK,Handler(Looper.getMainLooper()))) { "系统无法创建蓝牙连接" }
                    link.ready.await()
                    val channels = link.gatt!!.services.flatMap { s -> s.characteristics.map { c -> LabChannel(s.uuid.toString(),c.uuid.toString(),c.properties) } }
                    require(channels.size <= 256 && channels.map { it.key }.distinct().size == channels.size) { "服务太多或通道不唯一，不能可靠识别。" }
                    suspend fun identity(short: String): String {
                        val c = link.gatt?.getService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"))
                            ?.getCharacteristic(UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")) ?: return ""
                        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return ""
                        return try { link.operation("read",c) { link.gatt?.readCharacteristic(c) == true }
                            .toString(Charsets.UTF_8).filterNot { it.isISOControl() }.take(120) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "" }
                    }
                    link.report = LabDeviceReport(name.take(120),channels,identity("2a24"),identity("2a26"),System.currentTimeMillis())
                }
                link
            } catch (e: Exception) { link.close(); throw e }
        }
    }
}
