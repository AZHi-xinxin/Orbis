package me.rerere.rikkahub.data.recovery

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import me.rerere.rikkahub.utils.EmergencyProcessGate
import java.io.File

/** No database, settings, DI, network or assistant code is allowed on this path. */
internal object EmergencyAndroidRuntime {
    const val PROCESS_SUFFIX = ":recovery"
    fun directory(context: Context) = File(context.applicationInfo.dataDir, "orbis-emergency").canonicalFile
    fun gate(context: Context) = EmergencyProcessGate(File(directory(context), "control"))
    fun isRecoveryProcess(): Boolean = processName().endsWith(PROCESS_SUFFIX)

    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        File("/proc/self/cmdline").inputStream().use { input ->
            val bytes = ByteArray(512)
            val count = input.read(bytes)
            check(count > 0) { "Cannot identify application process" }
            bytes.copyOf(count).toString(Charsets.UTF_8).substringBefore('\u0000')
        }
    }

    private data class OwnedProcess(val pid: Int, val parent: Int, val start: String, val name: String)

    private fun ownedProcesses(context: Context): List<OwnedProcess> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val androidPids = checkNotNull(manager.runningAppProcesses) { "无法取得本应用的后台进程状态。" }.filter {
            it.uid == Process.myUid() && it.pid != Process.myPid()
        }.mapTo(mutableSetOf()) { it.pid }
        val entries = checkNotNull(File("/proc").listFiles()) { "无法检查后台写入进程，请先在系统设置中强行停止 Orbis。" }
        return (entries.toList() + androidPids.map { File("/proc/$it") }).distinctBy { it.name }.mapNotNull { folder ->
            val pid = folder.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == Process.myPid()) return@mapNotNull null
            // /proc directory ownership may become root for a non-dumpable release process.
            // Uid in status is authoritative, not the directory's uid.
            val status = try {
                File(folder, "status").readLines().first { it.startsWith("Uid:") }
                    .removePrefix("Uid:").trim().split(Regex("\\s+"))
            } catch (error: Exception) {
                val uid = try { Os.stat(folder.path).st_uid } catch (_: Exception) {
                    if (pid in androidPids && folder.exists()) throw IllegalStateException("无法检查 Orbis 后台进程。", error)
                    return@mapNotNull null
                }
                if ((uid == Process.myUid() || pid in androidPids) && folder.exists()) throw IllegalStateException("无法检查 Orbis 后台进程。", error)
                return@mapNotNull null
            }
            if (status.take(2).none { it.toInt() == Process.myUid() }) return@mapNotNull null
            try {
                val stat = File(folder, "stat").readText().substringAfterLast(") ").split(' ')
                // Zombies cannot retain writable file descriptors.
                if (stat[0] == "Z") return@mapNotNull null
                check(status.take(2).all { it.toInt() == Process.myUid() })
                val name = File(folder, "cmdline").inputStream().use { input ->
                    val bytes = ByteArray(4096)
                    val count = input.read(bytes)
                    check(count > 0)
                    bytes.copyOf(count).toString(Charsets.UTF_8).substringBefore('\u0000')
                }
                OwnedProcess(pid, stat[1].toInt(), stat[19], name)
            } catch (error: Exception) {
                if (!folder.exists()) null else throw IllegalStateException(
                    "无法确认 Orbis 后台进程已停止。请先在系统设置中强行停止 Orbis，再打开“Orbis 紧急备份”。", error)
            }
        }
    }

    /** Must ONLY be called after an explicit human confirmation that calls/generation will stop. */
    fun pauseBusiness(context: Context): EmergencyProcessGate.Lease {
        val gate = gate(context)
        check(gate.requestRecovery()) { "无法写入救援保护标记；没有开始导出，请检查剩余空间。" }
        repeat(8) {
            val owned = ownedProcesses(context)
            val recognized = owned.filter { it.name == context.packageName || it.name.startsWith(context.packageName + ":") }
                .mapTo(mutableSetOf()) { it.pid }
            // Claim only descendants of positively identified app processes, never unrelated same-UID processes.
            var grew: Boolean
            do { grew = owned.filter { it.parent in recognized }.any { recognized.add(it.pid) } } while (grew)
            check(owned.all { it.pid in recognized }) {
                "仍有无法安全认领的工作区子进程。请在系统设置中强行停止 Orbis，然后从“Orbis 紧急备份”图标重新进入。原文件未改动。"
            }
            for (target in owned.asReversed()) {
                val current = ownedProcesses(context).firstOrNull { it.pid == target.pid } ?: continue
                if (current.start == target.start && current.name == target.name) Process.killProcess(target.pid)
            }
            if (ownedProcesses(context).isEmpty()) {
                gate.tryAcquireRecoveryLease()?.let { lease ->
                    if (ownedProcesses(context).isEmpty()) return lease
                    lease.close()
                }
            }
            Thread.sleep(100)
        }
        error("后台写入尚未完全停止，没有开始复制。请在系统设置中强行停止 Orbis，再从紧急备份图标进入。")
    }

    fun checkPaused(context: Context) {
        check(ownedProcesses(context).isEmpty()) { "备份期间发现后台进程，未确认备份成功；原文件仍保留。" }
    }
}
