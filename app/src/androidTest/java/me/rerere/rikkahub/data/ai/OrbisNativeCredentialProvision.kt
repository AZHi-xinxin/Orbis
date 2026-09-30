package me.rerere.rikkahub.data.ai

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolCredentialStore
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit deployment helper, NEVER part of the normal isolated regression suite. */
class OrbisNativeCredentialProvision {
    @Test fun importAuthorizedOneTimeDeviceCredential() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orbisProvisionCloud") == "authorized-device-v1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = instrumentation.targetContext.applicationContext
        check(context.packageName == "org.orbis.agent.dev")
        val input = File(context.noBackupFilesDir, "orbis-native-cloud-provision-once.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var phase = "input"
        try {
            check(input.isFile && input.length() in 1..8192)
            phase = "json"
            val obj = Json.parseToJsonElement(input.readText(Charsets.UTF_8)) as JsonObject
            check(obj.keys == setOf("baseUrl", "token"))
            fun text(key: String) = (obj[key] as JsonPrimitive).also { check(it.isString) }.content
            val store = CloudToolCredentialStore(context, scope)
            phase = "save"
            store.save(text("baseUrl"), text("token"))
            phase = "reload"
            store.reload()
            check(store.state.value.configured && store.state.value.error == null)
            phase = "encrypted-file"
            val encrypted = File(context.noBackupFilesDir, "orbis-native-cloud-credential.bin").readBytes()
            check(!encrypted.toString(Charsets.ISO_8859_1).contains(text("token")))
        } catch (_: Exception) {
            // No exception causes or expected/actual values: they may contain secrets.
            throw AssertionError("Native device authorization provisioning failed at $phase; private payload not logged.")
        } finally {
            scope.cancel()
            check(!input.exists() || input.delete()) { "One-time private payload cleanup failed" }
        }
    }
}
