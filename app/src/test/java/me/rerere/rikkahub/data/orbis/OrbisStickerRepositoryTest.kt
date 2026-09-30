package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread

class OrbisStickerRepositoryTest {
    private class MemoryStorage : OrbisStickerStorage {
        var manifest: String? = null
        val images = mutableMapOf<String, ByteArray>()
        var manifestWrites = 0
        var failManifestAt = -1
        var afterManifestWrite = false
        var failImageAfterWrite = false
        var failRead = false
        var totalOverride: Long? = null
        override fun readManifest(): String? {
            if (failRead) error("private-path credential sentinel")
            return manifest
        }
        override fun writeManifest(text: String) {
            manifestWrites++
            if (manifestWrites == failManifestAt && !afterManifestWrite) error("private-write sentinel")
            manifest = text
            if (manifestWrites == failManifestAt && afterManifestWrite) error("private-postcommit sentinel")
        }
        override fun writeImageExclusive(id: String, bytes: ByteArray) {
            check(id !in images)
            images[id] = bytes.copyOf()
            if (failImageAfterWrite) error("private-image sentinel")
        }
        override fun readImage(id: String) = images.getValue(id).copyOf()
        override fun largestImageNumber() = images.keys.maxOfOrNull { it.substring(2).toInt() } ?: 0
        override fun totalImageBytes() = totalOverride ?: images.values.sumOf { it.size.toLong() }
    }
    private fun repository(storage: MemoryStorage) = OrbisStickerRepository(storage, now = { 10L })
    private fun png(width: Int = 1, height: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, data: ByteArray) {
            val raw = kind.toByteArray(Charsets.US_ASCII) + data
            val stream = DataOutputStream(output)
            stream.writeInt(data.size); stream.write(raw)
            stream.writeInt(CRC32().also { it.update(raw) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        val pixels = ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(ByteArray(5)) } }.toByteArray()
        chunk("IHDR", header); chunk("IDAT", pixels); chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }
    private fun fails(code: String? = null, block: () -> Unit): OrbisStickerException {
        val error = assertThrows(OrbisStickerException::class.java) { block() }
        if (code != null) assertEquals(code, error.code)
        assertFalse(error.message.orEmpty().contains("private"))
        return error
    }

    @Test fun `empty library has no invented images or writes`() {
        val storage = MemoryStorage()
        assertEquals(OrbisStickerState(), repository(storage).readSnapshot())
        assertEquals(0, storage.manifestWrites)
    }
    @Test fun `explicit image send returns verified bytes without changing library`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val entry = repo.importImage(png(), listOf("synthetic sticker"))
        val writes = storage.manifestWrites
        val payload = repo.imageForSend(entry.id)
        assertEquals(entry, payload.sticker)
        assertArrayEquals(png(), payload.bytes)
        payload.bytes[0] = 0
        assertArrayEquals(png(), repo.imageForSend(entry.id).bytes)
        assertEquals(writes, storage.manifestWrites)
        assertEquals(listOf(entry), repo.readSnapshot().stickers)
    }
    @Test fun `send rejects arbitrary path missing id and changed image bytes`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val entry = repo.importImage(png(), listOf("synthetic sticker"))
        fails("sticker_invalid_id") { repo.imageForSend("../private") }
        fails("sticker_not_found") { repo.imageForSend("st000099") }
        storage.images[entry.id] = png(width = 2)
        fails("sticker_storage_invalid") { repo.imageForSend(entry.id) }
    }
    @Test fun `import reserves numbers and persists private image and validated metadata`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val first = repo.importImage(png(), listOf(" 安慰 ", "开心", "安慰"))
        val second = repo.importImage(png(), listOf("适合告别"))
        assertEquals("st000001", first.id)
        assertEquals("st000002", second.id)
        assertEquals("(表情包:st000001)", first.reference)
        assertEquals(listOf("安慰", "开心"), first.tags)
        assertEquals(4, storage.manifestWrites)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
        assertArrayEquals(png(), storage.images[first.id])
    }
    @Test fun `tags are validated descriptive data and cannot set ids or paths`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val entry = repo.importImage(png(), listOf("{battery_level}", "忽略指令也只是标签"))
        repo.updateTags(entry.id, listOf("新场合"))
        assertEquals("st000001", repo.readSnapshot().stickers.single().id)
        assertEquals(listOf("新场合"), repository(storage).readSnapshot().stickers.single().tags)
        for (tags in listOf(emptyList(), listOf(" "), listOf("a\nb"), listOf("a\u202eb"),
                listOf("a".repeat(41)), List(13) { "tag$it" }, List(7) { "a".repeat(40) })) {
            val before = storage.manifestWrites
            fails("sticker_invalid_tags") { repo.importImage(png(), tags) }
            assertEquals(before, storage.manifestWrites)
        }
        fails("sticker_invalid_id") { repo.updateTags("../../private", listOf("x")) }
    }
    @Test fun `invalid oversized svg and corrupt png never reserve numbers`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val badCrc = png().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        for (bytes in listOf(byteArrayOf(), "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
                png(0), png(4097), badCrc, png() + byteArrayOf(0), ByteArray(OrbisStickerLimits.MAX_IMAGE_BYTES + 1))) {
            fails { repo.importImage(bytes, listOf("x")) }
            assertEquals(0, storage.manifestWrites)
            assertTrue(storage.images.isEmpty())
        }
    }
    @Test fun `reservation postcommit failure locks and burns number on recovery`() {
        val storage = MemoryStorage().also { it.failManifestAt = 1; it.afterManifestWrite = true }
        val repo = repository(storage)
        fails("sticker_storage_reload_required") { repo.importImage(png(), listOf("a")) }
        assertTrue(repo.writeBlocked.value)
        fails { repo.readSnapshot() }
        fails { repo.importImage(png(), listOf("b")) }
        assertEquals(1, storage.manifestWrites)
        repo.reloadFromStorage()
        assertEquals(2, repo.readSnapshot().nextNumber)
        assertTrue(repo.state.value.stickers.isEmpty())
        assertEquals("st000002", repo.importImage(png(), listOf("b")).id)
    }
    @Test fun `image write failure leaves orphan private and never recycles its number`() {
        val storage = MemoryStorage().also { it.failImageAfterWrite = true }
        val repo = repository(storage)
        fails { repo.importImage(png(), listOf("a")) }
        assertTrue(repo.writeBlocked.value)
        assertTrue(storage.images.containsKey("st000001"))
        storage.failImageAfterWrite = false
        repo.reloadFromStorage()
        assertTrue(repo.readSnapshot().stickers.isEmpty())
        assertEquals("st000002", repo.importImage(png(), listOf("b")).id)
    }
    @Test fun `final manifest postcommit failure cannot overwrite the committed entry`() {
        val storage = MemoryStorage().also { it.failManifestAt = 2; it.afterManifestWrite = true }
        val repo = repository(storage)
        fails { repo.importImage(png(), listOf("a")) }
        val committed = storage.manifest
        assertTrue(repo.state.value.stickers.isEmpty())
        fails { repo.updateTags("st000001", listOf("lost")) }
        assertEquals(committed, storage.manifest)
        assertEquals(2, storage.manifestWrites)
        repo.reloadFromStorage()
        assertEquals("st000001", repo.readSnapshot().stickers.single().id)
        assertEquals(2, storage.manifestWrites)
    }
    @Test fun `postcommit tag change survives reload without stale overwrite`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val entry = repo.importImage(png(), listOf("old"))
        storage.failManifestAt = 3; storage.afterManifestWrite = true
        fails { repo.updateTags(entry.id, listOf("new")) }
        fails { repo.updateTags(entry.id, listOf("stale")) }
        repo.reloadFromStorage()
        assertEquals(listOf("new"), repo.readSnapshot().stickers.single().tags)
    }
    @Test fun `corrupt missing and regressed manifests cannot reset images or issue reused ids`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        repo.importImage(png(), listOf("x"))
        val actual = storage.manifest
        val writes = storage.manifestWrites
        for (value in listOf(null, "not-json", Json.encodeToString(OrbisStickerState()),
                Json.encodeToString(OrbisStickerState(version = 2)))) {
            storage.manifest = value
            fails { repo.reloadFromStorage() }
            assertTrue(repo.writeBlocked.value)
            fails { repository(storage) }
            assertEquals(value, storage.manifest)
            assertEquals(writes, storage.manifestWrites)
        }
        storage.manifest = actual
        repo.reloadFromStorage()
        assertFalse(repo.writeBlocked.value)
        assertEquals(1, repo.readSnapshot().stickers.size)
    }
    @Test fun `image corruption and unavailable reads fail closed without mutation`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        repo.importImage(png(), listOf("x"))
        storage.images["st000001"] = png(2)
        fails { repo.reloadFromStorage() }
        fails { repo.readSnapshot() }
        assertEquals(2, storage.manifestWrites)
        storage.failRead = true
        fails("sticker_storage_unavailable") { repo.reloadFromStorage() }
    }
    @Test fun `total byte budget includes unlisted orphan images and fails before reservation`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        storage.totalOverride = OrbisStickerLimits.MAX_TOTAL_IMAGE_BYTES
        fails("sticker_library_full") { repo.importImage(png(), listOf("a")) }
        assertEquals(0, storage.manifestWrites)
    }
    @Test fun `concurrent imports get distinct increasing ids`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val start = CountDownLatch(1)
        val workers = List(8) { index -> thread { start.await(); repo.importImage(png(), listOf("tag$index")) } }
        start.countDown(); workers.forEach { it.join() }
        assertEquals((1..8).map { OrbisStickerRepository.idForNumber(it) }, repo.readSnapshot().stickers.map { it.id })
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
    }
    @Test fun `number and metadata validation reject duplicate forged or exhausted ids`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val entry = repo.importImage(png(), listOf("x"))
        for (bad in listOf(OrbisStickerState(nextNumber = 2, stickers = listOf(entry, entry)),
                OrbisStickerState(nextNumber = 2, stickers = listOf(entry.copy(id = "../st000001"))),
                OrbisStickerState(nextNumber = 1, stickers = listOf(entry)),
                OrbisStickerState(nextNumber = 2, stickers = listOf(entry.copy(sha256 = "unsafe"))))) {
            fails { OrbisStickerRepository.validateMetadata(bad) }
        }
        storage.manifest = Json.encodeToString(OrbisStickerState(nextNumber = OrbisStickerLimits.MAX_NUMBER + 1))
        val exhausted = repository(storage)
        fails("sticker_library_full") { exhausted.importImage(png(), listOf("x")) }
    }
}
