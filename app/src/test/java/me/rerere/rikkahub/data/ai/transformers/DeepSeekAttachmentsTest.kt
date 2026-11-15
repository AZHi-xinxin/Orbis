package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.*
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DeepSeekAttachmentsTest {
    @Test fun `successful identical uploads are deduplicated across instances and concurrent conversations`() = runBlocking {
        val root=Files.createTempDirectory("ds-cache").toFile()
        try {
            val file=root.resolve("file.pdf").apply{writeText("synthetic document")};var calls=0
            val first=async { DeepSeekAttachmentCache(root).get(file,"files_rich","key-a") { calls++;delay(10);"file-api-synthetic" } }
            val second=async { DeepSeekAttachmentCache(root).get(file,"files_rich","key-a") { error("duplicate upload") } }
            assertEquals(first.await(),second.await());assertEquals(1,calls)
            assertEquals("new-account",DeepSeekAttachmentCache(root).get(file,"files_rich","key-b") { "new-account" })
            assertFalse(root.resolve("deepseek_attachment_cache").listFiles()!!.any { it.readText().contains("key-a") })
        } finally { root.deleteRecursively() }
    }
    @Test fun `uncertain upload survives process recreation and needs explicit retry`() = runBlocking {
        val root=Files.createTempDirectory("ds-intent").toFile()
        try {
            val file=root.resolve("a.pdf").apply{writeText("synthetic")}
            try { DeepSeekAttachmentCache(root).get(file,"ocr","key") { error("connection lost") };fail() } catch (_:IllegalStateException) {}
            try { DeepSeekAttachmentCache(root).get(file,"ocr","key") { fail("must not replay");"" };fail() } catch (_:IllegalStateException) {}
            assertEquals(1,DeepSeekAttachmentCache(root).allowRetry())
            assertEquals("ok",DeepSeekAttachmentCache(root).get(file,"ocr","key") { "ok" })
            assertEquals(0,DeepSeekAttachmentCache(root).allowRetry())
        } finally { root.deleteRecursively() }
    }
    @Test fun `native vision images and zip archives bypass OCR`() {
        val image=UIMessagePart.Image("file:///image.jpg")
        assertFalse(DeepSeekAttachmentTransformer.eligible(image,"ocr",listOf(Modality.IMAGE)))
        assertTrue(DeepSeekAttachmentTransformer.eligible(image,"ocr",listOf(Modality.TEXT)))
        assertFalse(DeepSeekAttachmentTransformer.eligible(UIMessagePart.Document("file:///a.zip","a.zip"),"files_rich",emptyList()))
        assertEquals("local",DeepSeekAttachmentSettings().mode)
    }
    @Test fun `OCR checks error envelopes and marks incomplete pages`() {
        assertThrows(IllegalStateException::class.java) { parseDeepSeekOcr("{\"error\":{\"message\":\"timeout\"}}") }
        assertThrows(IllegalStateException::class.java) { parseDeepSeekOcr("{\"page_count\":2,\"pages\":[]}") }
        val result=parseDeepSeekOcr("""{"page_count":1,"pages":[{"index":0,"text":"<｜ref｜>标题<｜/ref｜><｜box｜>[[1,2,3,4]]<｜/box｜>","finish_reason":"length"}]}""")
        assertTrue(result.contains("标题"));assertTrue(result.contains("可能不完整"));assertFalse(result.contains("[["))
    }
}
