package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import java.net.URI

/** Only connection structures are scrubbed; questions, answers and prompts are never rewritten. */
internal object BackupSettingsPrivacy {
    private val connectionFields = setOf("providers", "ttsProviders", "asrProviders", "mcpServers", "searchServices", "networkSetting", "webDavConfig", "s3Config")
    private val secrets = setOf("apikey", "accesskey", "accesskeyid", "secretaccesskey", "accesskeysecret", "appkey", "appsecret", "password", "proxypassword", "proxyusername", "accesstoken", "refreshtoken", "clientsecret", "token", "secretkey", "secret", "privatekey", "private_key")
    fun sanitize(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        return JsonObject(root.mapValues { (key, value) -> when {
            key in connectionFields -> scrub(value)
            key == "webServerAccessPassword" -> JsonPrimitive("")
            key == "webServerEnabled" -> JsonPrimitive(false)
            key == "assistants" -> JsonArray(value.jsonArray.map { assistant -> JsonObject(assistant.jsonObject.mapValues { (k,v) ->
                if(k == "customHeaders" || k == "customBodies") JsonArray(emptyList()) else v
            }) })
            // A restored archive must not silently start uploading to its old destination.
            key == "autoBackup" -> JsonObject(value.jsonObject + ("enabled" to JsonPrimitive(false)))
            else -> value
        } }).toString()
    }
    private fun scrub(value: JsonElement): JsonElement = when(value) {
        is JsonArray -> JsonArray(value.map(::scrub))
        is JsonObject -> JsonObject(value.mapValues { (key, v) -> when {
            key.lowercase() in secrets -> if(v is JsonNull) v else JsonPrimitive("")
            key in setOf("oauth", "chatGptAccountId") -> JsonNull
            key in setOf("headers", "customHeaders", "customBodies") -> JsonArray(emptyList())
            (key.lowercase().endsWith("url") || key.lowercase().endsWith("endpoint")) && v is JsonPrimitive && v.isString -> JsonPrimitive(cleanUrl(v.content))
            else -> scrub(v)
        } })
        else -> value
    }
    private fun cleanUrl(value: String): String = runCatching {
        val uri = URI(value)
        if(uri.scheme in setOf("https", "http")) URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        else ""
    }.getOrDefault("")
}
