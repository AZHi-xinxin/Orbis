package me.rerere.workspace

import java.io.Closeable

/** Serializes managed runtimes with destructive workspace operations, not normal short commands. */
object WorkspaceRuntimeLease {
    private val holders = mutableMapOf<String, Any>()
    private val runtimes = mutableMapOf<String, MutableMap<String, Any>>()
    @Synchronized fun acquireRuntime(root: String, service: String): Closeable {
        check(root !in holders && runtimes[root]?.containsKey(service) != true) { "工作区环境操作或此服务仍在运行" }
        val ticket = Any()
        runtimes.getOrPut(root) { mutableMapOf() }[service] = ticket
        return Closeable { synchronized(this) {
            if (runtimes[root]?.get(service) === ticket) {
                runtimes[root]?.remove(service)
                if (runtimes[root]?.isEmpty() == true) runtimes.remove(root)
            }
        } }
    }

    @Synchronized fun acquire(root: String): Closeable {
        check(root !in holders && runtimes[root].isNullOrEmpty()) { "请先停止此工作区的记忆服务托管，或等待当前环境操作完成。" }
        val ticket = Any()
        holders[root] = ticket
        return Closeable { synchronized(this) { if (holders[root] === ticket) holders.remove(root) } }
    }
}
