package me.rerere.rikkahub.ui.pages.orbis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lover.connect.McpService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Catalog/unknown-call only. An unattached Service cannot access user storage or sensors. */
@RunWith(AndroidJUnit4::class)
class OrbisLcUsageOwnershipTest {
    private val removed = setOf("get_screen_time", "get_app_timeline", "reset_screen_time")

    @Test fun embeddedLcNoLongerAdvertisesDuplicateUsageToolsButKeepsOtherFamilies() {
        val method = McpService::class.java.getDeclaredMethod("handleToolsList", Any::class.java)
            .apply { isAccessible = true }
        val tools = JSONObject(method.invoke(McpService(), 1) as String)
            .getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertTrue(names.intersect(removed).isEmpty())
        assertTrue(names.containsAll(setOf("get_battery", "get_steps", "read_memory", "save_memory",
            "get_runtime_status", "get_device_context", "get_location_safety_status")))
    }

    @Test fun staleLcCallsCannotQueryUsageOrResetAnything() {
        val method = McpService::class.java.getDeclaredMethod("handleToolsCall", JSONObject::class.java, Any::class.java)
            .apply { isAccessible = true }
        val unattached = McpService()
        removed.forEach { name ->
            val call = JSONObject().put("params", JSONObject().put("name", name).put("arguments", JSONObject()))
            val result = JSONObject(method.invoke(unattached, call, 1) as String)
            val text = result.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
            assertEquals("未知工具：$name", text)
        }
    }
}
