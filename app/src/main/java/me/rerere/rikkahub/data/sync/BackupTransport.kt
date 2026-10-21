package me.rerere.rikkahub.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.webdav.WebDavClient
import me.rerere.rikkahub.data.sync.webdav.WebDavException
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class AtomicBackupUnsupportedException : IllegalStateException("backup_atomic_replace_unsupported")

internal suspend fun HttpResponse.backupText(limit: Int = 2 * 1024 * 1024): String {
    val coroutine = currentCoroutineContext()
    return bodyAsChannel().toInputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "backup_response_too_large" }
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}

internal suspend fun HttpResponse.copyBackup(file: File, maxBytes: Long, envelope: Boolean = false) {
    val ceiling = if (envelope) OverwriteBackupNames.MAX_BUNDLE_BYTES else ArchiveCapacity.MAX_ZIP_BYTES
    require(maxBytes in 1..ceiling)
    headers["Content-Length"]?.toLongOrNull()?.let { require(it in 0..maxBytes) { "backup_response_too_large" } }
    val coroutine = currentCoroutineContext()
    bodyAsChannel().toInputStream().use { input ->
        NativeBackupBudget.Output(file.outputStream(), maxBytes) { file.parentFile!!.usableSpace }.use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "backup_response_too_large" }
                output.write(buffer, 0, count)
            }
        }
    }
}

/** Own client: no redirects to a new credential destination and no implicit PUT retry. */
internal class RemoteBackupStore(settings: Settings) : AtomicBackupObjectStore, AutoCloseable {
    private val target = settings.autoBackup.target
    val usesOverwrite: Boolean get() = target == "webdav"
    init {
        val url = (if(target == "webdav") settings.webDavConfig.url else settings.s3Config.endpoint).toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(target in setOf("webdav", "s3"))
        if(target == "webdav") require(settings.webDavConfig.path.split('/').none { it == ".." || it == "." })
        else require(settings.s3Config.bucket.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{1,62}")))
    }
    private val http = HttpClient(OkHttp) {
        followRedirects = false
        engine { config {
            followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false)
            connectTimeout(20, TimeUnit.SECONDS); readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(120, TimeUnit.SECONDS); callTimeout(8, TimeUnit.MINUTES)
        } }
    }
    private val dav = WebDavClient(settings.webDavConfig, http)
    private val s3 = S3Client(settings.s3Config, http)
    private val prefix = "orbis_versions/"
    suspend fun prepare() {
        if(target != "webdav") return
        val result = dav.propfind(depth = 0)
        if(result.isFailure) {
            val error = result.exceptionOrNull()
            if(error is WebDavException && error.statusCode == 404) dav.mkcol("").getOrThrow()
            else throw requireNotNull(error)
        }
    }
    private fun name(value: String): String {
        if (usesOverwrite) return OverwriteBackupNames.requireAllowed(value)
        require(value.matches(Regex("orbis_v1_[a-f0-9-]{36}\\.(payload|complete\\.json)")))
        return value
    }
    override suspend fun listNames(): List<String> {
        if(target == "webdav") return dav.propfind().getOrThrow().filterNot { it.isCollection }
            .map { it.href.trimEnd('/').substringAfterLast('/') }
            .filter { it == OverwriteBackupNames.LATEST || BackupRestorePoint.isMarker(it) }
            .also { require(it.size <= 10_000) { "backup_listing_too_large" } }
        val result = mutableListOf<String>(); val tokens = hashSetOf<String>(); var token: String? = null
        do {
            val page = s3.listObjects(prefix = prefix, continuationToken = token).getOrThrow()
            result += page.objects.map { it.key.removePrefix(prefix) }.filter(BackupRestorePoint::isMarker)
            require(result.size <= 10_000) { "backup_listing_too_large" }
            if(!page.isTruncated) break
            token = requireNotNull(page.nextContinuationToken) { "backup_listing_incomplete" }
            require(tokens.add(token) && tokens.size <= 100) { "backup_listing_incomplete" }
        } while(true)
        return result
    }
    override suspend fun upload(name: String, file: File) {
        require(name != OverwriteBackupNames.LATEST) { "backup_atomic_replace_required" }
        if(target == "webdav") dav.put(name(name), file).getOrThrow()
        else s3.putObject(prefix + name(name), file).getOrThrow()
    }
    override suspend fun download(name: String, file: File, maxBytes: Long) {
        if(target == "webdav") dav.downloadToFile(name(name), file, maxBytes,
            envelope = name == OverwriteBackupNames.LATEST || OverwriteBackupNames.isTemporary(name)).getOrThrow()
        else s3.downloadObjectToFile(prefix + name(name), file, maxBytes).getOrThrow()
    }
    override suspend fun delete(name: String) {
        require(name != OverwriteBackupNames.LATEST) { "backup_committed_delete_forbidden" }
        if(target == "webdav") dav.delete(name(name)).getOrThrow()
        else s3.deleteObject(prefix + name(name)).getOrThrow()
    }
    override suspend fun readPrefix(name: String, maxBytes: Int): ByteArray {
        require(usesOverwrite && name == OverwriteBackupNames.LATEST)
        require(maxBytes in 1..OverwriteBackupNames.MAX_HEADER_BYTES)
        return dav.readPrefix(name(name), maxBytes).getOrThrow()
    }
    suspend fun requireAtomicOverwrite() {
        check(usesOverwrite)
        if (!dav.supportsAtomicBackupReplace().getOrThrow()) throw AtomicBackupUnsupportedException()
    }
    override suspend fun replaceLatest(temporaryName: String, expectedGeneration: String?, requireEmpty: Boolean) {
        check(usesOverwrite)
        dav.replaceBackup(temporaryName, expectedGeneration, requireEmpty).getOrThrow()
    }
    override fun close() { http.close() }
}
