package com.lover.connect

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompanionMemoryStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun readMissingDoesNotCreateFile() {
        assertEquals("记忆库为空", CompanionMemoryStore(directory.root).read())
        assertEquals(0, directory.root.listFiles()!!.size)
    }
    @Test fun newInstancesReuseExistingSameFileAndKeepOtherKeys() {
        val store = CompanionMemoryStore(directory.root)
        store.save("first", "one")
        CompanionMemoryStore(directory.root).save("second", "two")
        assertEquals("first = one", store.read("first"))
        assertEquals("second = two", store.read("second"))
        assertEquals(listOf("lc_memory.json"), directory.root.listFiles()!!.map { it.name })
    }
    @Test fun existingCorruptFileIsNeverReplacedByWrite() {
        val file = directory.root.resolve("lc_memory.json").apply { writeText("broken existing data") }
        val before = file.readBytes()
        try { CompanionMemoryStore(directory.root).save("new", "x"); fail("must reject corrupt file") } catch (_: Exception) { }
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun explicitImportIsAtomicAndExportReadsSameData() {
        val store = CompanionMemoryStore(directory.root)
        store.save("old", "original")
        store.importJson(ByteArrayInputStream("{\"imported\":\"synthetic\"}".toByteArray()))
        val exported = JSONObject(String(store.exportJson()!!))
        assertEquals("synthetic", exported.getString("imported"))
        assertFalse(exported.has("old"))
    }
    @Test fun badImportKeepsOriginal() {
        val store = CompanionMemoryStore(directory.root)
        store.save("old", "kept")
        try { store.importJson(ByteArrayInputStream("[not an object]".toByteArray())); fail("reject") } catch (_: Exception) { }
        assertEquals("old = kept", store.read("old"))
    }
    @Test fun oversizedImportKeepsOriginal() {
        val store = CompanionMemoryStore(directory.root)
        store.save("old", "kept")
        try { store.importJson(ByteArrayInputStream(ByteArray(CompanionMemoryStore.MAX_BYTES + 1))); fail("reject") } catch (_: Exception) { }
        assertEquals("old = kept", store.read("old"))
    }
    @Test fun oversizedEntryKeepsOriginalAndNoTempRemains() {
        val store = CompanionMemoryStore(directory.root)
        store.save("old", "kept")
        try { store.save("new", "x".repeat(128 * 1024 + 1)); fail("reject") } catch (_: Exception) { }
        assertEquals("old = kept", store.read("old"))
        assertEquals(listOf("lc_memory.json"), directory.root.listFiles()!!.map { it.name })
    }
    @Test fun parallelNativeAndMcpStoresDoNotLoseDifferentKeys() {
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (0..3).map { worker -> Thread {
            try {
                repeat(10) { index -> CompanionMemoryStore(directory.root).save("$worker-$index", "synthetic") }
            } catch (error: Throwable) { failures.add(error) }
        }.apply { start() } }
        workers.forEach { it.join(10_000); assertFalse(it.isAlive) }
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(40, JSONObject(String(CompanionMemoryStore(directory.root).exportJson()!!)).length())
    }
}
