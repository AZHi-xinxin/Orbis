package me.rerere.workspace

import java.io.Closeable

/** Serializes managed runtimes with destructive workspace operations, not normal short commands. */
object WorkspaceRuntimeLease {
    private val holders = mutableMapOf<String, Any>()

    @Synchronized fun acquire(root: String): Closeable {
        check(root !in holders) { "请先停止此工作区的 ST 托管，或等待当前环境操作完成。" }
        val ticket = Any()
        holders[root] = ticket
        return Closeable { synchronized(this) { if (holders[root] === ticket) holders.remove(root) } }
    }
}
