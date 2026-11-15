package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream

/** Explicit FILES backup whitelist. Code/data may travel, permission grants never do. */
internal object PluginBackup {
    private val path=Regex("orbis-plugins/([A-Za-z0-9_-]{1,80})/(plugins\\.json|data/([A-Za-z0-9_-]{1,80})/([A-Za-z0-9][A-Za-z0-9_.-]{0,79}))")
    fun maxBytes(name: String): Int?=path.matchEntire(name)?.takeIf { !it.groupValues[4].contains("..") }?.let {
        if(it.groupValues[2]=="plugins.json") PluginRepository.MAX_STORE_BYTES.toInt() else 64*1024
    }
    private fun exact(root: File,name: String): File=File(root.canonicalFile,name).also {
        require(it.canonicalFile==it.absoluteFile && it.toPath().startsWith(root.canonicalFile.toPath())) { "plugin_backup_path" }
    }
    private fun owners(root: File): List<File> {
        val parent=exact(root,"orbis-plugins")
        if(!parent.exists()) return emptyList()
        require(parent.isDirectory) { "plugin_backup_path" }
        return parent.listFiles().orEmpty().toList().also { list -> require(list.size<=200) { "plugin_backup_limit" } }.onEach {
            require(it.isDirectory && it.name.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.canonicalFile==it.absoluteFile) { "plugin_backup_path" }
        }
    }
    private fun read(name: String,file: File): ByteArray {
        val limit=requireNotNull(maxBytes(name))
        require(file.isFile && file.canonicalFile==file.absoluteFile && file.length()<=limit) { "plugin_backup_size" }
        return file.inputStream().use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val n=input.read(buffer);if(n<0) break;require(out.size()+n<=limit) { "plugin_backup_size" };out.write(buffer,0,n)}
            out.toByteArray()
        }
    }
    fun validate(name: String,bytes: ByteArray) {
        require(bytes.size<=requireNotNull(maxBytes(name))) { "plugin_backup_size" }
        if(name.endsWith("/plugins.json")) PluginRepository.validateState(pluginJson.decodeFromString<PluginState>(bytes.toString(Charsets.UTF_8)))
    }
    private fun portable(state: PluginState)=state.copy(entries=state.entries.map { e -> e.copy(enabled=false,
        runs=e.runs.map { if(it.status=="running") it.copy(status="interrupted",error="plugin_interrupted") else it }) })
    private fun files(root: File,owner: File): List<Pair<String,File>> {
        val state=PluginRepository(owner).snapshot()
        val result=mutableListOf<Pair<String,File>>()
        val store=exact(root,"orbis-plugins/${owner.name}/plugins.json")
        if(store.exists()) result+="orbis-plugins/${owner.name}/plugins.json" to store
        state.entries.forEach { e ->
            val directory=exact(owner,"data/${e.id}")
            if(directory.exists()) {
                require(directory.isDirectory) { "plugin_backup_path" }
                val entries=directory.listFiles().orEmpty()
                require(entries.size<=100 && entries.sumOf {it.length()}<=1024*1024) { "plugin_backup_limit" }
                entries.forEach { f ->
                    val name="orbis-plugins/${owner.name}/data/${e.id}/${f.name}"
                    require(maxBytes(name)!=null && !f.name.startsWith("pending-")) { "plugin_backup_path" }
                    val file=exact(root,name);validate(name,read(name,file));result+=name to file
                }
            }
        }
        return result
    }
    fun stageSnapshot(filesDir: File,destination: File,checkCancelled: ()->Unit={},beforeFile: (String,Long)->Unit={_,_->}): List<Pair<String,File>> {
        val result=mutableListOf<Pair<String,File>>()
        owners(filesDir).forEach { owner -> PluginRepository(owner).locked {
            files(filesDir,owner).forEach { (name,file) ->
                checkCancelled()
                val bytes=if(file.name=="plugins.json") pluginJson.encodeToString(portable(PluginRepository(owner).snapshot())).toByteArray() else read(name,file)
                beforeFile(name,bytes.size.toLong())
                val target=exact(destination,name);require(!target.exists());check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                FileOutputStream(target).use {it.write(bytes);it.fd.sync()};result+=name to target
            }
        } }
        return result
    }
    fun validateStaged(payload: File) {
        val root=File(payload,"files")
        owners(root).forEach { owner ->
            require(exact(owner,"plugins.json").isFile) { "plugin_backup_missing_store" }
            require(owner.listFiles().orEmpty().all { it.name in setOf("plugins.json","data") }) { "plugin_backup_path" }
            val known=PluginRepository(owner).snapshot().entries.map {it.id}.toSet()
            val data=exact(owner,"data")
            if(data.exists()) {
                require(data.isDirectory) { "plugin_backup_path" }
                require(data.listFiles().orEmpty().all {it.isDirectory && it.name in known && it.canonicalFile==it.absoluteFile}) { "plugin_backup_orphan" }
            }
            files(root,owner)
        }
    }
    fun stripRestoredPermissions(payload: File) {
        validateStaged(payload)
        owners(File(payload,"files")).forEach { owner ->
            val state=portable(PluginRepository(owner).snapshot())
            val file=exact(owner,"plugins.json")
            FileOutputStream(file).use {it.write(pluginJson.encodeToString(state).toByteArray());it.fd.sync()}
        }
    }
    fun prepareBeforeJournal(payload: File,live: File) {
        validateStaged(payload)
        val root=File(payload,"files")
        val writes=mutableListOf<Pair<File,ByteArray>>()
        owners(root).forEach { incoming ->
            val source=portable(PluginRepository(incoming).snapshot())
            val oldRoot=exact(live,"orbis-plugins/${incoming.name}")
            val old=PluginRepository(oldRoot).snapshot()
            val oldById=portable(old).entries.associateBy {it.id}
            require(source.entries.all {oldById[it.id]==null || oldById[it.id]==it}) { "plugin_backup_conflict" }
            val merged=old.copy(entries=old.entries+source.entries.filter {it.id !in oldById})
            PluginRepository.validateState(merged)
            files(live,oldRoot).filterNot {it.first.endsWith("/plugins.json")}.forEach { (name,sourceFile) ->
                val target=exact(root,name);val bytes=read(name,sourceFile)
                if(target.exists()) require(read(name,target).contentEquals(bytes)) { "plugin_backup_conflict" }
                else writes+=target to bytes
            }
            writes+=exact(incoming,"plugins.json") to pluginJson.encodeToString(merged).toByteArray()
        }
        writes.forEach { (file,bytes) -> check(file.parentFile.isDirectory || file.parentFile.mkdirs());FileOutputStream(file).use {it.write(bytes);it.fd.sync()} }
    }
}
