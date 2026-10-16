@file:Suppress("MissingPermission", "DEPRECATION")

package me.rerere.rikkahub.data.orbis.toy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Human-initiated, bounded connection diagnosis. Never joins [ToySession]. */
internal object YutuV2InfoProbe {
    suspend fun inspect(context: Context, device: BluetoothDevice): YutuV2InfoResult =
        withContext(Dispatchers.Main.immediate) {
            val connected = CompletableDeferred<Unit>()
            val services = CompletableDeferred<Unit>()
            val firmware = CompletableDeferred<String>()
            val battery = CompletableDeferred<Int>()
            var pending: CompletableDeferred<Unit>? = null
            var pendingDescriptor: UUID? = null
            var active: BluetoothGatt? = null
            var closed = false

            fun fail(reason: String) {
                val error = IllegalStateException(reason)
                connected.completeExceptionally(error)
                services.completeExceptionally(error)
                firmware.completeExceptionally(error)
                battery.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }

            fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
                if (closed || gatt !== active || characteristic.service?.uuid.toString() != YutuV2Info.SERVICE) return
                if (characteristic.uuid.toString() !in setOf(YutuV2Info.OPERATION, YutuV2Info.FUNCTION)) return
                when (val value = YutuV2Info.parseNotification(data)) {
                    is YutuV2Info.Value.Firmware -> firmware.complete(value.version)
                    is YutuV2Info.Value.Battery -> battery.complete(value.percent)
                    null -> Unit
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (closed || gatt !== active) return
                    when {
                        status != BluetoothGatt.GATT_SUCCESS -> fail("yutu_connection_failed")
                        newState == BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                        newState == BluetoothProfile.STATE_DISCONNECTED -> fail("yutu_disconnected")
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (closed || gatt !== active) return
                    if (status == BluetoothGatt.GATT_SUCCESS) services.complete(Unit)
                    else fail("yutu_discovery_failed")
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (closed || gatt !== active || descriptor.characteristic.uuid != pendingDescriptor) return
                    if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
                    else fail("yutu_notify_failed")
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (closed || gatt !== active || pendingDescriptor != null || characteristic.uuid.toString() != YutuV2Info.OPERATION) return
                    if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
                    else fail("yutu_info_query_failed")
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < 33) receive(gatt, characteristic, characteristic.value?.copyOf() ?: return)
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    receive(gatt, characteristic, value.copyOf())
                }
            }

            try {
                withTimeout(15_000) {
                    val gatt = checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper())))
                    active = gatt
                    connected.await()
                    check(gatt.discoverServices()) { "yutu_discovery_rejected" }
                    services.await()
                    val service = checkNotNull(gatt.getService(UUID.fromString(YutuV2Info.SERVICE)))
                    val op = checkNotNull(service.getCharacteristic(UUID.fromString(YutuV2Info.OPERATION)))
                    val function = checkNotNull(service.getCharacteristic(UUID.fromString(YutuV2Info.FUNCTION)))
                    check(op.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    // This device responds only after BOTH notification channels are enabled.
                    // CCCD subscription is not a function/actuator command.
                    for (characteristic in listOf(op, function)) {
                        check(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        val descriptor = checkNotNull(characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
                        check(gatt.setCharacteristicNotification(characteristic, true))
                        val completion = CompletableDeferred<Unit>()
                        pending = completion
                        pendingDescriptor = characteristic.uuid
                        val accepted = if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()) == BluetoothStatusCodes.SUCCESS
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
                            gatt.writeDescriptor(descriptor)
                        }
                        check(accepted) { "yutu_subscription_rejected" }
                        completion.await()
                        pending = null
                        pendingDescriptor = null
                    }
                    val completion = CompletableDeferred<Unit>()
                    pending = completion
                    val packet = YutuV2Info.firmwareQuery()
                    val accepted = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(op, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        op.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        op.value = packet
                        gatt.writeCharacteristic(op)
                    }
                    check(accepted) { "yutu_info_query_rejected" }
                    completion.await()
                    pending = null
                    val version = firmware.await()
                    YutuV2InfoResult(version, withTimeoutOrNull(500) { battery.await() })
                }
            } finally {
                closed = true
                pending?.cancel()
                connected.cancel()
                services.cancel()
                firmware.cancel()
                battery.cancel()
                // Disconnect on success, failure and cancellation; never retain an action-ready link.
                active?.let { gatt ->
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                }
                active = null
            }
        }
}
