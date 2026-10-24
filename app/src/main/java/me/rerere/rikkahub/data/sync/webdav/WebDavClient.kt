package me.rerere.rikkahub.data.sync.webdav

import android.util.Log
import me.rerere.rikkahub.data.sync.backupText
import me.rerere.rikkahub.data.sync.copyBackup
import me.rerere.rikkahub.data.sync.OverwriteBackupNames
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val TAG = "WebDavClient"

class WebDavClient(
    private val config: WebDavConfig,
    private val httpClient: HttpClient,
) {
    private fun WebDavConfig.buildUrl(vararg segments: String): String {
        val base = url.trimEnd('/')
        val pathSegments = listOfNotNull(
            path.takeIf { it.isNotBlank() }?.trim('/'),
            *segments.map { it.trim('/') }.toTypedArray()
        ).filter { it.isNotEmpty() }

        return if (pathSegments.isEmpty()) {
            base
        } else {
            "$base/${pathSegments.joinToString("/")}"
        }
    }
    suspend fun put(
        path: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("上传备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", data.size.toString())
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to put: ${response.status}", response.status.value, errorBody)
            }

            // Do not log storage URLs, object names or remote response bodies.
            Unit
        }
    }

    suspend fun put(
        path: String,
        file: File,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("上传备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", file.length().toString())
                }
                // Stream file content to avoid loading the whole backup zip into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to put file: ${response.status}", response.status.value, errorBody)
            }

            // Do not log storage URLs, object names or remote response bodies.
            Unit
        }
    }

    suspend fun get(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        davResult("下载备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to get: ${response.status}", response.status.value, errorBody)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().readBytes()
        }
    }

    suspend fun getStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        davResult("下载备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to get stream: ${response.status}", response.status.value, errorBody)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadToFile(path: String, targetFile: File, maxBytes: Long = me.rerere.rikkahub.data.sync.importer.ArchiveCapacity.MAX_ZIP_BYTES,
        envelope: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("下载备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            httpClient.prepareRequest(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    response.bodyAsChannel().cancel(null)
                    val errorBody = "HTTP ${response.status.value}"
                    // Do not log storage URLs, object names or remote response bodies.
                    throw WebDavException("Failed to download: ${response.status}", response.status.value, errorBody)
                }

                require(!envelope || path == OverwriteBackupNames.LATEST || OverwriteBackupNames.isTemporary(path))
                response.copyBackup(targetFile, maxBytes, envelope)
                Log.d(TAG, "downloadToFile success: downloaded ${targetFile.length()} bytes")
            }
            Unit
        }
    }

    /** Read metadata without buffering the whole archive when a server ignores Range. */
    suspend fun readPrefix(path: String, maxBytes: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        davResult("读取备份头") {
            require(path == OverwriteBackupNames.LATEST && maxBytes in 1..OverwriteBackupNames.MAX_HEADER_BYTES)
            httpClient.prepareRequest(config.buildUrl(path)) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
                headers { append("Range", "bytes=0-${maxBytes - 1}") }
            }.execute { response ->
                require(response.status.value == 200 || response.status.value == 206) { "backup_prefix_http_${response.status.value}" }
                if (response.status.value == 206) require(response.headers["Content-Range"]?.startsWith("bytes 0-") == true) {
                    "backup_prefix_range"
                }
                val channel = response.bodyAsChannel()
                try {
                    val bytes = ByteArray(maxBytes)
                    var count = 0
                    while (count < maxBytes) {
                        val size = channel.readAvailable(bytes, count, maxBytes - count)
                        if (size < 0) break
                        count += size
                    }
                    bytes.copyOf(count)
                } finally { channel.cancel(null) }
            }
        }
    }

    /** Generic DAV MOVE may delete the old file first: require the explicit server contract. */
    suspend fun supportsAtomicBackupReplace(): Result<Boolean> = withContext(Dispatchers.IO) {
        davResult("检查覆盖能力") {
            httpClient.prepareRequest(config.buildUrl().trimEnd('/') + "/") {
                method = HttpMethod.Options
                basicAuth(config.username, config.password)
            }.execute { response ->
                try {
                    if (response.status.value == 405 || response.status.value == 501) return@execute false
                    check(response.status.isSuccess()) { "backup_capability_http_${response.status.value}" }
                    response.headers["X-Orbis-Atomic-Replace"] == "bundle-v2"
                } finally { response.bodyAsChannel().cancel(null) }
            }
        }
    }

    suspend fun replaceBackup(temporaryName: String, expectedGeneration: String?, requireEmpty: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("提交覆盖备份") {
            require(OverwriteBackupNames.isTemporary(temporaryName))
            require(expectedGeneration == null || OverwriteBackupNames.isGeneration(expectedGeneration))
            require(!requireEmpty || expectedGeneration == null)
            check(supportsAtomicBackupReplace().getOrThrow()) { "backup_atomic_replace_unsupported" }
            val source = config.buildUrl(temporaryName).toHttpUrl()
            val destination = config.buildUrl(OverwriteBackupNames.LATEST).toHttpUrl()
            check(source.scheme == destination.scheme && source.host == destination.host && source.port == destination.port &&
                source.encodedPath.substringBeforeLast('/') == destination.encodedPath.substringBeforeLast('/'))
            httpClient.prepareRequest(source.toString()) {
                method = HttpMethod("MOVE")
                basicAuth(config.username, config.password)
                headers {
                    append("Destination", destination.toString())
                    append("Overwrite", "T")
                    append("X-Orbis-Expected-Generation", expectedGeneration ?: "absent")
                    append("X-Orbis-Require-Empty", if (requireEmpty) "1" else "0")
                }
            }.execute { response ->
                try {
                    check(response.status.value == 201 || response.status.value == 204) { "backup_replace_http_${response.status.value}" }
                    check(response.headers["X-Orbis-Atomic-Replace"] == "bundle-v2") { "backup_replace_unconfirmed" }
                } finally { response.bodyAsChannel().cancel(null) }
            }
        }
    }

    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("删除远端备份") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Delete
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to delete: ${response.status}", response.status.value, errorBody)
            }

            // Do not log storage URLs, object names or remote response bodies.
            Unit
        }
    }

    suspend fun head(path: String): Result<WebDavResourceInfo> = withContext(Dispatchers.IO) {
        davResult("读取文件信息") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Head
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                throw WebDavException("Resource not found: ${response.status}", response.status.value, "")
            }

            WebDavResourceInfo(
                href = path,
                displayName = path.substringAfterLast("/"),
                contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["Content-Type"] ?: "application/octet-stream",
                lastModified = parseLastModified(response.headers["Last-Modified"]),
                isCollection = false,
            )
        }
    }

    suspend fun mkcol(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("创建备份目录") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("MKCOL")
                basicAuth(config.username, config.password)
            }

            // 201 Created or 405 Method Not Allowed (already exists) are acceptable
            if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to create collection: ${response.status}", response.status.value, errorBody)
            }

            // Do not log storage URLs, object names or remote response bodies.
            Unit
        }
    }

    suspend fun propfind(
        path: String = "",
        depth: Int = 1,
    ): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        davResult("读取目录") {
            val url = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            val propfindBody = """<?xml version="1.0" encoding="UTF-8"?>
                |<D:propfind xmlns:D="DAV:">
                |  <D:prop>
                |    <D:displayname/>
                |    <D:getcontentlength/>
                |    <D:getcontenttype/>
                |    <D:getlastmodified/>
                |    <D:resourcetype/>
                |  </D:prop>
                |</D:propfind>
            """.trimMargin()

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", "application/xml; charset=utf-8")
                    append("Depth", depth.toString())
                }
                setBody(propfindBody)
            }

            if (!response.status.isSuccess() && response.status.value != 207) {
                response.bodyAsChannel().cancel(null)
                val errorBody = "HTTP ${response.status.value}"
                // Do not log storage URLs, object names or remote response bodies.
                throw WebDavException("Failed to propfind: ${response.status}", response.status.value, errorBody)
            }

            val xmlBody = response.backupText()
            parsePropfindResponse(xmlBody, url)
        }
    }

    suspend fun exists(path: String): Boolean {
        return head(path).isSuccess
    }

    suspend fun ensureCollectionExists(path: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        davResult("检查备份目录") {
            val targetUrl = config.buildUrl(path)
            // Do not log storage URLs, object names or remote response bodies.

            // Try propfind first to check if it exists
            val propfindResult = propfind(path, depth = 0)
            if (propfindResult.isSuccess) {
                // Do not log storage URLs, object names or remote response bodies.
                return@davResult
            }

            // Only a confirmed missing collection permits creation. Preserve auth/network failures.
            val failure = propfindResult.exceptionOrNull()
            val status = when (failure) {
                is WebDavOperationFailure -> failure.code
                is WebDavException -> failure.statusCode
                else -> 0
            }
            if (status != 404) throw checkNotNull(failure)
            mkcol(path).getOrThrow()
        }
    }

    suspend fun list(path: String = ""): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        davResult("列出备份") {
            val result = propfind(path, depth = 1).getOrThrow()
            // Filter out the parent directory itself (first entry)
            if (result.isNotEmpty()) {
                result.drop(1)
            } else {
                emptyList()
            }
        }
    }

    private fun parsePropfindResponse(xml: String, baseUrl: String): List<WebDavResourceInfo> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val resources = mutableListOf<WebDavResourceInfo>()
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var currentContentLength: Long = 0
        var currentContentType: String? = null
        var currentLastModified: Instant? = null
        var currentIsCollection = false
        var currentTag: String? = null
        var inResponse = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(":")
                    when (currentTag) {
                        "response" -> {
                            inResponse = true
                            currentHref = null
                            currentDisplayName = null
                            currentContentLength = 0
                            currentContentType = null
                            currentLastModified = null
                            currentIsCollection = false
                        }
                        "collection" -> {
                            currentIsCollection = true
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inResponse) {
                        when (currentTag) {
                            "href" -> currentHref = text
                            "displayname" -> currentDisplayName = text
                            "getcontentlength" -> currentContentLength = text.toLongOrNull() ?: 0
                            "getcontenttype" -> currentContentType = text
                            "getlastmodified" -> currentLastModified = parseLastModified(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.substringAfter(":")
                    if (tagName == "response" && currentHref != null) {
                        val displayName = currentDisplayName
                            ?: currentHref!!.trimEnd('/').substringAfterLast("/")

                        resources.add(
                            WebDavResourceInfo(
                                href = currentHref!!,
                                displayName = displayName,
                                contentLength = currentContentLength,
                                contentType = currentContentType ?: "application/octet-stream",
                                lastModified = currentLastModified,
                                isCollection = currentIsCollection,
                            )
                        )
                        inResponse = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return resources
    }

    private fun parseLastModified(dateString: String?): Instant? {
        if (dateString.isNullOrBlank()) return null

        return try {
            // RFC 1123 format: "Tue, 15 Nov 1994 08:12:31 GMT"
            ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // RFC 850 format: "Tuesday, 15-Nov-94 08:12:31 GMT"
                ZonedDateTime.parse(dateString, DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz")).toInstant()
            } catch (e: Exception) {
                try {
                    // ISO 8601
                    Instant.parse(dateString)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse date: $dateString")
                    null
                }
            }
        }
    }
}

data class WebDavResourceInfo(
    val href: String,
    val displayName: String,
    val contentLength: Long,
    val contentType: String,
    val lastModified: Instant?,
    val isCollection: Boolean,
)

class WebDavException(
    message: String,
    val statusCode: Int,
    val responseBody: String,
) : Exception(message)
