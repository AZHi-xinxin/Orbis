package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCacheIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun key(file: File, owner: String = "owner", prompt: String = "describe",
                    model: String = "vision-1", provider: String = "endpoint+synthetic-key") =
        OcrCacheIdentity.key(file, owner, prompt, model, provider)

    @Test fun sameBytesAndConfigurationAreStableWithoutLeakingInputs() {
        val file = temp.newFile("private-image.png").apply { writeText("synthetic image") }
        val key = key(file)
        assertEquals(key, key(file))
        assertTrue(key.matches(Regex("ocr-v2:[0-9a-f]{64}")))
        assertFalse(key.contains("synthetic"))
        assertFalse(key.contains(file.path))
    }

    @Test fun samePathWithDifferentBytesNeverReusesCacheEvenWithSameLengthAndTimestamp() {
        val file = temp.newFile().apply { writeText("AAAA") }
        val time = file.lastModified()
        val before = key(file)
        file.writeText("BBBB")
        file.setLastModified(time)
        assertNotEquals(before, key(file))
    }

    @Test fun everyRecognitionConfigurationAndAssistantBoundaryInvalidatesCache() {
        val file = temp.newFile().apply { writeText("synthetic") }
        val initial = key(file)
        assertNotEquals(initial, key(file, owner = "other-assistant"))
        assertNotEquals(initial, key(file, prompt = "transcribe"))
        assertNotEquals(initial, key(file, model = "vision-2"))
        assertNotEquals(initial, key(file, provider = "other-endpoint+synthetic-key"))
        assertNotEquals(initial, key(file, provider = "endpoint+rotated-synthetic-key"))
    }

    @Test fun fieldBoundariesAreUnambiguous() {
        val file = temp.newFile()
        assertNotEquals(key(file, owner = "ab", prompt = "c"), key(file, owner = "a", prompt = "bc"))
    }

    @Test fun pathAloneDoesNotDetermineImageIdentity() {
        val a = temp.newFile().apply { writeText("same content") }
        val b = temp.newFile().apply { writeText("same content") }
        assertEquals(key(a), key(b))
        assertNotEquals(a.toURI().toString(), key(a))
    }

    @Test fun missingFileIsNeverACacheHit() {
        assertThrows(IllegalArgumentException::class.java) { key(File(temp.root, "absent.png")) }
    }

    @Test fun hashingHonorsCancellation() {
        val file = temp.newFile()
        assertThrows(CancellationException::class.java) {
            OcrCacheIdentity.key(file, "owner", "prompt", "model", "provider") { throw CancellationException() }
        }
    }

    @Test fun emptyAndFailurePlaceholdersAreNotCacheable() {
        listOf("", " \n", "[Image]", "[ERROR, OCR failed]", "[ERROR, OCR failed: synthetic]").forEach {
            assertNull(OcrCacheIdentity.cacheableText(it))
        }
        assertEquals("一只猫", OcrCacheIdentity.cacheableText("一只猫"))
    }
}
