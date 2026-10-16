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
    /** Keep the tested wire bytes, but never expose the legacy 0–100 buckets as a continuous scale. */
    fun legacyIntensityForLevel(level: Int): Int {
        require(level in 0..5) { "档位必须为整数 0–5；0 停止，1–5 为五个离散档位，不是频率。" }
        return level * 20
    }
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
    val profile: ToyProfile = ToyProfile.SVAKOM,
    val requestedYutuStrength: Int? = null,
    val durationSeconds: Int? = null,
)

internal interface ToyTransport {
    val profile: ToyProfile get() = ToyProfile.SVAKOM
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
    private var deadlineJob: Job? = null
    private var commandRevision = 0L

    suspend fun attach(selectedName: String, selectedTransport: ToyTransport) = mutex.withLock {
        check(links.isEmpty() || state.value.profile == selectedTransport.profile) {
            "当前连接的是另一种协议，请先停止并断开，再选择新设备。"
        }
        check(selectedTransport.profile != ToyProfile.YUTU_V2 || links.isEmpty()) {
            "YUTU V2 当前只接受一台手动选择的设备。"
        }
        if (state.value.profile != selectedTransport.profile || selectedTransport.profile == ToyProfile.YUTU_V2) {
            repeatJob?.cancel(); repeatJob = null
            deadlineJob?.cancel(); deadlineJob = null
            commandRevision++
            mutableState.value = state.value.copy(profile = selectedTransport.profile,
                requestedIntensity = null, requestedYutuStrength = null, durationSeconds = null, level = 0)
        }
        links[selectedTransport] = selectedName
        updateConnections()
        if (selectedTransport.profile == ToyProfile.YUTU_V2) {
            mutableState.value = state.value.copy(message = "YUTU V2 已连接，尚未运行。目标值可选 1、2、3、5、10、20、50、100；时长可选 5、10、20、30、40、50 分钟或 1 小时。停止会断开，需本人手动重连。")
            return@withLock
        }
        mutableState.value = mutableState.value.copy(message = if (state.value.requestedIntensity == null)
            "已连接；尚未设置档位。支持 1–5 档，0 停止。" else "已连接，将跟随当前 ${state.value.level} 档。")
        ensureRepeater()
    }

    suspend fun setLevel(level: Int, isStillAllowed: () -> Boolean = { true }): ToyState =
        set(ToyProtocol.legacyIntensityForLevel(level), isStillAllowed)

    suspend fun set(intensity: Int, isStillAllowed: () -> Boolean = { true }): ToyState {
        if (intensity == 0) return stop()
        return mutex.withLock {
        check(state.value.profile == ToyProfile.SVAKOM) { YutuControlContract.GUIDE }
        val packet = ToyProtocol.set(intensity)
        check(isStillAllowed()) { "设备连接或停止请求已变化，本次旧指令未执行。" }
        check(links.isNotEmpty()) { "请本人先选择并连接自己的蓝牙设备。" }
        withContext(NonCancellable) {
            val nextLevel = ToyProtocol.level(intensity)
            val unchanged = state.value.requestedIntensity != null && state.value.level == nextLevel
            mutableState.value = mutableState.value.copy(requestedIntensity = intensity,
                level = nextLevel, busy = true)
            val failures = writeAll(packet)
            mutableState.value = mutableState.value.copy(busy = false,
                message = if (failures == 0) (if (unchanged) "仍为 $nextLevel 档，档位未变化。" else "已发送 $nextLevel 档指令。") +
                    "每 1.4 秒重发，直到停止；未验证物理状态。" else mutableState.value.message)
            ensureRepeater()
        }
        mutableState.value
        }
    }

    suspend fun setYutu(strength: Int, durationSeconds: Int, isStillAllowed: () -> Boolean = { true }): ToyState {
        YutuControlContract.validate(strength, durationSeconds)
        return mutex.withLock {
            check(state.value.profile == ToyProfile.YUTU_V2 && links.isNotEmpty()) { "请本人先连接 YUTU V2。" }
            check(isStillAllowed()) { "设备连接或停止请求已变化，本次旧指令未执行。" }
            withContext(NonCancellable) {
                deadlineJob?.cancel()
                val revision = ++commandRevision
                mutableState.value = state.value.copy(requestedYutuStrength = strength,
                    durationSeconds = durationSeconds, busy = true)
                deadlineJob = scope.launch {
                    delay(durationSeconds * 1000L)
                    mutex.withLock {
                        if (revision == commandRevision) stopLocked(reason = "YUTU 本次时长结束，已请求断开；请确认设备已停止。")
                    }
                }
                val failures = writeAll(YutuControlContract.packet(strength))
                mutableState.value = state.value.copy(busy = false,
                    message = if (failures == 0) "已发送 YUTU 目标值 $strength，本次时长 ${YutuControlContract.durationLabel(durationSeconds)}，到时请求断开，可随时停止。${YutuControlContract.STRENGTH_GUIDE}未验证物理状态。" else state.value.message)
                ensureRepeater()
            }
            state.value
        }
    }

    suspend fun stop(disconnect: Boolean = false, reason: String = "已向当前连接设备发送停止指令。") = mutex.withLock {
        stopLocked(disconnect, reason)
    }

    private suspend fun stopLocked(disconnect: Boolean = false, reason: String): ToyState {
        commandRevision++
        repeatJob?.cancel(); repeatJob = null
        deadlineJob?.cancel(); deadlineJob = null
        withContext(NonCancellable) {
            if (state.value.profile == ToyProfile.YUTU_V2) {
                // Do not send zero or the legacy stop packet: neither is a YUTU stop command.
                links.keys.toList().forEach { it.close() }
                links.clear(); updateConnections()
                mutableState.value = state.value.copy(requestedYutuStrength = null, durationSeconds = null,
                    requestedIntensity = null, level = 0, busy = false, physicalStopUncertain = true,
                    message = (if (reason.startsWith("YUTU 本次时长")) "YUTU 本次时长结束，已请求断开停止。" else "YUTU 已请求断开停止。") +
                        "实测断连会停止，但本次物理状态仍需本人确认。再次使用请手动选择连接，不会自动重连。")
                return@withContext
            }
            mutableState.value = mutableState.value.copy(requestedIntensity = null, level = 0, busy = true)
            val failures = writeAll(ToyProtocol.stop)
            if (disconnect) {
                links.keys.toList().forEach { it.close() }; links.clear(); updateConnections()
            }
            mutableState.value = mutableState.value.copy(busy = false,
                message = if (failures == 0) reason else mutableState.value.message)
        }
        return mutableState.value
    }

    suspend fun linkLost(link: ToyTransport, message: String) = mutex.withLock {
        if (links.remove(link) == null) return@withLock
        link.close(); updateConnections()
        clearYutuIfDisconnected()
        // Only the legacy adapter retains the last command. YUTU never replays on reconnection.
        mutableState.value = mutableState.value.copy(message = message, physicalStopUncertain = true)
    }

    private fun ensureRepeater() {
        if ((state.value.requestedIntensity == null && state.value.requestedYutuStrength == null) || repeatJob?.isActive == true) return
        repeatJob = scope.launch {
            while (isActive) {
                delay(if (state.value.profile == ToyProfile.YUTU_V2) YutuControlContract.REPEAT_MS else ToyProtocol.REPEAT_MS)
                mutex.withLock {
                    val packet = if (state.value.profile == ToyProfile.YUTU_V2) {
                        YutuControlContract.packet(state.value.requestedYutuStrength ?: return@launch)
                    } else ToyProtocol.set(state.value.requestedIntensity ?: return@launch)
                    if (links.isNotEmpty()) withContext(NonCancellable) { writeAll(packet) }
                }
            }
        }
    }

    private suspend fun writeAll(packet: ByteArray) = coroutineScope {
        val failures = links.keys.toList().map { link -> async {
            try { withTimeout(if (link.profile == ToyProfile.YUTU_V2) minOf(writeTimeoutMs, 1000L) else writeTimeoutMs) { link.write(packet.copyOf()) }; null }
            catch (_: Exception) { link }
        } }.awaitAll().filterNotNull()
        if (failures.isNotEmpty()) {
            failures.forEach { links.remove(it); it.close() }
            updateConnections()
            clearYutuIfDisconnected()
            mutableState.value = mutableState.value.copy(physicalStopUncertain = true,
                message = "有设备写入失败或连接中断；其物理状态未知，请检查设备。其它连接不受影响。")
        }
        failures.size
    }

    private fun clearYutuIfDisconnected() {
        if (state.value.profile != ToyProfile.YUTU_V2 || links.isNotEmpty()) return
        commandRevision++
        repeatJob?.cancel(); repeatJob = null
        deadlineJob?.cancel(); deadlineJob = null
        mutableState.value = state.value.copy(requestedYutuStrength = null, durationSeconds = null, busy = false)
    }

    private fun updateConnections() {
        val names = links.values.toList()
        mutableState.value = mutableState.value.copy(connected = names.isNotEmpty(), deviceNames = names,
            deviceName = names.takeIf { it.isNotEmpty() }?.joinToString("、"),
            connectionRevision = mutableState.value.connectionRevision + 1)
    }
}
