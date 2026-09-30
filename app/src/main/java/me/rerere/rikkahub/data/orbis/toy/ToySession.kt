package me.rerere.rikkahub.data.orbis.toy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exact supplied HTML mapping. set is continuous; stop is a separate command. */
internal object ToyProtocol {
    const val REPEAT_MS = 1400L
    fun intensityByte(intensity: Int): Int {
        require(intensity in 0..100) { "强度必须为 0–100" }
        return when { intensity <= 0 -> 0; intensity <= 20 -> 50; intensity <= 40 -> 100
            intensity <= 60 -> 160; intensity <= 80 -> 210; else -> 255 }
    }
    fun level(intensity: Int): Int = when { intensity <= 0 -> 0; else -> (intensity + 19) / 20 }
    val stop: ByteArray get() = byteArrayOf(0x55, 0x04, 0, 0, 0, 0, 0xAA.toByte())
    fun set(intensity: Int): ByteArray = byteArrayOf(0x55, 4, 0, 0, 1, intensityByte(intensity).toByte(), 0xAA.toByte())
}

data class ToyState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val deviceNames: List<String> = emptyList(),
    val level: Int = 0,
    val requestedIntensity: Int? = null,
    val busy: Boolean = false,
    val connectionRevision: Long = 0,
    val message: String = "尚未连接。请在设备页手动选择自己的设备。",
    val physicalStopUncertain: Boolean = false,
)

internal interface ToyTransport {
    suspend fun write(packet: ByteArray)
    fun close()
}

/** Original multi-device continuous mode, with explicit results instead of swallowed BLE errors. */
internal class ToySession(private val scope: CoroutineScope, private val writeTimeoutMs: Long = 5000) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ToyState())
    val state = mutableState.asStateFlow()
    private val links = linkedMapOf<ToyTransport, String>()
    private var repeatJob: Job? = null

    suspend fun attach(selectedName: String, selectedTransport: ToyTransport) = mutex.withLock {
        links[selectedTransport] = selectedName
        updateConnections()
        mutableState.value = mutableState.value.copy(message = if (state.value.requestedIntensity == null)
            "已连接；尚未设置强度。" else "已连接，将跟随当前持续强度。")
        ensureRepeater()
    }

    suspend fun set(intensity: Int, isStillAllowed: () -> Boolean = { true }): ToyState {
        if (intensity == 0) return stop()
        return mutex.withLock {
        val packet = ToyProtocol.set(intensity)
        check(isStillAllowed()) { "设备连接或停止请求已变化，本次旧指令未执行。" }
        check(links.isNotEmpty()) { "请本人先选择并连接自己的蓝牙设备。" }
        withContext(NonCancellable) {
            mutableState.value = mutableState.value.copy(requestedIntensity = intensity,
                level = ToyProtocol.level(intensity), busy = true)
            val failures = writeAll(packet)
            mutableState.value = mutableState.value.copy(busy = false,
                message = if (failures == 0) "已设置强度 $intensity；按原版每 1.4 秒重发，直到停止。" else mutableState.value.message)
            ensureRepeater()
        }
        mutableState.value
        }
    }

    suspend fun stop(disconnect: Boolean = false, reason: String = "已向当前连接设备发送停止指令。") = mutex.withLock {
        repeatJob?.cancel(); repeatJob = null
        withContext(NonCancellable) {
            mutableState.value = mutableState.value.copy(requestedIntensity = null, level = 0, busy = true)
            val failures = writeAll(ToyProtocol.stop)
            if (disconnect) {
                links.keys.toList().forEach { it.close() }; links.clear(); updateConnections()
            }
            mutableState.value = mutableState.value.copy(busy = false,
                message = if (failures == 0) reason else mutableState.value.message)
        }
        mutableState.value
    }

    suspend fun linkLost(link: ToyTransport, message: String) = mutex.withLock {
        if (links.remove(link) == null) return@withLock
        link.close(); updateConnections()
        // Like the HTML, retain last command in memory. A manually added device follows it.
        mutableState.value = mutableState.value.copy(message = message, physicalStopUncertain = true)
    }

    private fun ensureRepeater() {
        if (state.value.requestedIntensity == null || repeatJob?.isActive == true) return
        repeatJob = scope.launch {
            while (isActive) {
                delay(ToyProtocol.REPEAT_MS)
                mutex.withLock {
                    val intensity = state.value.requestedIntensity ?: return@launch
                    if (links.isNotEmpty()) withContext(NonCancellable) { writeAll(ToyProtocol.set(intensity)) }
                }
            }
        }
    }

    private suspend fun writeAll(packet: ByteArray) = coroutineScope {
        val failures = links.keys.toList().map { link -> async {
            try { withTimeout(writeTimeoutMs) { link.write(packet.copyOf()) }; null }
            catch (_: Exception) { link }
        } }.awaitAll().filterNotNull()
        if (failures.isNotEmpty()) {
            failures.forEach { links.remove(it); it.close() }
            updateConnections()
            mutableState.value = mutableState.value.copy(physicalStopUncertain = true,
                message = "有设备写入失败或连接中断；其物理状态未知，请检查设备。其它连接不受影响。")
        }
        failures.size
    }

    private fun updateConnections() {
        val names = links.values.toList()
        mutableState.value = mutableState.value.copy(connected = names.isNotEmpty(), deviceNames = names,
            deviceName = names.takeIf { it.isNotEmpty() }?.joinToString("、"),
            connectionRevision = mutableState.value.connectionRevision + 1)
    }
}
