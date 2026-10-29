package me.rerere.rikkahub.data.orbis.ombre

import android.app.Application
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.st.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.workspace.WorkspaceRuntimeLease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class OmbreRuntimeDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    @Test fun installedObAndStCoexistReadWriteRestartAndStopIndependently() {
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        val context=inst.targetContext
        check(context.packageName.endsWith(".bodylab") && context.applicationContext.javaClass==Application::class.java)
        val root="dev141-memory-runtime"
        check(File(context.filesDir,"workspaces/$root/linux/opt/ombre-python/bin/python3").isFile)
        val ob=OmbreRuntimeHost.get(context);val st=StRuntimeHost.get(context)
        compose.setContent { MaterialTheme { Text("Isolated ST and OmbreBrain runtime test") } }
        ob.setEnabled(root,true);st.setEnabled(root,true)
        try {
            st.requestStart(root,"synthetic-owner",StRuntimeSpec("/workspace/ST","/opt/python/bin/python3","/root/st-fixture/config.env"))
            ob.requestStart(root,"synthetic-owner",OmbreRuntimeSpec("/workspace/OB","/opt/ombre-python/bin/python3","/root/ombre-fixture/config.yaml"))
            waitFor(110_000) { ob.current(root).phase in setOf("ready","failed") && st.current(root).phase in setOf("ready","failed") }
            assertEquals(ob.current(root).toString(),"ready",ob.current(root).phase)
            assertEquals(st.current(root).toString(),"ready",st.current(root).phase)
            assertTrue(runCatching { WorkspaceRuntimeLease.acquire(root) }.isFailure)
            val connection=URL("http://127.0.0.1:29881/mcp").openConnection() as HttpURLConnection
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }
            assertTrue(connection.responseCode in listOf(401,403));connection.disconnect()
            initialize()
            val marker="Orbis Android isolated OB fixture 20261029"
            val held=rpc("tools/call",buildJsonObject { put("name","hold");put("arguments",buildJsonObject { put("content",marker);put("title","Android fixture");put("test_data",true) }) })
            assertFalse(held.toString(),held["result"]!!.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull==true)
            assertTrue(File(context.filesDir,"workspaces/$root/linux/root/ombre-fixture/buckets").walkTopDown().filter { it.extension=="md" }.any { marker in it.readText() })
            assertTrue(readFixture().contains(marker))
            val tick=ob.current(root).checkedAt!!
            inst.runOnMainSync { compose.activity.moveTaskToBack(true) }
            waitFor(25_000) { (ob.current(root).checkedAt ?: 0)>tick }
            ob.stopAll(forgetRunning=false)
            waitFor(40_000) { !ob.hasRuns() }
            assertEquals("ready",st.current(root).phase)
            assertTrue(runCatching { WorkspaceRuntimeLease.acquire(root) }.isFailure)
            // Return to visible app before restoring the previously running OB instance.
            inst.runOnMainSync { context.startActivity(android.content.Intent(context,compose.activity.javaClass).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ob.restoreOnAppOpen(mapOf(root to setOf("synthetic-owner")))
            waitFor(110_000) { ob.current(root).phase in setOf("ready","failed") }
            assertEquals(ob.current(root).toString(),"ready",ob.current(root).phase)
            session=null;initialize();assertTrue(readFixture().contains(marker))
            ob.stop(root);waitFor(40_000) { !ob.hasRuns() }
            assertFalse(ob.saved(root).resumeOnAppOpen)
            assertEquals("ready",st.current(root).phase)
            st.stop(root);waitFor(40_000) { !st.hasRuns() }
            WorkspaceRuntimeLease.acquire(root).close()
            for(port in listOf(29871,29872,29873,29881)) assertTrue("listener $port",runCatching { java.net.Socket("127.0.0.1",port).close() }.isFailure)
        } finally { ob.setEnabled(root,false);st.setEnabled(root,false);ob.stop(root);st.stop(root) }
    }
    private var session:String?=null
    private var request=0
    private fun initialize() {
        rpc("initialize",buildJsonObject { put("protocolVersion","2025-03-26");put("capabilities",buildJsonObject {});put("clientInfo",buildJsonObject { put("name","android-fixture");put("version","1") }) })
        rpc("notifications/initialized",buildJsonObject {},false)
    }
    private fun readFixture()=rpc("tools/call",buildJsonObject { put("name","breath");put("arguments",buildJsonObject { put("query","Android isolated OB fixture");put("max_tokens",1000) }) }).toString()
    private fun rpc(method:String,params:JsonObject,id:Boolean=true):JsonObject {
        val c=URL("http://127.0.0.1:29881/mcp").openConnection() as HttpURLConnection
        try {
            c.requestMethod="POST";c.doOutput=true;c.connectTimeout=5000;c.readTimeout=15000
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json, text/event-stream")
            c.setRequestProperty("Authorization","Bearer synthetic-android-ombre-token");c.setRequestProperty("MCP-Protocol-Version","2025-03-26")
            session?.let { c.setRequestProperty("Mcp-Session-Id",it) }
            c.outputStream.use { it.write(buildJsonObject { put("jsonrpc","2.0");put("method",method);put("params",params);if(id)put("id",++request) }.toString().toByteArray()) }
            check(c.responseCode in 200..299) { "MCP HTTP ${c.responseCode}" }
            session=c.getHeaderField("Mcp-Session-Id") ?: session
            if(!id)return buildJsonObject {}
            return c.inputStream.bufferedReader().use { reader ->
                if(c.contentType?.contains("text/event-stream")==true) {
                    val text=reader.lineSequence().first { it.startsWith("data:") }.removePrefix("data:")
                    Json.parseToJsonElement(text).jsonObject
                } else Json.parseToJsonElement(reader.readText()).jsonObject
            }
        } finally { c.disconnect() }
    }
    private fun waitFor(timeout:Long,test:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!test()) { if(SystemClock.elapsedRealtime()>end)error("runtime timeout");SystemClock.sleep(100) }
    }
}
