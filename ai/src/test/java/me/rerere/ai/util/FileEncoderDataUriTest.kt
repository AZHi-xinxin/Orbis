package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class FileEncoderDataUriTest {
    private val payload = "AQIDBA=="

    @Test fun openAiReceivesTheCompleteDataUri() {
        val uri = "data:image/jpeg;base64,$payload"
        assertEquals(EncodedImage(uri, "image/jpeg"), UIMessagePart.Image(uri).encodeBase64().getOrThrow())
    }

    @Test fun claudeAndGeminiReceiveOnlyBase64WhenPrefixIsFalse() {
        val uri = "data:image/jpeg;base64,$payload"
        assertEquals(EncodedImage(payload, "image/jpeg"), UIMessagePart.Image(uri).encodeBase64(false).getOrThrow())
    }

    @Test fun existingImageTypesKeepTheirMimeType() {
        listOf("image/png", "image/gif", "image/webp").forEach { mime ->
            assertEquals(EncodedImage(payload, mime), encodeImageDataUri("data:$mime;base64,$payload", false))
        }
    }

    @Test fun metadataParametersAreNotPartOfThePayload() {
        val uri = "data:image/png;charset=utf-8;base64,$payload"
        assertEquals(EncodedImage(payload, "image/png"), encodeImageDataUri(uri, false))
        assertEquals(uri, encodeImageDataUri(uri, true).base64)
    }

    @Test fun malformedUriErrorsDoNotEchoTheImageOrItsPayload() {
        listOf("data:image/jpeg;base64$payload", "data:image/jpeg;base64,",
            "data:image/jpeg,private-screen", "data:text/plain;base64,$payload").forEach { uri ->
            val failure = UIMessagePart.Image(uri).encodeBase64(false).exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(failure?.message.orEmpty().contains(payload))
            assertFalse(failure?.message.orEmpty().contains("private-screen"))
        }
    }

    @Test fun remoteImageBehaviorIsUnchanged() {
        val uri = "https://example.invalid/synthetic.png"
        assertEquals(EncodedImage(uri, "image/png"), UIMessagePart.Image(uri).encodeBase64(false).getOrThrow())
    }
}
