package me.rerere.rikkahub.data.orbis.toy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Pure contract tests; no Bluetooth, Android context or physical action. */
class ToyLevelContractTest {
    @Test fun `schema exposes exactly six integer choices including stop`() {
        assertEquals(setOf("level"), ToyLevelContract.properties.keys)
        val property = ToyLevelContract.properties.getValue("level").jsonObject
        assertEquals("integer", property.getValue("type").jsonPrimitive.content)
        assertEquals(0, property.getValue("minimum").jsonPrimitive.int)
        assertEquals(5, property.getValue("maximum").jsonPrimitive.int)
        assertEquals((0..5).toList(), property.getValue("enum").jsonArray.map { it.jsonPrimitive.int })
        assertTrue(property.getValue("description").jsonPrimitive.content.contains("不是频率"))
    }

    @Test fun `all advertised levels parse and only zero bypasses action approval`() {
        for (level in 0..5) {
            val input = Json.parseToJsonElement("{\"level\":$level}")
            assertEquals(level, ToyLevelContract.parse(input))
            assertEquals(level != 0, ToyLevelContract.needsApproval(input))
        }
    }

    @Test fun `legacy percent and invalid shapes fail explicitly instead of being quantized`() {
        val invalid = listOf(
            "{\"level\":30}", "{\"level\":35}", "{\"level\":6}", "{\"level\":-1}",
            "{\"level\":100}", "{\"level\":2147483648}", "{\"level\":1.5}", "{\"level\":1.0}",
            "{\"level\":\"1\"}", "{\"level\":\"0\"}", "{\"level\":null}", "{\"level\":true}",
            "{\"level\":[]}", "{\"level\":{}}", "{\"intensity\":30}", "{\"intensity\":0}",
            "{\"level\":1,\"intensity\":20}", "{\"level\":0,\"frequency\":35}",
            "{\"level\":1,\"mode\":0}", "{}", "[]", "null", "0", "\"1\""
        )
        for (json in invalid) {
            val input = Json.parseToJsonElement(json)
            val error = assertThrows(json, IllegalArgumentException::class.java) { ToyLevelContract.parse(input) }
            assertEquals(ToyLevelContract.GUIDE, error.message)
            assertTrue(ToyLevelContract.needsApproval(input))
        }
    }

    @Test fun `status exposes only the unambiguous level contract and no physical verification`() {
        val status = Json.parseToJsonElement(ToyState(connected = true, deviceNames = listOf("fixture"),
            level = 2, requestedIntensity = 40).asToolJson()).jsonObject
        assertEquals("level", status.getValue("input_field").jsonPrimitive.content)
        assertEquals(2, status.getValue("requested_level").jsonPrimitive.int)
        assertEquals((0..5).toList(), status.getValue("allowed_levels").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(status.containsKey("requested_intensity"))
        assertFalse(status.getValue("frequency_control_supported").jsonPrimitive.boolean)
        assertFalse(status.getValue("pattern_control_supported").jsonPrimitive.boolean)
        assertFalse(status.getValue("physical_state_verified").jsonPrimitive.boolean)
        assertTrue(status.getValue("continuous").jsonPrimitive.boolean)
    }
}
