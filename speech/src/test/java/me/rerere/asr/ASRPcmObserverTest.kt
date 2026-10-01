package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class ASRPcmObserverTest {
    @Test fun absentObserverDoesNotRetainOrInvoke() {
        ASRPcmObserver().emit(0, byteArrayOf(1, 2), 2, 16_000)
    }

    @Test fun callbackGetsOwnedBytesAndExactSampleRate() {
        val tap = ASRPcmObserver()
        var captured: ByteArray? = null
        var rate = 0
        tap.set { bytes, sampleRate -> captured = bytes; rate = sampleRate }
        val source = byteArrayOf(1, 2, 3, 4)
        tap.emit(tap.captureEpoch(), source, 2, 24_000)
        source[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), captured)
        assertEquals(24_000, rate)
    }

    @Test fun stoppedOrReplacedCaptureCannotWriteToNextRecording() {
        val tap = ASRPcmObserver()
        var first = 0
        var second = 0
        tap.set { _, _ -> first++ }
        val old = tap.captureEpoch()
        tap.set(null)
        tap.emit(old, byteArrayOf(0, 0), 2, 16_000)
        tap.set { _, _ -> second++ }
        tap.emit(old, byteArrayOf(0, 0), 2, 16_000)
        tap.emit(tap.captureEpoch(), byteArrayOf(0, 0), 2, 16_000)
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test fun oddCaptureBlocksAreDeliveredWithoutDroppingTheirTrailingByte() {
        val tap = ASRPcmObserver()
        val captured = mutableListOf<Byte>()
        tap.set { bytes, _ -> captured.addAll(bytes.toList()) }
        val epoch = tap.captureEpoch()
        tap.emit(epoch, byteArrayOf(1, 2, 3, 99), 3, 16_000)
        tap.emit(epoch, byteArrayOf(4), 1, 16_000)
        assertEquals(listOf<Byte>(1, 2, 3, 4), captured)
    }
}
