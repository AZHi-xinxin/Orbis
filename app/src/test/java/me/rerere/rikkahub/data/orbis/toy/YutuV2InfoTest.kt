package me.rerere.rikkahub.data.orbis.toy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YutuV2InfoTest {
    private val advertisement = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test fun matchesOnlyExpectedNameAndV2PayloadShape() {
        assertTrue(YutuV2Info.matchesAdvertisement("YUTU", advertisement))
        assertTrue(YutuV2Info.matchesAdvertisement(" yutu ", advertisement))
        assertFalse(YutuV2Info.matchesAdvertisement("OTHER", advertisement))
        assertFalse(YutuV2Info.matchesAdvertisement(null, advertisement))
        assertFalse(YutuV2Info.matchesAdvertisement("YUTU", null))
        assertFalse(YutuV2Info.matchesAdvertisement("YUTU", byteArrayOf(2)))
        assertFalse(YutuV2Info.matchesAdvertisement("YUTU", advertisement + 0))
        assertFalse(YutuV2Info.matchesAdvertisement("YUTU", advertisement.copyOf().also { it[0] = 3 }))
    }

    @Test fun firmwareQueryIsFixedReadOnlyAndReturnsIndependentArrays() {
        val packet = YutuV2Info.firmwareQuery()
        assertArrayEquals(byteArrayOf(2, 0), packet)
        packet[0] = 0
        assertArrayEquals(byteArrayOf(2, 0), YutuV2Info.firmwareQuery())
    }

    @Test fun parsesDeviceCapturedFirmwareResponse() {
        assertEquals(YutuV2Info.Value.Firmware("1.0.0"),
            YutuV2Info.parseNotification(byteArrayOf(2, 0x28, 0x31, 0x2e, 0x30, 0x2e, 0x30)))
    }

    @Test fun parsesBatteryIncludingZeroAndFull() {
        for (percent in listOf(0, 80, 100)) {
            assertEquals(YutuV2Info.Value.Battery(percent),
                YutuV2Info.parseNotification(byteArrayOf(0x12, 8, percent.toByte())))
        }
    }

    @Test fun rejectsTruncatedPaddedAndEmptyFrames() {
        for (packet in listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(2, 0),
            byteArrayOf(2, 0x10, 0x31), byteArrayOf(2, 8, 0x31, 0x32))) {
            assertNull(YutuV2Info.parseNotification(packet))
        }
    }

    @Test fun ignoresWriteAcknowledgementsAndReservedFlags() {
        for (flag in listOf(0x88, 9, 10, 12)) {
            assertNull(YutuV2Info.parseNotification(byteArrayOf(2, flag.toByte(), 0x31)))
        }
    }

    @Test fun rejectsInvalidBatteryAndNonPrintableFirmware() {
        for (value in listOf(101, 128, 255)) {
            assertNull(YutuV2Info.parseNotification(byteArrayOf(0x12, 8, value.toByte())))
        }
        assertNull(YutuV2Info.parseNotification(byteArrayOf(0x12, 0x10, 1, 2)))
        for (value in listOf(0, 10, 31, 127, 255)) {
            assertNull(YutuV2Info.parseNotification(byteArrayOf(2, 8, value.toByte())))
        }
    }

    @Test fun ignoresUnknownAndHeartbeatFrames() {
        // 0x14 is an unverified use-budget counter, not battery percentage.
        assertNull(YutuV2Info.parseNotification(byteArrayOf(0x14, 8, 75)))
        assertNull(YutuV2Info.parseNotification(byteArrayOf(0x14, 0x88.toByte(), 75)))
        assertNull(YutuV2Info.parseNotification(byteArrayOf(0x15, 8, 1)))
        assertNull(YutuV2Info.parseNotification(byteArrayOf(0x7f, 8, 1)))
    }
}
