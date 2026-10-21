package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupSettingsPrivacyTest {
    @Test fun removesConnectionSecretsWithoutRewritingChatOrQuestions() {
        val raw = """{"providers":[{"apiKey":"test-secret","baseUrl":"https://user:pass@host/v1?key=test-secret#secret","customHeaders":[{"value":"test-secret"}]}],"mcpServers":[{"commonOptions":{"headers":[["Authorization","test-secret"]],"oauth":{"refreshToken":"test-secret"}}}],"s3Config":{"endpoint":"https://host?token=test-secret","secretAccessKey":"test-secret"},"prompt":"a fictional password in a question","autoBackup":{"enabled":true,"localEnabled":true}}"""
        val clean = BackupSettingsPrivacy.sanitize(raw)
        assertFalse(clean.contains("test-secret")); assertFalse(clean.contains("user:pass"))
        val json = Json.parseToJsonElement(clean).jsonObject
        assertEquals("a fictional password in a question", json["prompt"]!!.jsonPrimitive.content)
        assertFalse(json["autoBackup"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }
    @Test fun localDefaultsAreOnRemoteDefaultsAreOff() {
        assertTrue(AutoBackupConfig().localEnabled); assertFalse(AutoBackupConfig().enabled)
        assertFalse(AutoBackupConfig().includeConnectionSecrets)
    }
    @Test fun removesGoogleServiceAccountPrivateKeysIncludingModelOverrides() {
        val raw = """{"providers":[{"privateKey":"root-private-secret","models":[{"providerOverwrite":{"privateKey":"override-private-secret","serviceAccountEmail":"account@example.invalid"}}]}],"prompt":"privateKey is a field name"}"""
        val clean = BackupSettingsPrivacy.sanitize(raw)
        assertFalse(clean.contains("root-private-secret"))
        assertFalse(clean.contains("override-private-secret"))
        assertTrue(clean.contains("account@example.invalid"))
        assertTrue(clean.contains("privateKey is a field name"))
    }
}
