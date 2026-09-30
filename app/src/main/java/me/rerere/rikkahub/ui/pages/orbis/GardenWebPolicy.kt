package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.decodeGardenExtrasUtf8

/** Exact local assets only; never an old website script, network URL or arbitrary app file. */
internal object GardenAssetPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val HOME = "$ORIGIN/assets/orbis-garden/index.html"
    private val names = setOf("index.html", "original.css", "local.css", "garden.js", "library.js")
    fun asset(url: String): Boolean = names.any { url == "$ORIGIN/assets/orbis-garden/$it" }
    fun mainFrame(url: String?): Boolean = url == HOME
}

internal object GardenLibraryBackup {
    const val MAX_BYTES = me.rerere.rikkahub.data.orbis.GardenBookImport.MAX_LIBRARY_BYTES + 4096
    private const val FORMAT = "orbis-local-library/1"
    fun encode(data: JsonObject): ByteArray {
        require(data.keys == setOf("version", "books") && data["version"] == JsonPrimitive(1))
        require(data["books"] is JsonArray)
        return buildJsonObject { put("format", FORMAT); put("data", data) }.toString()
            .toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size <= MAX_BYTES)
        val value = Json.parseToJsonElement(decodeGardenExtrasUtf8(bytes).removePrefix("\uFEFF")).jsonObject
        require(value.keys == setOf("format", "data") && value["format"] == JsonPrimitive(FORMAT))
        val data = value.getValue("data").jsonObject
        encode(data) // Envelope validation only; the reader validates every field before explicit CAS merge.
        return data
    }
}
