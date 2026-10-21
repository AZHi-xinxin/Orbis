package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android HTTP engines and XML parsers, synthetic data, loopback only. */
@RunWith(AndroidJUnit4::class)
class RemoteBackupStoreDeviceTest {
    private val marker = "orbis_v1_11111111-1111-1111-1111-111111111111.complete.json"
    private fun response(body: String = "", code: Int = 200) =
        PrivateRoomLoopbackResponse().setBody(body).setResponseCode(code)
    private fun settings(url: String, s3: Boolean = false) = Settings(
        autoBackup = AutoBackupConfig(target = if(s3) "s3" else "webdav"),
        webDavConfig = WebDavConfig(url, "synthetic", "not-a-secret", "archive"),
        s3Config = S3Config(url, "synthetic", "not-a-secret", "test-bucket", "test-region", true))
    private fun isolated(block: suspend (File) -> Unit) = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val root = Files.createTempDirectory(inst.targetContext.cacheDir.toPath(), "remote-backup-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun webDavWritesReadsListsAndDeletesExactVersionObjects() = isolated { root ->
        PrivateRoomLoopbackServer().use { server ->
            val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/archive/$marker</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response></D:multistatus>"""
            server.enqueue(response(xml, 207)); server.enqueue(response(code = 201))
            server.enqueue(response("synthetic version")); server.enqueue(response(xml, 207)); server.enqueue(response(code = 204))
            RemoteBackupStore(settings(server.url(""))).use { store ->
                store.prepare()
                val original = File(root, "original").apply { writeText("synthetic version") }
                store.upload(marker, original)
                val restored = File(root, "restored")
                store.download(marker, restored, 100)
                assertEquals(original.readText(), restored.readText())
                assertEquals(listOf(marker), store.listNames())
                store.delete(marker)
            }
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("PROPFIND", "PUT", "GET", "PROPFIND", "DELETE"), requests.map { it.method })
            assertEquals("/archive/$marker", requests[1].path)
            assertEquals("synthetic version", requests[1].body)
            assertTrue(requests.all { it.getHeader("Authorization")?.startsWith("Basic ") == true })
        }
    }
    @Test fun s3SigningAndPaginationPreserveVersionPrefix() = isolated { root ->
        PrivateRoomLoopbackServer().use { server ->
            server.enqueue(response(code = 200)); server.enqueue(response("synthetic version"))
            server.enqueue(response("""<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>next-page</NextContinuationToken><Contents><Key>orbis_versions/$marker</Key><Size>17</Size></Contents></ListBucketResult>"""))
            server.enqueue(response("""<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"""))
            server.enqueue(response(code = 204))
            RemoteBackupStore(settings(server.url(""), true)).use { store ->
                store.upload(marker, File(root, "original").apply { writeText("synthetic version") })
                val restored = File(root, "restored")
                store.download(marker, restored, 100)
                assertEquals("synthetic version", restored.readText())
                assertEquals(listOf(marker), store.listNames())
                store.delete(marker)
            }
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("PUT", "GET", "GET", "GET", "DELETE"), requests.map { it.method })
            assertEquals("/test-bucket/orbis_versions/$marker", requests[0].path)
            assertTrue(requests[3].path.contains("continuation-token=next-page"))
            assertTrue(requests.all { it.getHeader("Authorization")?.startsWith("AWS4-HMAC-SHA256 ") == true })
        }
    }
    @Test fun redirectNeverForwardsStorageCredentialsToAnotherDestination() = isolated { root ->
        PrivateRoomLoopbackServer().use { target -> PrivateRoomLoopbackServer().use { server ->
            server.enqueue(response(code = 302).addHeader("Location", target.url("/unexpected")))
            RemoteBackupStore(settings(server.url(""))).use { store ->
                assertTrue(runCatching { store.download(marker, File(root, "restored"), 100) }.isFailure)
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, target.requestCount)
        } }
    }
    @Test fun oversizedRemotePayloadIsRejectedWithoutPublishingAReplacement() = isolated { root ->
        PrivateRoomLoopbackServer().use { server ->
            server.enqueue(response("synthetic oversized response"))
            val destination = File(root, "restored")
            RemoteBackupStore(settings(server.url(""))).use { store ->
                assertTrue(runCatching { store.download(marker, destination, 4) }.isFailure)
            }
            assertFalse(destination.exists())
            assertEquals(1, server.requestCount)
        }
    }
}
