package me.rerere.rikkahub.data.orbis.market

import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.orbis.isMarketEventSource
import java.io.FileNotFoundException

/** Code-only preparation result. Neither event text nor private storage errors become diagnostics. */
internal data class MarketSavePreparation(
    val event: OrbisEventMetadata? = null,
    val delivery: MarketDelivery? = null,
    val diagnostic: String? = null,
)

internal fun prepareMarketSave(
    expectedEvent: OrbisEventMetadata?,
    currentEvent: OrbisEventMetadata?,
    assistant: String,
    conversation: String,
    galleryEnabled: Boolean,
    readDelivery: (String) -> MarketDelivery,
): MarketSavePreparation {
    val expected = expectedEvent?.takeIf { isMarketEventSource(it.source) }
    val current = currentEvent?.takeIf { isMarketEventSource(it.source) }
    if (expected != null && (current == null || current.recordId != expected.recordId ||
            current.eventId != expected.eventId || current.source != expected.source)) {
        return MarketSavePreparation(diagnostic = "market_event_binding_mismatch")
    }
    if (current == null) return MarketSavePreparation()
    val delivery = try { readDelivery(current.source.removePrefix("market.")) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: FileNotFoundException) { return MarketSavePreparation(diagnostic = "market_confirmation_missing") }
    catch (_: Exception) { return MarketSavePreparation(diagnostic = "market_confirmation_unreadable") }
    if (delivery.assistant != assistant || delivery.conversation != conversation ||
        delivery.source != current.source || delivery.id != current.eventId) {
        return MarketSavePreparation(diagnostic = "market_target_mismatch")
    }
    if (delivery.category != "extra" && !galleryEnabled) {
        return MarketSavePreparation(diagnostic = "market_gallery_disabled")
    }
    return MarketSavePreparation(current, delivery)
}
