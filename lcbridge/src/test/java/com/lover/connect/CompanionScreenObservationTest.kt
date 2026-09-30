package com.lover.connect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionScreenObservationTest {
    private val synthetic = CompanionToolImage("/9j/synthetic-private-pixels", 1200, 2608)

    @Test fun observationDescribesPixelsWithoutInventingOcrOrCurrentPackage() {
        val result = companionScreenObservationResult(synthetic, 100, 125, "accessibility_screenshot", "example.old", 10)
        assertTrue(result.ok)
        assertEquals(listOf(synthetic), result.images)
        val content = JSONObject(result.content!!)
        assertEquals("raw_screen_observation", content.getString("kind"))
        assertFalse(content.getBoolean("image_is_model_summary"))
        assertTrue(content.isNull("visible_text"))
        assertTrue(content.isNull("current_screen_package"))
        assertEquals("not_extracted_no_ocr_or_window_content_access", content.getString("visible_text_status"))
        val last = content.getJSONObject("last_external_window_event")
        assertEquals("example.old", last.getString("package"))
        assertEquals(10, last.getLong("observed_at_ms"))
        assertFalse(last.getBoolean("is_current_screen_identity"))
        assertEquals(125, content.getLong("capture_completed_at_ms"))
    }

    @Test fun imageBytesAreNotInsertedIntoTextOrDiagnosticString() {
        val result = companionScreenObservationResult(synthetic, 100, 125, "synthetic", null, 0)
        val wire = JSONObject(result.toJson())
        assertEquals(1, wire.getInt("image_count"))
        assertEquals("image/jpeg", wire.getJSONArray("images").getJSONObject(0).getString("mime_type"))
        assertFalse(result.toJson().contains("synthetic-private-pixels"))
        assertFalse(synthetic.toString().contains("synthetic-private-pixels"))
        assertFalse(JSONObject(result.content!!).has("last_external_window_event"))
    }

    @Test fun missingOrMultiplePicturesAreNotSuccessfulCaptures() {
        assertEquals("screen_capture_missing_image", validateCompanionScreenResult(CompanionToolResult(true)).errorCode)
        assertEquals("screen_capture_missing_image", validateCompanionScreenResult(
            CompanionToolResult(true, images = listOf(synthetic, synthetic))).errorCode)
    }

    @Test fun invalidDimensionsMimeAndUnboundedPayloadAreRejected() {
        for (image in listOf(synthetic.copy(width = 0), synthetic.copy(height = 16_385),
            synthetic.copy(width = 16_000, height = 16_000), synthetic.copy(mimeType = "text/html"),
            synthetic.copy(base64 = "not a jpeg"))) {
            assertEquals("screen_capture_invalid_image", validateCompanionScreenResult(
                CompanionToolResult(true, images = listOf(image))).errorCode)
        }
        assertEquals("screen_capture_too_large", validateCompanionScreenResult(
            CompanionToolResult(true, "x".repeat(16 * 1024 + 1), images = listOf(synthetic))).errorCode)
    }

    @Test fun refusalDoesNotCarryAnOldScreenshot() {
        val failure = validateCompanionScreenResult(CompanionToolResult(false,
            errorCode = "screen_capture_failed_or_protected", images = listOf(synthetic)))
        assertFalse(failure.ok)
        assertEquals("screen_capture_failed_or_protected", failure.errorCode)
        assertTrue(failure.images.isEmpty())
    }
}
