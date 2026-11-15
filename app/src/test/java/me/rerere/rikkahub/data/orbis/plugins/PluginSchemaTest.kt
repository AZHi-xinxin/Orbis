package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PluginSchemaTest {
    private fun json(s: String)=pluginJson.parseToJsonElement(s).jsonObject
    @Test fun validatesRequiredTypesBoundsAndExtraFields() {
        val schema=json("""{"type":"object","properties":{"n":{"type":"integer","minimum":1,"maximum":4},"text":{"type":"string","maxLength":4}},"required":["n"],"additionalProperties":false}""")
        PluginSchema.validateSchema(schema);PluginSchema.validateArguments(schema,json("""{"n":2,"text":"好"}"""))
        listOf("{}","""{"n":"2"}""","""{"n":2.5}""","""{"n":9}""","""{"n":2,"x":true}""","""{"n":2,"text":"12345"}""").forEach {
            assertThrows(it,IllegalArgumentException::class.java) {PluginSchema.validateArguments(schema,json(it))}
        }
    }
    @Test fun unsupportedSchemaCannotCreateFalseSecurityPromise() {
        listOf("""{"type":"object","pattern":".*"}""","""{"type":"object","allOf":[]}""",
            """{"type":"object","properties":{"x":{"type":"string","format":"url"}}}""").forEach {
            assertThrows(IllegalArgumentException::class.java) {PluginSchema.validateSchema(json(it))}
        }
    }
    @Test fun unknownPermissionsAndImplicitNetworkFail() {
        val body=pluginJson.decodeFromString<PluginManifest>(PLUGIN_EXAMPLE)
        assertThrows(IllegalArgumentException::class.java) {body.copy(permissions=listOf("root")).validate()}
        assertThrows(IllegalArgumentException::class.java) {body.copy(permissions=listOf("network")).validate()}
        assertThrows(IllegalArgumentException::class.java) {body.copy(permissions=listOf("network"),networkHosts=listOf("localhost")).validate()}
        body.copy(permissions=listOf("network"),networkHosts=listOf("example.com")).validate()
    }
    @Test fun publicNetworkFilterRejectsLocalAndTailnet() {
        listOf("127.0.0.1","10.0.0.1","172.16.0.1","192.168.0.1","100.100.96.49","169.254.169.254","::1","fc00::1","fe80::1").forEach {
            assertFalse(it,publicPluginAddress(InetAddress.getByName(it)))
        }
        assertTrue(publicPluginAddress(InetAddress.getByName("1.1.1.1")))
    }
}
