package me.rerere.rikkahub.data.orbis.market

import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.orbis.diagnostics.diagnosticCodes
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.util.UUID

class MarketSavePreparationTest {
    private val id = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val chat = UUID.randomUUID().toString()
    private val event = OrbisEventMetadata(UUID.randomUUID().toString(), "market.$id", id, 1L)
    private val delivery = MarketDelivery(id, "item", 1, "Synthetic", "Synthetic prompt", "test", "extra", owner, chat, 1L)
    private fun prepared(expected: OrbisEventMetadata? = event, current: OrbisEventMetadata? = event,
        gallery: Boolean = false, read: (String) -> MarketDelivery = { delivery }) =
        prepareMarketSave(expected, current, owner, chat, gallery, read)

    private fun assertDiagnostic(code: String, result: MarketSavePreparation) {
        assertEquals(code, result.diagnostic)
        assertTrue(code in diagnosticCodes)
        assertNull(result.delivery)
        assertNull(result.event)
    }
    @Test fun matchingStoryDoesNotRequireGalleryPermission() {
        val result = prepared()
        assertEquals(event, result.event); assertEquals(delivery, result.delivery); assertNull(result.diagnostic)
    }
    @Test fun gridRequiresExistingHumanPermission() {
        val grid = delivery.copy(category = "grid")
        assertDiagnostic("market_gallery_disabled", prepared(read = { grid }))
        assertEquals(grid, prepared(gallery = true, read = { grid }).delivery)
    }
    @Test fun absentOrDifferentCurrentEventDoesNotReadOrAdvertiseAnotherDelivery() {
        assertDiagnostic("market_event_binding_mismatch", prepared(current = null, read = { error("must not read") }))
        assertDiagnostic("market_event_binding_mismatch", prepared(current = event.copy(recordId = UUID.randomUUID().toString()),
            read = { error("must not read") }))
    }
    @Test fun missingAndUnreadableRecordsHaveDistinctCodeOnlyResults() {
        assertDiagnostic("market_confirmation_missing", prepared(read = { throw FileNotFoundException("private/path") }))
        assertDiagnostic("market_confirmation_unreadable", prepared(read = { error("private contents must not be returned") }))
    }
    @Test fun targetAndEventMismatchNeverRegisterSave() {
        listOf(delivery.copy(assistant = UUID.randomUUID().toString()), delivery.copy(conversation = UUID.randomUUID().toString()),
            delivery.copy(id = UUID.randomUUID().toString())).forEach { foreign ->
            assertDiagnostic("market_target_mismatch", prepared(read = { foreign }))
        }
    }
    @Test fun ordinaryTurnDoesNotInventAMarketError() {
        assertEquals(MarketSavePreparation(), prepared(expected = null, current = null, read = { error("must not read") }))
    }
    @Test fun cancellationRemainsCancellation() {
        assertThrows(CancellationException::class.java) { prepared(read = { throw CancellationException("cancelled") }) }
    }
}
