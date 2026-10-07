package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class StMemoryImageFileReaderTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1sAAAAASUVORK5CYII=")

    @Test fun `original bytes are preserved and outside paths are rejected`() {
        val temp = Files.createTempDirectory("st-image-source-test").toFile()
        try {
            val uploads = temp.resolve("upload").apply { mkdir() }
            val original = uploads.resolve("original.png").apply { writeBytes(png) }
            val chosen = resolveStImageUploadFile(original.toURI().toString(), uploads)
            val result = readBoundedStImageOriginal(chosen)
            assertArrayEquals(png, result.bytes)
            assertEquals("image/png", result.mimeType)
            val outside = temp.resolve("not-upload.png").apply { writeBytes(png) }
            for (url in listOf(outside.toURI().toString(), "https://example.invalid/image.png",
                original.toURI().toString() + "?other=true", "file://server/share/image.png")) {
                try { resolveStImageUploadFile(url, uploads); fail("must reject $url") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { temp.deleteRecursively() }
    }

    @Test fun `oversize and disguised non-image input never reach staging`() {
        val temp = Files.createTempDirectory("st-image-bounds-test").toFile()
        try {
            val file = temp.resolve("image.png")
            file.writeText("not an image")
            try { readBoundedStImageOriginal(file); fail("must reject") } catch (_: IllegalStateException) { }
            file.writeBytes(ByteArray(ST_IMAGE_MAX_BYTES + 1))
            try { readBoundedStImageOriginal(file); fail("must reject") } catch (_: IllegalArgumentException) { }
        } finally { temp.deleteRecursively() }
    }
}
