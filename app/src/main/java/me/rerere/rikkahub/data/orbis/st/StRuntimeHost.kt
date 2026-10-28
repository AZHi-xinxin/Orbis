package me.rerere.rikkahub.data.orbis.st

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.rikkahub.service.OrbisStRuntimeService
import me.rerere.workspace.WorkspaceRuntimeLease
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
internal data class StRuntimeSaved(val enabled: Boolean = false, val owner: String = "", val spec: StRuntimeSpec? = null,
    // Old registrations did not distinguish an explicit stop from process death. Do not guess.
    val resumeOnAppOpen: Boolean = false)

data class StRuntimeState(val phase: String = "stopped", val code: String? = null,
    val gatewayPort: Int? = null, val mcpPort: Int? = null, val checkedAt: Long? = null) {
    val active: Boolean get() = phase in setOf("starting", "ready", "unhealthy", "stopping")
}

/** Separate from chat jobs. Explicit starts persist intent; foreground app entry can restore it. */
class StRuntimeHost private constructor(private val context: Context) {
    private val mutableStates = MutableStateFlow<Map<String, StRuntimeState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val runs = mutableMapOf<String, Run>()
    private val recoveryAttempts = mutableSetOf<String>()
    private val workers = Executors.newCachedThreadPool { Thread(it, "orbis-st-host").apply { isDaemon = true } }
    private val json = Json { ignoreUnknownKeys = true }
    private data class Run(val owner: String, val spec: StRuntimeSpec, val lease: Closeable,
        var process: Process? = null, var cancelled: Boolean = false)

    private fun file(root: String): AtomicFile {
        require(root.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "st_workspace_invalid" }
        return AtomicFile(File(context.noBackupFilesDir, "st-host/$root.json"))
    }
    @Synchronized internal fun saved(root: String): StRuntimeSaved = runCatching {
        json.decodeFromString<StRuntimeSaved>(file(root).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrDefault(StRuntimeSaved())
    private fun save(root: String, value: StRuntimeSaved) {
        val target = file(root); target.baseFile.parentFile!!.mkdirs()
        val stream = target.startWrite()
        try { stream.write(json.encodeToString(value).toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    @Synchronized fun enabled(root: String): Boolean = saved(root).enabled
    @Synchronized fun setEnabled(root: String, enabled: Boolean) {
        save(root, saved(root).copy(enabled = enabled))
        if (!enabled) stop(root)
        publish(root, mutableStates.value[root] ?: StRuntimeState())
    }
    @Synchronized fun current(root: String): StRuntimeState = mutableStates.value[root] ?: StRuntimeState()
    @Synchronized fun hasRuns(): Boolean = runs.isNotEmpty()
    @Synchronized private fun publish(root: String, value: StRuntimeState) { mutableStates.value = mutableStates.value + (root to value) }

    @Synchronized fun requestStart(root: String, owner: String, spec: StRuntimeSpec): StRuntimeState {
        check(enabled(root)) { "st_host_disabled" }
        spec.validate()
        runs[root]?.let {
            check(it.owner == owner && it.spec == spec) { "st_host_busy" }
            return current(root)
        }
        val base = File(File(context.filesDir, "workspaces"), root)
        check(File(base, "linux/bin/sh").isFile) { "st_rootfs_missing" }
        val run = Run(owner, spec, WorkspaceRuntimeLease.acquire(root))
        runs[root] = run
        try {
            save(root, StRuntimeSaved(true, owner, spec, resumeOnAppOpen = true))
            publish(root, StRuntimeState("starting"))
            ContextCompat.startForegroundService(context, Intent(context, OrbisStRuntimeService::class.java).putExtra("root", root))
            workers.execute {
                Thread.sleep(10_000)
                synchronized(this) {
                    if (runs[root] === run && run.process == null && !run.cancelled) failPending(root, "st_foreground_unavailable")
                }
            }
        } catch (_: Exception) { failPending(root, "st_foreground_unavailable") }
        return current(root)
    }

    @Synchronized fun restartSaved(root: String): StRuntimeState {
        val previous = saved(root)
        return requestStart(root, previous.owner, previous.spec ?: error("st_configuration_missing"))
    }

    /** Called only from a visible, non-rescue Activity, after settings and workspace DB load.
     * At most one recovery attempt per root/process; no crash loop, install or old tool replay.
     * Binding must still belong to the assistant that registered this particular runtime.
     */
    @Synchronized fun restoreOnAppOpen(ownersByRoot: Map<String, Set<String>>) {
        ownersByRoot.forEach { (root, owners) ->
            val previous = saved(root)
            if (!previous.enabled || !previous.resumeOnAppOpen || previous.spec == null ||
                previous.owner !in owners || root in runs || !recoveryAttempts.add(root)) return@forEach
            try { requestStart(root, previous.owner, previous.spec) }
            catch (_: Exception) { publish(root, StRuntimeState("failed", "st_restore_unavailable")) }
        }
    }

    @Synchronized fun launchPending(root: String) {
        val run = runs[root] ?: return
        if (run.process != null) return
        if (run.cancelled || !enabled(root)) { finish(root, run, StRuntimeState()); return }
        try {
            val base = File(File(context.filesDir, "workspaces"), root)
            val temp = File(base, "tmp/st-host").apply { mkdirs() }
            val wrapper = File(temp, "host.py")
            context.assets.open("orbis-st-host.py").use { input -> wrapper.outputStream().use { input.copyTo(it) } }
            val native = File(context.applicationInfo.nativeLibraryDir)
            val process = ProcessBuilder(stRuntimeCommand(native, base, wrapper, run.spec))
                .directory(File(base, "files")).apply {
                    environment().clear()
                    environment()["PROOT_LOADER"] = File(native, "libproot_loader.so").path
                    environment()["PROOT_TMP_DIR"] = temp.path
                    environment()["TMPDIR"] = temp.path
                }.start()
            run.process = process
            workers.execute { runCatching { process.errorStream.use { input -> val buf = ByteArray(4096); while (input.read(buf) >= 0) { /* Never retain raw private output. */ } } } }
            workers.execute { observe(root, run, process) }
        } catch (_: Exception) { finish(root, run, StRuntimeState("failed", "st_process_start_failed")) }
    }

    private fun observe(root: String, run: Run, process: Process) {
        try {
            // Bound each frame even if an installed runtime emits an unbounded line.
            process.inputStream.use { input ->
                val line = StringBuilder(); var oversized = false
                while (true) {
                    val b = input.read(); if (b < 0) break
                    if (b == 10) {
                        if (!oversized) runCatching { acceptFrame(root, run, line.toString()) }
                        line.setLength(0); oversized = false
                    } else if (line.length < 1024) line.append(b.toChar()) else oversized = true
                }
            }
            process.waitFor()
        } catch (_: Exception) {
            // Losing the observation pipe must not release the workspace while the child still lives.
            runCatching { process.outputStream.close() }
            runCatching { if (!process.waitFor(35, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor() } }
        }
        synchronized(this) {
            val old = current(root)
            finish(root, run, if (run.cancelled) StRuntimeState() else
                StRuntimeState("failed", old.code ?: "st_process_exited"))
        }
    }
    @Synchronized private fun acceptFrame(root: String, run: Run, line: String) {
        if (runs[root] !== run || run.cancelled) return
        val data = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val phase = data["state"]?.jsonPrimitive?.contentOrNull ?: return
        if (phase !in setOf("starting", "ready", "unhealthy", "failed")) return
        val allowedCodes = setOf("st_stack_exited", "st_health_unavailable", "st_startup_timeout", "st_start_failed")
        val code = data["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it in allowedCodes }
        val gateway = data["gateway_port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1024..65535 }
        val mcp = data["mcp_port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1024..65535 }
        if (phase == "ready" && (gateway == null || mcp == null || gateway == mcp)) return
        publish(root, StRuntimeState(phase, code, gateway, mcp, if (phase == "ready") System.currentTimeMillis() else null))
    }
    @Synchronized fun failPending(root: String, code: String) {
        val run = runs[root] ?: return
        if (run.process == null) finish(root, run, StRuntimeState("failed", code)) else stop(root, forgetRunning = false)
    }
    @Synchronized fun stop(root: String, forgetRunning: Boolean = true) {
        // Persist even when the Android process already died and there is no in-memory Run.
        if (forgetRunning) save(root, saved(root).copy(resumeOnAppOpen = false))
        val run = runs[root] ?: return
        if (run.cancelled) return
        run.cancelled = true
        publish(root, StRuntimeState("stopping"))
        val process = run.process
        if (process == null) { finish(root, run, StRuntimeState()); return }
        workers.execute { runCatching {
            runCatching { process.outputStream.close() } // EOF -> owned stack graceful stop.
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } }
    }
    @Synchronized fun stopAll(forgetRunning: Boolean = true) {
        val roots = runs.keys.toMutableSet()
        if (forgetRunning) File(context.noBackupFilesDir, "st-host").listFiles()?.forEach {
            val name = it.name.removeSuffix(".bak")
            if (name.endsWith(".json")) roots.add(name.removeSuffix(".json"))
        }
        roots.filter { it.matches(Regex("[a-zA-Z0-9_-]{1,100}")) }.forEach { stop(it, forgetRunning) }
    }
    @Synchronized private fun finish(root: String, run: Run, state: StRuntimeState) {
        if (runs[root] !== run) return
        runs.remove(root); run.lease.close(); publish(root, state)
    }
    companion object {
        @Volatile private var instance: StRuntimeHost? = null
        fun get(context: Context): StRuntimeHost = instance ?: synchronized(this) {
            instance ?: StRuntimeHost(context.applicationContext).also { instance = it }
        }
    }
}
