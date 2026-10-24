package me.rerere.rikkahub.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse as Response
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer as Server
import me.rerere.rikkahub.data.sync.webdav.WebDavClient
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class WebDavOverwriteTransportTest {
    companion object {
        private const val LOGGING_PROVIDER_PROPERTY = "slf4j.provider"
        private var previousLoggingProvider: String? = null

        @JvmStatic @BeforeClass
        fun useJvmLoggingProvider() {
            previousLoggingProvider = System.getProperty(LOGGING_PROVIDER_PROPERTY)
            // Exercise real Ktor/OkHttp against loopback without requiring Android Log.
            System.setProperty(LOGGING_PROVIDER_PROPERTY,
                org.slf4j.helpers.NOP_FallbackServiceProvider::class.java.name)
        }

        @JvmStatic @AfterClass
        fun restoreJvmLoggingProperty() {
            previousLoggingProvider?.let { System.setProperty(LOGGING_PROVIDER_PROPERTY, it) }
                ?: System.clearProperty(LOGGING_PROVIDER_PROPERTY)
        }
    }

    private val generation = "12345678-1234-1234-1234-123456789abc"
    private fun client() = HttpClient(OkHttp) {
        followRedirects = false
        engine { config { followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false) } }
    }
    private fun dav(server: Server, client: HttpClient) = WebDavClient(
        WebDavConfig(url = server.url("/dav"), path = "orbis", username = "fixture", password = "synthetic"), client)

    @Test fun rangeIgnoredStillReadsOnlyBoundedPrefix() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setBody("a".repeat(50000)))
            assertEquals("aaaa", dav(server, http).readPrefix(OverwriteBackupNames.LATEST, 4).getOrThrow().decodeToString())
            val request = server.takeRequest()
            assertEquals("bytes=0-3", request.getHeader("Range"))
            assertEquals("/dav/orbis/${OverwriteBackupNames.LATEST}", request.path)
        } }
    }
    @Test fun partialPrefixMustBeginAtZero() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setResponseCode(206).addHeader("Content-Range", "bytes 4-7/20").setBody("abcd"))
            assertTrue(dav(server, http).readPrefix(OverwriteBackupNames.LATEST, 4).isFailure)
        } }
    }
    @Test fun genericDavCannotTriggerDestructiveMoveFallback() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setBody(""))
            assertTrue(dav(server, http).replaceBackup(OverwriteBackupNames.temporary(generation), null).isFailure)
            assertEquals("OPTIONS", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } }
    }
    @Test fun unsupportedOptionsIsCapabilityAbsenceNotConnectionFailure() = runBlocking {
        for (code in listOf(405, 501)) Server().use { server -> client().use { http ->
            server.enqueue(Response().setResponseCode(code).setBody(""))
            assertFalse(dav(server, http).supportsAtomicBackupReplace().getOrThrow())
            assertEquals("OPTIONS", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } }
    }
    @Test fun supportedCommitUsesSameDirectoryAndExpectedGeneration() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setBody("").addHeader("X-Orbis-Atomic-Replace", "bundle-v2"))
            server.enqueue(Response().setResponseCode(204).setBody("").addHeader("X-Orbis-Atomic-Replace", "bundle-v2"))
            dav(server, http).replaceBackup(OverwriteBackupNames.temporary(generation), generation).getOrThrow()
            assertEquals("OPTIONS", server.takeRequest().method)
            val move = server.takeRequest()
            assertEquals("MOVE", move.method)
            assertEquals(server.url("/dav/orbis/${OverwriteBackupNames.LATEST}"), move.getHeader("Destination"))
            assertEquals(generation, move.getHeader("X-Orbis-Expected-Generation"))
            assertEquals("0", move.getHeader("X-Orbis-Require-Empty"))
            assertEquals("T", move.getHeader("Overwrite"))
            assertEquals(0L, move.bodySize)
        } }
    }
    @Test fun firstCommitRequiresAbsentAndUnconfirmedReplyFails() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setBody("").addHeader("X-Orbis-Atomic-Replace", "bundle-v2"))
            server.enqueue(Response().setResponseCode(204).setBody(""))
            assertTrue(dav(server, http).replaceBackup(OverwriteBackupNames.temporary(generation), null, requireEmpty = true).isFailure)
            server.takeRequest()
            val move = server.takeRequest()
            assertEquals("absent", move.getHeader("X-Orbis-Expected-Generation"))
            assertEquals("1", move.getHeader("X-Orbis-Require-Empty"))
            assertEquals(2, server.requestCount)
        } }
    }
    @Test fun arbitraryRemoteNamesAndHeaderInjectionRejectedBeforeNetwork() = runBlocking {
        Server().use { server -> client().use { http ->
            assertTrue(dav(server, http).replaceBackup("../other", null).isFailure)
            assertTrue(dav(server, http).replaceBackup(OverwriteBackupNames.temporary(generation), "bad\r\nHeader: injected").isFailure)
            assertTrue(dav(server, http).readPrefix("../other", 4).isFailure)
            assertTrue(dav(server, http).readPrefix(OverwriteBackupNames.LATEST, 4109).isFailure)
            assertEquals(0, server.requestCount)
        } }
    }

    @Test fun directoryAuthOrServerFailureDoesNotAttemptCreationAndKeepsOriginalStep() = runBlocking {
        for (code in listOf(401, 403, 405, 500)) Server().use { server -> client().use { http ->
            server.enqueue(Response().setResponseCode(code).setBody("synthetic-private-response"))
            val error = dav(server, http).ensureCollectionExists().exceptionOrNull()!!
            assertTrue(error.message!!.contains("读取目录"))
            assertTrue(error.message!!.contains("HTTP $code"))
            assertFalse(error.message!!.contains("synthetic-private-response"))
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } }
    }

    @Test fun confirmedMissingDirectoryCreatesExactlyOnce() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setResponseCode(404).setBody(""))
            server.enqueue(Response().setResponseCode(201).setBody(""))
            dav(server, http).ensureCollectionExists().getOrThrow()
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("MKCOL", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun largeUploadExplainsServerLimitWithoutExposingBodyOrUrl() = runBlocking {
        Server().use { server -> client().use { http ->
            server.enqueue(Response().setResponseCode(413).setBody("private-provider-body"))
            val error = dav(server, http).put("private-name.zip", byteArrayOf(1, 2)).exceptionOrNull()!!
            assertTrue(error.message!!.contains("上传备份"))
            assertTrue(error.message!!.contains("HTTP 413"))
            assertTrue(error.message!!.contains("上传大小限制"))
            assertFalse(error.toString().contains("private-"))
            assertEquals(1, server.requestCount)
        } }
    }
}
