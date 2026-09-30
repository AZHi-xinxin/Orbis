package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.io.File

/** No usage query, telephony ID, location, notification contents, network or permission request. */
class AndroidDeviceReader(context: Context) {
    private val app = context.applicationContext
    fun read(): OrbisDeviceSnapshot {
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (scale > 0 && level in 0..scale) ((level.toLong() * 100) / scale).toInt() else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val usage = try { if (app.hasUsageStatsPermission()) "granted" else "not_granted" }
            catch (_: Exception) { "unavailable" }
        return OrbisDeviceSnapshot(safe(Build.MANUFACTURER), safe(Build.MODEL), safe(Build.VERSION.RELEASE),
            Build.VERSION.SDK_INT, percent, charging, usage)
    }
    private fun safe(value: String?): String = value.orEmpty().filter {
        !Character.isISOControl(it) && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069'
    }.trim().take(80).ifBlank { "unknown" }
}

/** Private summary archive only. Samples and capability state are never persisted. */
internal class AndroidObservationStorage(context: Context) : OrbisObservationStorage {
    private val paths = OrbisObservationPaths(context.applicationContext.filesDir)
    private val root = paths.root
    private val file = AtomicFile(paths.baseFile)
    override fun readArchive(): String? {
        paths.checkRoot()
        if (!file.baseFile.exists() && !File(root, "summaries.json.bak").exists()) {
            check(!File(root, "summaries.json.new").exists())
            return null
        }
        return file.openRead().use { stream ->
            val bytes = stream.readBytesBounded()
            val text = bytes.toString(Charsets.UTF_8)
            check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            text
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= OrbisPhoneLimits.MAX_ARCHIVE_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    override fun writeArchive(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= OrbisPhoneLimits.MAX_ARCHIVE_BYTES)
        paths.checkRoot(create = true)
        val stream = file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch (error: Throwable) {
            try { file.failWrite(stream) } catch (_: Throwable) { /* Core blocks further writes. */ }
            throw error
        }
    }
}

/** Page-visible lifetime only. No service, boot receiver, alarm, WorkManager, or model dependency. */
class AndroidPhoneObservation internal constructor(context: Context) {
    private val reader = AndroidDeviceReader(context)
    private val core = OrbisPhoneObservation(AndroidObservationStorage(context), object : OrbisObservationClock {
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun wallMs() = System.currentTimeMillis()
    }, reader::read)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(core.readState())
    private val mutableDevice = MutableStateFlow(reader.read())
    val state = mutableState.asStateFlow()
    val device = mutableDevice.asStateFlow()
    @Volatile private var ticker: Job? = null
    private val actions = Mutex()

    fun enterForeground(owner: String) {
        core.foreground.enter(owner)
        scope.launch { actions.withLock { safely { core.pauseIfNotForeground() }; refreshDevice() } }
    }
    fun leaveForeground(owner: String) {
        if (core.foreground.forOwner(owner) == null) return
        // Revoke synchronously before waiting for IO/cancellation cleanup.
        core.foreground.leave(owner)
        scope.launch { actions.withLock {
            safely { core.pauseIfNotForeground() }
            if (core.readState().active == null) { ticker?.cancelAndJoin(); ticker = null }
        } }
    }
    suspend fun start(owner: String) = withContext(Dispatchers.IO) {
        actions.withLock {
        try {
            val current = core.start(owner)
            publish()
            val number = current.active?.number
            if (number != null) {
                ticker?.cancelAndJoin()
                ticker = scope.launch {
                    try {
                        while (isActive) {
                            safely { core.poll(number) }
                            if (mutableState.value.active?.number != number) break
                            delay(1_000) // Deadline/lifecycle checks; OS sampling is still every 30s.
                        }
                    } finally {
                        withContext(NonCancellable) { safely { core.pauseIfNotForeground() } }
                    }
                }
            }
        } finally { publish() }
        }
    }
    suspend fun stop() {
        core.requestStop() // Fence immediately, before waiting for IO or the prior start action.
        withContext(NonCancellable + Dispatchers.IO) { actions.withLock {
            ticker?.cancelAndJoin()
            ticker = null
            safely { core.stop() }
        } }
    }
    suspend fun reload() = withContext(Dispatchers.IO) { actions.withLock { safely { core.reload() }; refreshDevice() } }
    suspend fun refreshDevice() = withContext(Dispatchers.IO) { mutableDevice.value = reader.read() }
    suspend fun readDevice(): OrbisDeviceSnapshot = withContext(Dispatchers.IO) { reader.read() }
    suspend fun readState(): OrbisObservationState = withContext(Dispatchers.IO) { core.readState() }
    private fun safely(block: () -> OrbisObservationState) {
        try { block(); publish() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { publish() }
    }
    @Synchronized private fun publish() {
        // Publish a fresh core snapshot, not an older result returned before another action finished.
        val next = core.readState()
        if (next.active?.samples != mutableState.value.active?.samples)
            next.latestSample?.let { mutableDevice.value = it }
        mutableState.value = next
    }
}

object OrbisPhones {
    @Volatile private var repository: AndroidPhoneObservation? = null
    @Synchronized fun open(context: Context): AndroidPhoneObservation =
        repository ?: AndroidPhoneObservation(context.applicationContext).also { repository = it }
}
