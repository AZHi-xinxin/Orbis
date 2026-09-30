package com.lover.connect

import android.app.KeyguardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One-shot native capture. Never starts projection, opens settings, calls a model or writes a diary. */
internal object AndroidCompanionScreenCapture {
    private val inFlight = AtomicBoolean(false)

    fun capture(context: Context): CompanionToolResult {
        // Isolated runners must not touch production preferences, screen content or services.
        if (!LcExternalRecoveryGate.isAllowed()) return screenObservationFailure("screen_capture_unavailable", "not_started")
        screenUnavailable(context)?.let { return screenObservationFailure(it, "not_started") }
        val accessibility = LCAccessibilityService.instance
            ?: return screenObservationFailure("accessibility_permission_required", "not_started")
        if (!inFlight.compareAndSet(false, true)) return screenObservationFailure("screen_capture_busy", "not_started")
        val requestedAtMs = System.currentTimeMillis()
        val acceptingResult = AtomicBoolean(true)
        val encoded = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        try {
            accessibility.takeScreenshotNow { base64 ->
                if (acceptingResult.compareAndSet(true, false)) {
                    encoded.set(base64)
                    latch.countDown()
                }
            }
            if (!latch.await(8, TimeUnit.SECONDS)) return screenObservationFailure("screen_capture_timeout")
            // A lock/permission change while Android was capturing must not leak the late frame.
            screenUnavailable(context)?.let { return screenObservationFailure(it) }
            if (LCAccessibilityService.instance !== accessibility) return screenObservationFailure("accessibility_disconnected")
            val base64 = encoded.get() ?: return screenObservationFailure("screen_capture_failed_or_protected")
            if (base64.length > COMPANION_SCREEN_MAX_BASE64_CHARS) return screenObservationFailure("screen_capture_too_large")
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            // Decode bounds only; do not duplicate a full-screen bitmap on the worker thread.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val diagnostics = context.getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
            return companionScreenObservationResult(
                image = CompanionToolImage(base64, bounds.outWidth, bounds.outHeight),
                requestedAtMs = requestedAtMs,
                completedAtMs = System.currentTimeMillis(),
                backend = if (Build.VERSION.SDK_INT >= 30) "accessibility_screenshot" else "authorized_media_projection",
                // These events intentionally exclude our own windows. They cannot name this screenshot.
                lastExternalWindowPackage = diagnostics.getString("accessibility_last_event_package", null),
                lastExternalWindowEventAtMs = diagnostics.getLong("accessibility_last_event_at", 0),
            )
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: SecurityException) {
            return screenObservationFailure("screen_capture_permission_required")
        } catch (_: Exception) {
            return screenObservationFailure("screen_capture_failed")
        } finally {
            acceptingResult.set(false)
            encoded.set(null)
            inFlight.set(false)
        }
    }

    private fun screenUnavailable(context: Context): String? = when {
        !McpServiceController.isEnabled(context) -> "service_disabled"
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked -> "device_locked"
        !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive -> "screen_not_interactive"
        LCAccessibilityService.instance == null -> "accessibility_permission_required"
        Build.VERSION.SDK_INT < 30 && !ScreenCaptureService.isReady() -> "screen_capture_permission_required"
        else -> null
    }
}
