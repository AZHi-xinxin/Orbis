package com.lover.connect

/**
 * Original companion event data, not a human chat message or a model instruction.
 * The host owns durable deduplication by (source, eventId), destination binding and role selection.
 * content is the existing local alert text, not a replacement for any server-side processing.
 * payloadJson is the unchanged legacy payload; there are no credentials or routing URLs here.
 */
data class CompanionHostEvent(
    val source: String,
    val eventId: String,
    val type: String,
    val content: String?,
    val payloadJson: String,
    val occurredAtMs: Long,
)

enum class CompanionHostEventResult {
    /** Only the router may return NOT_OWNED. A claimed sink cannot request legacy fallback. */
    NOT_OWNED,
    /** Durable inbox acceptance, NOT proof that a conversation displayed or answered the event. */
    ACCEPTED,
    DUPLICATE,
    REJECTED,
    UNKNOWN,
}

fun interface CompanionHostEventSink {
    /** Called on the existing delivery worker. Must not return ACCEPTED before durable acceptance. */
    fun accept(event: CompanionHostEvent): CompanionHostEventResult
}

/** Pure registration/routing; install never starts observation, sends a test, or drains a queue. */
class CompanionHostEventRouter {
    private data class Registration(val sources: Set<String>, val sink: CompanionHostEventSink)
    @Volatile private var registration: Registration? = null

    fun install(ownedSources: Set<String>, sink: CompanionHostEventSink): AutoCloseable {
        require(ownedSources.all { it in CompanionHostEvents.KNOWN_SOURCES }) { "unknown_companion_source" }
        val installed = Registration(ownedSources.toSet(), sink)
        synchronized(this) { registration = installed }
        return AutoCloseable {
            // Closing an obsolete registration must not remove a newer host's registration.
            synchronized(this) { if (registration === installed) registration = null }
        }
    }

    fun owns(source: String): Boolean = registration?.sources?.contains(source) == true

    fun dispatch(event: CompanionHostEvent): CompanionHostEventResult {
        val selected = registration ?: return CompanionHostEventResult.NOT_OWNED
        if (event.source !in selected.sources) return CompanionHostEventResult.NOT_OWNED
        // Capture exactly one registration. A concurrent replacement never triggers a second sink.
        return try {
            val result = selected.sink.accept(event)
            if (result == CompanionHostEventResult.NOT_OWNED) CompanionHostEventResult.UNKNOWN else result
        } catch (_: Exception) {
            // The inbox may already have persisted the event. Never send to the old route as well.
            CompanionHostEventResult.UNKNOWN
        }
    }
}

object CompanionHostEvents {
    const val LC_VISUAL = "lc_visual"
    const val LC_REST = "lc_rest"
    const val LC_MANUAL_TEST = "lc_manual_test"
    const val LC_LOCATION = "lc_location"
    val KNOWN_SOURCES: Set<String> = setOf(LC_VISUAL, LC_REST, LC_MANUAL_TEST, LC_LOCATION)
    private val router = CompanionHostEventRouter()

    fun install(ownedSources: Set<String>, sink: CompanionHostEventSink): AutoCloseable = router.install(ownedSources, sink)
    fun owns(source: String): Boolean = router.owns(source)
    fun dispatch(event: CompanionHostEvent): CompanionHostEventResult = router.dispatch(event)
}

internal data class CompanionEventDelivery(
    val delivery: SentinelDelivery,
    val hostResult: CompanionHostEventResult,
) {
    val viaHost get() = hostResult != CompanionHostEventResult.NOT_OWNED
}

/** Legacy is eligible only before a host has claimed this source, never after a host outcome. */
internal fun deliverCompanionEvent(
    event: CompanionHostEvent,
    legacy: () -> SentinelDelivery,
    dispatch: (CompanionHostEvent) -> CompanionHostEventResult = CompanionHostEvents::dispatch,
): CompanionEventDelivery {
    val result = dispatch(event)
    val delivery = when (result) {
        CompanionHostEventResult.NOT_OWNED -> legacy()
        CompanionHostEventResult.ACCEPTED, CompanionHostEventResult.DUPLICATE -> SentinelDelivery.DELIVERED
        CompanionHostEventResult.REJECTED -> SentinelDelivery.REJECTED
        CompanionHostEventResult.UNKNOWN -> SentinelDelivery.UNCERTAIN
    }
    return CompanionEventDelivery(delivery, result)
}

internal fun EyesAlertEvent.asHostEvent(eventId: String): CompanionHostEvent = CompanionHostEvent(
    source = if (type == "app_timeout") CompanionHostEvents.LC_REST else CompanionHostEvents.LC_VISUAL,
    eventId = eventId,
    type = type,
    content = message,
    payloadJson = toJson(eventId).toString(),
    occurredAtMs = observedAtMs,
)
