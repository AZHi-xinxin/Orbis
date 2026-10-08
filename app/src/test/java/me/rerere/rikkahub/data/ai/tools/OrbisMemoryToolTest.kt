package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext
import org.junit.Assert.*
import org.junit.Test

class OrbisMemoryToolTest {
    private fun args(text: String) = Json.parseToJsonElement(text)
    @Test fun aliasesAndOptionalTagsAreForgiving() {
        val p = normalizeOrbisMemoryArguments(args("""{"op":"save","content":"whole text","tags":"tea，咖啡","assistantId":"wrong","operation_key":"forged"}"""))
        assertEquals("store", p["action"]!!.jsonPrimitive.content)
        assertEquals("whole text", p["body"]!!.jsonPrimitive.content)
        assertEquals(2, p["tags"]!!.jsonArray.size)
        assertFalse(p.containsKey("assistantId")); assertFalse(p.containsKey("operation_key"))
    }
    @Test fun bodyOnlyIsEnoughAndEmptyRequestReads() {
        assertEquals("store", normalizeOrbisMemoryArguments(args("""{"body":"memo"}"""))["action"]!!.jsonPrimitive.content)
        assertEquals("read", normalizeOrbisMemoryArguments(args("{}"))["action"]!!.jsonPrimitive.content)
    }
    @Test fun hostBindsOwnerAndReplayKey() = runBlocking {
        var seen = ""
        val tool = buildOrbisMemoryTool("owner", { true }) { owner, _, key ->
            seen = "$owner/$key"; buildJsonObject { put("ok", true) }
        }
        withContext(CloudToolInvocationContext("call", "message", "conversation")) {
            tool.execute(args("""{"body":"memo","assistantId":"victim"}"""))
        }
        assertEquals("owner/conversation:message:call", seen)
    }
    @Test fun missingHostIdentityDoesNotWriteAndReferenceOnlyCannotMutate() = runBlocking {
        var writes = 0
        val normal = buildOrbisMemoryTool("owner", { true }) { _, _, _ -> writes++; buildJsonObject { put("ok", true) } }
        normal.execute(args("""{"body":"memo"}"""))
        val reference = buildOrbisMemoryTool("owner", { true }, true) { _, _, _ -> writes++; buildJsonObject { put("ok", true) } }
        withContext(CloudToolInvocationContext("call", "m", "c")) { reference.execute(args("""{"body":"memo"}""")) }
        assertEquals(0, writes)
    }
    @Test fun injectionOffDoesNotDisableReadOrStoreBySchema() {
        val tool = buildOrbisMemoryTool("owner", { true }) { _, _, _ -> buildJsonObject { put("ok", true) } }
        assertEquals("orbis_memory", tool.name)
        assertFalse(tool.description.contains("先申请"))
    }
}
