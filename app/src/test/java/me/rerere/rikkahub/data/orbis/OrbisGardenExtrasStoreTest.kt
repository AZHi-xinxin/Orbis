package me.rerere.rikkahub.data.orbis

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrbisGardenExtrasStoreTest {
    private class Memory : OrbisGardenExtrasPersistence {
        val documents = mutableMapOf<String, ByteArray>()
        var reads = 0
        var writes = 0
        var failRead = false
        var failWrite = false
        var dropWrite = false

        override fun read(module: String): ByteArray? {
            reads++
            if (failRead) throw IOException("synthetic private read detail")
            return documents[module]?.copyOf()
        }

        override fun write(module: String, value: ByteArray) {
            writes++
            if (failWrite) throw IOException("synthetic private write detail")
            if (!dropWrite) documents[module] = value.copyOf()
        }
    }

    private fun data(name: String) = buildJsonObject { put("name", name) }

    @Test fun `fresh modules are empty without creating files and only strict names are allowed`() = runBlocking {
        val disk = Memory()
        val store = OrbisGardenExtrasStore(disk)
        assertEquals(0, store.load("library").revision)
        assertEquals(emptyMap<String, Any>(), store.load("soup").data)
        listOf("../library", "Library", "library/other", "", "chat", "library ").forEach { module ->
            assertNotNull(runCatching { store.load(module) }.exceptionOrNull())
        }
        assertEquals(0, disk.writes)
        assertTrue(disk.documents.isEmpty())
    }

    @Test fun `save is durable CAS and modules remain isolated`() = runBlocking {
        val disk = Memory()
        val store = OrbisGardenExtrasStore(disk)
        val first = store.save("library", 0, data("first"))
        assertEquals(1, first.revision)
        assertEquals("first", first.data["name"]?.toString()?.trim('"'))
        val soup = store.save("soup", 0, data("case"))
        assertEquals(1, soup.revision)
        assertEquals(first, OrbisGardenExtrasStore(disk).load("library"))
        val second = store.save("library", first.revision, data("second"))
        assertEquals(2, second.revision)
        assertEquals(2, store.load("library").revision)
        assertEquals(1, store.load("soup").revision)
    }

    @Test fun `stale writer cannot replace newer data`() = runBlocking {
        val disk = Memory()
        val firstWindow = OrbisGardenExtrasStore(disk)
        val secondWindow = OrbisGardenExtrasStore(disk)
        val baseline = firstWindow.save("library", 0, data("baseline"))
        val latest = secondWindow.save("library", baseline.revision, data("latest"))
        val before = disk.documents.getValue("library").copyOf()
        val failure = runCatching { firstWindow.save("library", baseline.revision, data("stale")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("revision_changed"))
        assertTrue(before.contentEquals(disk.documents.getValue("library")))
        assertEquals(latest, firstWindow.load("library"))
    }

    @Test fun `strict envelope rejects malformed mismatched and unknown storage without rewrite`() = runBlocking {
        val bad = listOf(
            "not json",
            "[]",
            "{}",
            """{"version":1,"module":"soup","revision":1,"data":{}}""",
            """{"version":2,"module":"library","revision":1,"data":{}}""",
            """{"version":1,"module":"library","revision":0,"data":{}}""",
            """{"version":1,"module":"library","revision":1,"data":{},"extra":true}""",
        )
        bad.forEach { value ->
            val disk = Memory().apply { documents["library"] = value.toByteArray() }
            val store = OrbisGardenExtrasStore(disk)
            assertNotNull(runCatching { store.load("library") }.exceptionOrNull())
            assertNotNull(runCatching { store.save("library", 0, data("replacement")) }.exceptionOrNull())
            assertEquals(value, decodeGardenExtrasUtf8(disk.documents.getValue("library")))
            assertEquals(0, disk.writes)
        }
    }

    @Test fun `invalid UTF8 and oversized documents fail closed`() = runBlocking {
        val invalid = Memory().apply { documents["library"] = byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d) }
        assertNotNull(runCatching { OrbisGardenExtrasStore(invalid).load("library") }.exceptionOrNull())

        val oversized = Memory().apply {
            documents["library"] = ByteArray(OrbisGardenExtrasStore.MAX_DOCUMENT_BYTES + 1) { ' '.code.toByte() }
        }
        assertNotNull(runCatching { OrbisGardenExtrasStore(oversized).load("library") }.exceptionOrNull())

        val disk = Memory()
        val huge = buildJsonObject { put("text", "x".repeat(OrbisGardenExtrasStore.MAX_DOCUMENT_BYTES)) }
        assertNotNull(runCatching { OrbisGardenExtrasStore(disk).save("library", 0, huge) }.exceptionOrNull())
        assertEquals(0, disk.writes)
    }

    @Test fun `dropped or failed writes never report a committed revision`() = runBlocking {
        listOf(
            Memory().apply { dropWrite = true },
            Memory().apply { failWrite = true },
        ).forEach { disk ->
            val failure = runCatching { OrbisGardenExtrasStore(disk).save("library", 0, data("draft")) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("garden_extras_write_failed", failure!!.message)
            assertFalse(failure.cause?.message.orEmpty().contains("private"))
        }
    }

    @Test fun `read failure and disappearance cannot become a fresh writable document`() = runBlocking {
        val disk = Memory()
        val store = OrbisGardenExtrasStore(disk)
        store.save("library", 0, data("kept"))
        disk.documents.remove("library")
        assertNotNull(runCatching { store.load("library") }.exceptionOrNull())
        assertNotNull(runCatching { store.save("library", 0, data("replacement")) }.exceptionOrNull())

        val failed = Memory().apply { failRead = true }
        val failedStore = OrbisGardenExtrasStore(failed)
        val failure = runCatching { failedStore.load("library") }.exceptionOrNull()
        assertEquals("garden_extras_read_failed", failure?.message)
        failed.failRead = false
        assertNotNull(runCatching { failedStore.save("library", 0, data("replacement")) }.exceptionOrNull())
        assertEquals(0, failed.writes)
    }

    @Test fun `persisted JSON contains only module document fields and never chat or credential state`() = runBlocking {
        val disk = Memory()
        OrbisGardenExtrasStore(disk).save("library", 0, data("local book"))
        val document = Json.parseToJsonElement(decodeGardenExtrasUtf8(disk.documents.getValue("library"))).jsonObject
        assertEquals(setOf("version", "module", "revision", "data"), document.keys)
        val raw = document.toString()
        listOf("conversation", "assistant", "apiKey", "token", "workspace").forEach { forbidden ->
            assertFalse(raw.contains(forbidden, ignoreCase = true))
        }
    }
}
