package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException

/** Only fixed event kinds may enter notification delivery diagnostics, never message contents. */
internal enum class ChatNotificationDeliveryKind(val failureCode: String) {
    UPDATE("chat_notification_update_failed"),
    COMPLETED("chat_notification_completed_failed"),
    CLEANUP("chat_notification_cleanup_failed"),
}

/** A failed Android notification must not retire the application's long-lived event subscriber. */
internal fun deliverChatNotificationEvent(
    kind: ChatNotificationDeliveryKind,
    reportFailure: (ChatNotificationDeliveryKind) -> Unit,
    deliver: () -> Unit,
) {
    try {
        deliver()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        try {
            reportFailure(kind)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics are also an edge effect, not a reason to lose later notifications.
        }
    }
}
