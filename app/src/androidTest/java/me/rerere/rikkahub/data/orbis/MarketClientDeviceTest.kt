package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.market.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in synthetic public account only; normal test runs never use a live service. */
@RunWith(AndroidJUnit4::class)
class MarketClientDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    @Test fun encryptedLoginAndUncertainMutationReceiptAreRecovered() = runBlocking {
        check(instrumentation is IsolatedGenerationLoopRunner)
        val path = InstrumentationRegistry.getArguments().getString("marketSyntheticFixture")
        assumeTrue(path != null)
        require(File(path!!).canonicalPath.startsWith(instrumentation.targetContext.cacheDir.canonicalPath + "/market-synthetic-"))
        val fixture = Json.parseToJsonElement(File(path).readText()).jsonObject
        require(fixture.ms("login").startsWith("ui_"))
        val root = temporary.newFolder("private")
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val client = MarketClient(context)
        val auth = client.auth("login", marketObject("login" to fixture.ms("login"), "password" to fixture.ms("password")))
        assertTrue(client.hasSession()); assertEquals(fixture.ms("login"), MarketClient(context).get("/me").ms("login"))
        val bytes = File(root, "orbis-market/session.enc").readBytes()
        assertFalse(bytes.decodeToString().contains(auth.ms("token")))
        val body = buildJsonObject { put("item_id", fixture.ms("item")); put("favorite", true) }
        val key = UUID.randomUUID().toString()
        // Simulate a server commit whose acknowledgement is lost before the app can record it.
        val request = Request.Builder().url("https://consult.azhi-xinxin.cn/v1/market/favorites")
            .header("Authorization", "Bearer ${auth.ms("token")}").header("Idempotency-Key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        OkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
        val pending = buildJsonObject { put("id", key); put("owner", auth["user"]!!.jsonObject.ms("id")); put("path", "/favorites"); put("body", body) }
        marketAtomic(File(root, "orbis-market/pending.json"), pending.toString().toByteArray())
        val receipt = MarketClient(context).reconcile()
        assertTrue(receipt!!.mb("favorite")); assertNull(client.pendingRequest())
        assertEquals(1, client.get("/items/${fixture.ms("item")}").mi("favorite_count"))
        client.post("/favorites", buildJsonObject { put("item_id", fixture.ms("item")); put("favorite", false) })
        assertEquals(0, client.get("/items/${fixture.ms("item")}").mi("favorite_count"))
        assertNull(client.pendingRequest())
        client.logout(); assertFalse(client.hasSession())
    }
}
