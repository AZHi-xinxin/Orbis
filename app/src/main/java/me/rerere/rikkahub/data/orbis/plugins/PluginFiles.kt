package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only this plugin's bounded private directory is exposed, never app settings, chats or rootfs. */
internal class PluginFiles(private val root: File) {
    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) && !name.contains("..")) { "plugin_file_name_invalid" }
        return File(root,name).also { check(root.canonicalFile==root.absoluteFile && it.canonicalFile==it.absoluteFile) { "plugin_file_path_invalid" } }
    }
    fun read(name: String): String? {
        val f=file(name); if(!f.exists()) return null
        check(f.isFile && f.length()<=64*1024) { "plugin_file_size" }
        return f.inputStream().use { stream ->
            val bytes=stream.readBytes(); check(bytes.size<=64*1024) { "plugin_file_size" }; bytes.toString(Charsets.UTF_8)
        }
    }
    fun list(): List<String> {
        file("check")
        return root.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith("pending-") }.map { it.name }.sorted().take(100)
    }
    fun deleteAll() = synchronized(locks.getOrPut(root.absolutePath) { Any() }) {
        file("check")
        val files=root.listFiles().orEmpty()
        check(files.size<=100 && files.all { it.isFile && it.canonicalFile==it.absoluteFile }) { "plugin_file_path_invalid" }
        files.forEach { check(it.delete()) { "plugin_file_delete_failed" } }
        if(root.exists()) check(root.delete()) { "plugin_file_delete_failed" }
    }
    fun write(name: String, text: String, stillAllowed: () -> Unit) = synchronized(locks.getOrPut(root.absolutePath) { Any() }) {
        val bytes=text.toByteArray(); require(bytes.size<=64*1024) { "plugin_file_size" }
        val f=file(name); check(root.isDirectory || root.mkdirs()) { "plugin_file_path_invalid" }
        val others=root.listFiles().orEmpty().filter { it!=f }
        check(others.size<100 && others.sumOf { it.length() }+bytes.size<=1024*1024) { "plugin_files_full" }
        val temp=file("pending-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            stillAllowed()
            Files.move(temp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}

/** Human grants are device-local; normal exports/restores never grant code execution on another device. */
internal class PluginGrantStore(private val directory: File) {
    private val lock=locks.getOrPut(directory.absolutePath) { Any() }
    private fun file(id: String, version: Int): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && version>0)
        return File(directory,"$id-$version.json").also {
            check(directory.canonicalFile==directory.absoluteFile && it.canonicalFile==it.absoluteFile) { "plugin_grant_invalid" }
        }
    }
    fun read(id: String, version: Int): PluginGrant? = synchronized(lock) {
        val f=file(id,version); if(!f.exists()) return@synchronized null
        check(f.isFile && f.length() in 1..8192) { "plugin_grant_invalid" }
        pluginJson.decodeFromString<PluginGrant>(f.readText())
    }
    fun approve(id: String, version: PluginVersion, permissions: Set<String>, workspaceId: String?) = synchronized(lock) {
        require(permissions.all { it in version.body.permissions }) { "plugin_permission_denied" }
        val f=file(id,version.number); check(directory.isDirectory || directory.mkdirs())
        val grant=PluginGrant(version.hash,permissions,workspaceId.takeIf { "shell" in permissions })
        val tmp=File(directory,"pending-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { it.write(pluginJson.encodeToString(grant).toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
    fun revoke(id: String) = synchronized(lock) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        directory.listFiles().orEmpty().filter { it.name.startsWith("$id-") && it.name.endsWith(".json") }.forEach {
            check(it.canonicalFile==it.absoluteFile && it.isFile && it.delete()) { "plugin_grant_invalid" }
        }
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}
