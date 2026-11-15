package me.rerere.rikkahub.data.orbis.activities

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Explicit user/AI request only: no startup downloads and no provider credentials. */
internal object PetArtDownloads {
    private val client by lazy { OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build() }
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var catalog: PetArtCatalog? = null
    @Synchronized fun catalog(context: Context): PetArtCatalog = catalog ?: context.assets.open("orbis-pet-art.json")
        .bufferedReader().use { Json.decodeFromString<PetArtCatalog>(it.readText()) }.also { catalog = it }
    fun store(context: Context) = PetArtStore(File(context.filesDir.canonicalFile, "orbis-pet-art-v1"), catalog(context))
    fun url(context: Context, id: String): String {
        require(id in catalog(context).packages)
        return catalog(context).baseUrl + "$id.zip"
    }
    suspend fun download(context: Context, id: String): File = withContext(Dispatchers.IO) {
        val store = store(context)
        if (store.ready(id)) return@withContext store.directory(id)
        val spec = store.catalog.packages.getValue(id)
        val temp = File.createTempFile("pet-art-", ".zip", context.cacheDir)
        try {
            client.newCall(Request.Builder().url(url(context, id)).build()).execute().use { response ->
                check(response.isSuccessful) { "pet_art_http_${response.code}" }
                val body = checkNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == spec.bytes) { "pet_art_size" }
                body.byteStream().use { input -> temp.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= spec.bytes && total <= 32 * 1024 * 1024L)
                        out.write(buffer, 0, count)
                    }
                    require(total == spec.bytes)
                    out.fd.sync()
                } }
            }
            synchronized(this@PetArtDownloads) {
                store.install(id, temp).also {
                    revision.value++
                    // A damaged/unreadable archive must stop cleanup, not undo a valid installation.
                    runCatching { cleanUnused(context, setOf(id)) }
                }
            }
        } finally { temp.delete() }
    }
    @Synchronized fun importPack(context: Context, input: java.io.InputStream, id: String) {
        val temp=File.createTempFile("pet-art-import-",".zip",context.cacheDir)
        try {
            temp.outputStream().use { out ->
                val buffer=ByteArray(8192); var total=0L
                while(true) { val n=input.read(buffer);if(n<0) break;total+=n;require(total<=32*1024*1024L);out.write(buffer,0,n) }
            }
            val pack=catalog(context).packages.getValue(id)
            if(temp.length()==pack.bytes && temp.inputStream().use(::petArtSha256)==pack.sha256) store(context).install(id,temp)
            else temp.inputStream().use { PetAssets.import(context,it,id) }
            revision.value++
            runCatching {cleanUnused(context,setOf(id))}
        } finally {temp.delete()}
    }
    /** Protect every assistant, even disabled/removed assistants whose pet archive still exists. */
    @Synchronized fun cleanUnused(context: Context, extraKeep: Set<String> = emptySet()): Long {
        val base = File(context.filesDir.canonicalFile, "orbis-activities")
        val keep = extraKeep.toMutableSet()
        if (base.exists()) {
            require(base.canonicalFile == base.absoluteFile)
            for (owner in checkNotNull(base.listFiles())) {
                require(owner.canonicalFile == owner.absoluteFile && owner.isDirectory)
                val archive = File(owner, "pet.json")
                // A pending AtomicFile recovery must not be mistaken for an absent adoption.
                if (File(owner, "pet.json.bak").exists() || File(owner, "pet.json.new").exists()) return 0
                if (!archive.exists()) continue
                require(archive.canonicalFile == archive.absoluteFile && archive.length() <= 16 * 1024 * 1024)
                val pet = Json.parseToJsonElement(archive.readText()).jsonObject["pet"]
                if (pet != null && pet !is JsonNull) keep += pet.jsonObject.getValue("appearance").jsonPrimitive.content
            }
        }
        val store=store(context)
        var removed=store.removeUnused(keep) + store.removeSuperseded(keep)
        catalog(context).packages.keys.forEach { id ->
            // Drop an unused stock legacy import, or its verified duplicate after upgrade.
            if((id !in keep || store.ready(id)) && PetAssets.legacyIsOfficial(context,id)) {
                val dir=PetAssets.directory(context,id)
                PetAssets.names.forEach {name -> val f=File(dir,name);val bytes=f.length();if(f.delete()) removed+=bytes}
                dir.delete()
            }
        }
        if(removed>0) revision.value++
        return removed
    }
}
