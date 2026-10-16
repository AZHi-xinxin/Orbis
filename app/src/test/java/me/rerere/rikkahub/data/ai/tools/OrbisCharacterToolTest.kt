package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.character.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisCharacterToolTest {
    @Test fun getAcceptsEmptyPlaceholdersButWritesAndIdsRemainStrict() {
        val s = store()
        assertTrue(call(s, """{"action":"get","region_id":"","text":"","asset_id":"","expected_revision":0,"duration_seconds":0,"lines":[],"allowed":[]}""").getValue("ok").jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java) { call(s, """{"action":"get","asset_id":"calm"}""") }
        assertThrows(IllegalArgumentException::class.java) { call(s, """{"action":"set_greeting","text":"","expected_revision":0}""") }
    }
    @get:Rule val folder = TemporaryFolder()
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private fun store(): CharacterStore {
        val store = CharacterStore(folder.root, owner)
        val bytes = "synthetic-test-pixels".toByteArray()
        val a = CharacterAsset("calm", CharacterAssetKind.EXPRESSION, "calm", characterSha(bytes), 100, 100)
        store.install(CharacterDraft(CharacterPack(sha256 = characterSha(bytes), width = 100, height = 100), CharacterInteractions(), bytes,
            CharacterPresentation(assets = listOf(a)), mapOf(a.sha256 to bytes)), 0)
        return store
    }
    private fun call(store: CharacterStore, raw: String) = executeCharacterTool(store, Json.parseToJsonElement(raw), 1000)
    @Test fun getHasNoBinaryOrIdentity() { val out = call(store(), """{"action":"get"}""").toString(); assertFalse(out.contains(owner)); assertFalse(out.contains("sha256")); assertTrue(out.contains("revision")) }
    @Test fun writesOnlyOneRegion() { val s = store(); call(s, """{"action":"set_lines","expected_revision":1,"region_id":"head","lines":["hello"]}"""); assertEquals(listOf("hello"), s.read().interactions.lines["head"]) }
    @Test fun noCrossOwnerArgument() { assertThrows(IllegalArgumentException::class.java) { call(store(), """{"action":"get","owner":"anything"}""") } }
    @Test fun staleRevisionNeverWins() { val s = store(); assertThrows(IllegalArgumentException::class.java) { call(s, """{"action":"set_greeting","expected_revision":0,"text":"x"}""") }; assertEquals("", s.read().presentation.greeting) }
    @Test fun unknownRegionRejects() { assertThrows(IllegalArgumentException::class.java) { call(store(), """{"action":"set_lines","expected_revision":1,"region_id":"unknown","lines":["x"]}""") } }
    @Test fun stringRevisionRejects() { assertThrows(IllegalStateException::class.java) { call(store(), """{"action":"set_greeting","expected_revision":"1","text":"x"}""") } }
    @Test fun boundedPolicyExpiry() { val s = store(); call(s, """{"action":"set_expression_policy","expected_revision":1,"allowed":["calm"],"duration_seconds":60}"""); assertEquals(61000L, s.read().presentation.expressionPolicy!!.expiresAt) }
    @Test fun missingExpressionCannotLockBlankSprite() { assertThrows(IllegalArgumentException::class.java) { call(store(), """{"action":"set_expression_policy","expected_revision":1,"allowed":["angry"],"duration_seconds":60}""") } }
    @Test fun infinitePolicyRejects() { assertThrows(IllegalArgumentException::class.java) { call(store(), """{"action":"set_expression_policy","expected_revision":1,"allowed":["calm"],"duration_seconds":86401}""") } }
    @Test fun unknownSpriteRejects() { assertThrows(IllegalArgumentException::class.java) { call(store(), """{"action":"set_sprite","expected_revision":1,"asset_id":"outside"}""") } }
}
