package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object OrbisPhoneLimits {
    const val DURATION_MS = 10 * 60 * 1000L
    const val INTERVAL_MS = 30 * 1000L
    const val MAX_HISTORY = 10
    const val MAX_ARCHIVE_BYTES = 32 * 1024
    const val MAX_SAMPLES = 20
}

/** Only these OS facts are sampled. No identifiers, locations, app history or notifications. */
@Serializable
data class OrbisDeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val usageAccess: String = "unavailable",
) {
    fun isValid(): Boolean = listOf(manufacturer, model, androidRelease).all {
        it.length in 1..80 && it.none { char -> Character.isISOControl(char) }
    } && apiLevel in 1..100 && (batteryPercent == null || batteryPercent in 0..100) &&
        usageAccess in setOf("granted", "not_granted", "unavailable")
}

@Serializable
data class OrbisObservationSummary(
    val number: Long,
    val startedAtMs: Long,
    val status: String = "running",
    val elapsedMs: Long = 0,
    val samples: Int = 0,
    val changes: Int = 0,
)

@Serializable
data class OrbisObservationArchive(
    val version: Int = 1,
    val nextNumber: Long = 1,
    val active: OrbisObservationSummary? = null,
    val history: List<OrbisObservationSummary> = emptyList(),
)

data class OrbisObservationState(
    val active: OrbisObservationSummary? = null,
    val history: List<OrbisObservationSummary> = emptyList(),
    val storageBlocked: Boolean = false,
    val error: String? = null,
    val latestSample: OrbisDeviceSnapshot? = null,
)

interface OrbisObservationStorage {
    fun readArchive(): String?
    fun writeArchive(text: String)
}

/** Canonicalize only the trusted OS-provided app directory; own children must not be aliases. */
internal class OrbisObservationPaths(filesDir: File) {
    private val files = filesDir.canonicalFile
    val root = File(files, "orbis-phone-observation")
    val baseFile = File(root, "summaries.json")

    fun checkRoot(create: Boolean = false) {
        check(root.canonicalFile == root)
        if (create && !root.exists()) check(root.mkdirs())
        check(!root.exists() || root.isDirectory)
        for (name in listOf("summaries.json", "summaries.json.bak", "summaries.json.new")) {
            val path = File(root, name)
            check(path.canonicalFile == path.absoluteFile && (!path.exists() || path.isFile))
        }
    }
}

interface OrbisObservationClock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

class OrbisPhoneException(val code: String) : IllegalStateException(code)

/** Foreground permission is a process-local lease, never persisted or available to a tool. */
class OrbisObservationForeground {
    data class Lease(val owner: String, val generation: Long)
    @Volatile private var lease: Lease? = null
    private var generation = 0L
    @Synchronized fun enter(owner: String) {
        require(owner.isNotBlank())
        if (lease?.owner != owner) lease = Lease(owner, ++generation)
    }
    @Synchronized fun leave(owner: String) {
        if (lease?.owner == owner) lease = null
    }
    fun forOwner(owner: String): Lease? = lease?.takeIf { it.owner == owner }
    fun isCurrent(candidate: Lease): Boolean = lease == candidate
}

/** Pure bounded observer. All mutation methods run on a serial IO owner, not on the UI thread.
 * It has no model, message, network, grant, shell or scheduler API.
 */
class OrbisPhoneObservation(
    private val storage: OrbisObservationStorage,
    private val clock: OrbisObservationClock,
    private val sampler: () -> OrbisDeviceSnapshot,
    val foreground: OrbisObservationForeground = OrbisObservationForeground(),
) {
    private val json = Json { encodeDefaults = true }
    private var archive = OrbisObservationArchive()
    private var expectedArchiveText: String? = null
    private var blocked = false
    private var lastError: String? = null
    private var uncertainSummary: OrbisObservationSummary? = null
    private var latest: OrbisDeviceSnapshot? = null
    private var runLease: OrbisObservationForeground.Lease? = null
    @Volatile private var stopSignal: AtomicBoolean? = null
    private var startedElapsed = 0L
    private var lastElapsed = 0L
    private var nextSampleElapsed = 0L

    init { reload() }

    @Synchronized fun readState(): OrbisObservationState {
        val interruption = interruptionReason().takeIf { !blocked }
        val revoked = interruption != null
        return OrbisObservationState(
            active = archive.active.takeIf { !blocked && !revoked },
            history = (listOfNotNull(uncertainSummary,
                archive.active?.takeIf { revoked }?.copy(status = interruption!!)) + archive.history)
                .take(OrbisPhoneLimits.MAX_HISTORY),
            storageBlocked = blocked,
            error = lastError,
            latestSample = latest.takeIf { !blocked && !revoked },
        )
    }

    /** Load only; recovering an interrupted process never samples, runs, or writes. */
    @Synchronized fun reload(): OrbisObservationState {
        if (runLease != null && !blocked) throw OrbisPhoneException("phone_observation_running")
        try {
            val text = storage.readArchive()
            val loaded = if (text == null) OrbisObservationArchive() else {
                require(text.toByteArray(Charsets.UTF_8).size <= OrbisPhoneLimits.MAX_ARCHIVE_BYTES)
                json.decodeFromString<OrbisObservationArchive>(text).also(::validateArchive)
            }
            archive = loaded.copy(active = null, history = (
                listOfNotNull(loaded.active?.copy(status = "interrupted")) + loaded.history
            ).take(OrbisPhoneLimits.MAX_HISTORY))
            expectedArchiveText = text
            blocked = false; lastError = null; uncertainSummary = null
        } catch (_: Exception) {
            blocked = true; lastError = "phone_observation_storage_unavailable"
        }
        runLease = null; stopSignal = null; latest = null
        return readState()
    }

    @Synchronized fun start(owner: String): OrbisObservationState {
        if (blocked) throw OrbisPhoneException("phone_observation_storage_unavailable")
        if (archive.active != null) throw OrbisPhoneException("phone_observation_running")
        val lease = foreground.forOwner(owner) ?: throw OrbisPhoneException("phone_observation_foreground_required")
        val elapsed = clock.elapsedMs()
        if (elapsed < 0 || elapsed > Long.MAX_VALUE - OrbisPhoneLimits.DURATION_MS || archive.nextNumber >= 1_000_000_000L)
            throw OrbisPhoneException("phone_observation_unavailable")
        val summary = OrbisObservationSummary(archive.nextNumber, clock.wallMs().coerceAtLeast(0))
        stopSignal = AtomicBoolean(false)
        runLease = lease
        // Reserve/checkpoint before sampling. An uncertain write blocks future runs until reload.
        commit(archive.copy(nextNumber = archive.nextNumber + 1, active = summary))
        startedElapsed = elapsed; lastElapsed = elapsed; nextSampleElapsed = elapsed
        latest = null; runLease = lease
        return poll()
    }

    @Synchronized fun poll(expectedNumber: Long? = null): OrbisObservationState {
        if (blocked || archive.active == null) return readState()
        if (expectedNumber != null && archive.active?.number != expectedNumber) return readState()
        interruptionReason()?.let { return finish(it) }
        val now = clock.elapsedMs()
        if (now < lastElapsed) return finish("clock_error", lastElapsed)
        lastElapsed = now
        if (now - startedElapsed >= OrbisPhoneLimits.DURATION_MS) return finish("completed", now)
        if (now < nextSampleElapsed) return readState()
        val sample = try { sampler().also { require(it.isValid()) } }
        catch (error: java.util.concurrent.CancellationException) { finish("stopped", now); throw error }
        catch (_: Exception) { return finish("sample_error", now) }
        // A foreground revoke during an OS read invalidates the result, even if a new page resumed.
        interruptionReason()?.let { return finish(it) }
        val afterRead = clock.elapsedMs()
        if (afterRead < now) return finish("clock_error", now)
        if (afterRead - startedElapsed >= OrbisPhoneLimits.DURATION_MS) return finish("completed", afterRead)
        val current = archive.active ?: return readState()
        if (current.samples >= OrbisPhoneLimits.MAX_SAMPLES) return finish("completed", afterRead)
        val updated = current.copy(
            elapsedMs = (afterRead - startedElapsed).coerceIn(0, OrbisPhoneLimits.DURATION_MS),
            samples = current.samples + 1,
            changes = current.changes + if (latest != null && latest != sample) 1 else 0,
        )
        commit(archive.copy(active = updated))
        // A write already accepted before Stop may finish; no later read or run is allowed.
        interruptionReason()?.let { return finish(it) }
        latest = sample; lastElapsed = afterRead
        // No catch-up burst after a late tick.
        nextSampleElapsed = afterRead + OrbisPhoneLimits.INTERVAL_MS
        return readState()
    }

    /** Lock-free fence: can be called while the synchronized sampler or AtomicFile write is busy. */
    fun requestStop() { stopSignal?.set(true) }

    @Synchronized fun stop(): OrbisObservationState {
        requestStop()
        return finish("stopped")
    }

    private fun interruptionReason(): String? = when {
        stopSignal?.get() == true -> "stopped"
        runLease?.let { !foreground.isCurrent(it) } == true -> "background_paused"
        else -> null
    }

    /** Called after cancel/foreground loss, not on resume: it can only finish, never continue. */
    @Synchronized fun pauseIfNotForeground(): OrbisObservationState {
        return interruptionReason()?.let { finish(it) } ?: readState()
    }

    private fun finish(status: String, elapsed: Long = clock.elapsedMs()): OrbisObservationState {
        val active = archive.active ?: return readState()
        if (blocked) return readState()
        val summary = active.copy(status = status,
            elapsedMs = (elapsed - startedElapsed).coerceIn(0, OrbisPhoneLimits.DURATION_MS))
        commit(archive.copy(active = null, history = (listOf(summary) + archive.history).take(OrbisPhoneLimits.MAX_HISTORY)))
        runLease = null; stopSignal = null; latest = null
        return readState()
    }

    private fun commit(candidate: OrbisObservationArchive) {
        try {
            validateArchive(candidate)
            val text = json.encodeToString(candidate)
            check(storage.readArchive() == expectedArchiveText)
            storage.writeArchive(text)
            check(storage.readArchive() == text)
            archive = candidate
            expectedArchiveText = text
        } catch (_: Exception) {
            uncertainSummary = (candidate.active ?: candidate.history.firstOrNull())?.copy(status = "storage_error")
            blocked = true; lastError = "phone_observation_storage_unavailable"; runLease = null; stopSignal = null; latest = null
            throw OrbisPhoneException("phone_observation_storage_unavailable")
        }
    }

    companion object {
        private val terminalStatuses = setOf("completed", "stopped", "background_paused", "interrupted", "sample_error", "clock_error", "storage_error")
        fun validateArchive(value: OrbisObservationArchive) {
            require(value.version == 1 && value.nextNumber in 1..1_000_000_000L)
            require(value.history.size <= OrbisPhoneLimits.MAX_HISTORY)
            require(value.active == null || value.active.status == "running")
            require(value.history.all { it.status in terminalStatuses })
            val entries = listOfNotNull(value.active) + value.history
            require(entries.map { it.number }.distinct().size == entries.size)
            require(entries.zipWithNext().all { (a, b) -> a.number > b.number })
            entries.forEach {
                require(it.number in 1 until value.nextNumber && it.startedAtMs >= 0)
                require(it.elapsedMs in 0..OrbisPhoneLimits.DURATION_MS && it.samples in 0..OrbisPhoneLimits.MAX_SAMPLES)
                require(it.changes in 0..maxOf(0, it.samples - 1))
            }
        }
    }
}
