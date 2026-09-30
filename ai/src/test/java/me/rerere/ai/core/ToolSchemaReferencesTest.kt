package me.rerere.ai.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolSchemaReferencesTest {
    private fun schema(text: String) = Json.parseToJsonElement(text.trimIndent().replace("DOLLAR", "\$"))
    private fun check(text: String) = schema(text).validateToolSchemaReferences("fixture_tool")
    private fun rejected(text: String, reason: String) {
        val error = assertThrows(InvalidToolSchemaException::class.java) { check(text) }
        assertEquals(reason, error.reasonCode)
        assertEquals("fixture_tool", error.toolName)
        assertFalse(error.message.orEmpty().contains("secret-fixture"))
    }

    @Test fun `definitions survive persisted InputSchema serialization`() {
        val properties = schema("""{"input":{"DOLLARref":"#/DOLLARdefs/ReviewDecisionIn","description":"review"}}""").jsonObject
        val defs = schema("""{"ReviewDecisionIn":{"type":"object","properties":{"decision":{"enum":["approve","reject"]}},"required":["decision"],"additionalProperties":false}}""").jsonObject
        val original: InputSchema = InputSchema.Obj(properties, listOf("input"), defs, "https://json-schema.org/draft/2020-12/schema")
        val encoded = Json.encodeToString(original)
        assertEquals(original, Json.decodeFromString<InputSchema>(encoded))
        val wire = Json.parseToJsonElement(encoded)
        assertEquals(defs, wire.jsonObject["\$defs"])
        assertSame(wire, wire.validateToolSchemaReferences("review_drift_bottles"))
    }

    @Test fun `old persisted object without defs still decodes`() {
        val old = Json.decodeFromString<InputSchema>("""{"type":"object","properties":{"q":{"type":"string"}}}""") as InputSchema.Obj
        assertNull(old.defs)
        assertNull(old.schema)
        assertFalse(Json.encodeToString<InputSchema>(old).contains("\$defs"))
        assertEquals("Obj(properties={\"q\":{\"type\":\"string\"}}, required=null)", old.toString())
    }

    @Test fun `missing definition reports local error without argument or schema contents`() {
        rejected("""{"type":"object","properties":{"input":{"DOLLARref":"#/DOLLARdefs/InboxInput","description":"secret-fixture"}}}""", "missing_local_reference")
    }

    @Test fun `self recursive definitions are preserved not expanded`() {
        val original = schema("""{"type":"object","properties":{"root":{"DOLLARref":"#/DOLLARdefs/Node"}},"DOLLARdefs":{"Node":{"type":"object","properties":{"next":{"anyOf":[{"DOLLARref":"#/DOLLARdefs/Node"},{"type":"null"}]}},"additionalProperties":false}}}""")
        assertSame(original, original.validateToolSchemaReferences("recursive"))
    }

    @Test fun `root recursive schema is preserved`() {
        check("""{"type":"object","properties":{"child":{"DOLLARref":"#"}}}""")
    }

    @Test fun `mutually recursive definitions do not loop`() {
        check("""{"type":"object","DOLLARdefs":{"A":{"DOLLARref":"#/DOLLARdefs/B"},"B":{"DOLLARref":"#/DOLLARdefs/A"}},"properties":{"a":{"DOLLARref":"#/DOLLARdefs/A"}}}""")
    }

    @Test fun `one tool cannot borrow definitions from another`() {
        check("""{"type":"object","DOLLARdefs":{"Input":{"type":"string"}},"properties":{"a":{"DOLLARref":"#/DOLLARdefs/Input"}}}""")
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":"#/DOLLARdefs/Input"}}}""", "missing_local_reference")
    }

    @Test fun `pointer escapes and percent encoding are resolved without modifying wire`() {
        check("""{"type":"object","DOLLARdefs":{"a/b~c+d e":{"type":"string"}},"properties":{"a":{"DOLLARref":"#/DOLLARdefs/a~1b~0c+d%20e"}}}""")
    }

    @Test fun `array pointer and boolean schema targets are supported`() {
        check("""{"type":"object","allOf":[false],"properties":{"a":{"DOLLARref":"#/allOf/0"}}}""")
    }

    @Test fun `ref siblings constraints are unchanged`() {
        val original = schema("""{"type":"object","DOLLARdefs":{"Text":{"type":"string"}},"properties":{"a":{"DOLLARref":"#/DOLLARdefs/Text","maxLength":4,"pattern":"^A"}}}""")
        assertSame(original, original.validateToolSchemaReferences("siblings"))
    }

    @Test fun `ordinary ref keys in examples defaults const and enum are data`() {
        check("""{"type":"object","properties":{"a":{"type":"object","default":{"DOLLARref":"missing"},"examples":[{"DOLLARref":"missing"}],"const":{"DOLLARref":"missing"},"enum":[{"DOLLARref":"missing"}]}}}""")
    }

    @Test fun `refs in array items and union branches are checked`() {
        rejected("""{"type":"object","properties":{"a":{"type":"array","items":{"anyOf":[{"DOLLARref":"#/DOLLARdefs/Missing"}]}}}}""", "missing_local_reference")
    }

    @Test fun `targeted schema outside standard keyword is still checked`() {
        rejected("""{"type":"object","default":{"DOLLARref":"#/DOLLARdefs/Missing"},"properties":{"a":{"DOLLARref":"#/default"}}}""", "missing_local_reference")
    }

    @Test fun `external references are explicitly unsupported`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":"https://example.invalid/secret-fixture"}}}""", "unsupported_reference")
    }

    @Test fun `anchor references are explicitly unsupported`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":"#anchor"}}}""", "unsupported_reference")
    }

    @Test fun `dynamic references are explicitly unsupported`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARdynamicRef":"#anchor"}}}""", "unsupported_dynamic_reference")
    }

    @Test fun `nested ids cannot silently change reference base`() {
        rejected("""{"type":"object","DOLLARdefs":{"a":{"DOLLARid":"https://example.invalid/secret-fixture","type":"string"}}}""", "unsupported_nested_schema_id")
    }

    @Test fun `malformed tilde pointer is rejected`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":"#/DOLLARdefs/a~2"}}}""", "invalid_reference_pointer")
    }

    @Test fun `malformed percent pointer is rejected`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":"#/DOLLARdefs/%xx"}}}""", "invalid_reference_pointer")
    }

    @Test fun `non string ref is rejected`() {
        rejected("""{"type":"object","properties":{"a":{"DOLLARref":7}}}""", "invalid_reference")
    }

    @Test fun `non schema ref target is rejected`() {
        rejected("""{"type":"object","title":"secret-fixture","properties":{"a":{"DOLLARref":"#/title"}}}""", "reference_target_not_schema")
    }

    @Test fun `null parameter behavior is unchanged`() {
        assertSame(JsonNull, JsonNull.validateToolSchemaReferences("empty"))
    }

    @Test fun `excessive nesting fails safely`() {
        var nested: JsonObject = schema("""{"type":"string"}""").jsonObject
        repeat(130) { nested = JsonObject(mapOf("not" to nested)) }
        val error = assertThrows(InvalidToolSchemaException::class.java) { nested.validateToolSchemaReferences("large") }
        assertEquals("schema_complexity_limit", error.reasonCode)
    }
}
