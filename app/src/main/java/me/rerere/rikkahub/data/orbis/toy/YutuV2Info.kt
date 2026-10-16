package me.rerere.rikkahub.data.orbis.toy

/** Informational V2 subset only. It deliberately has no actuator command encoder. */
internal object YutuV2Info {
    const val MANUFACTURER_ID = 9490
    const val SERVICE = "00009000-0000-1000-8000-00805f9b34fb"
    const val OPERATION = "00009001-0000-1000-8000-00805f9b34fb"
    const val FUNCTION = "00009002-0000-1000-8000-00805f9b34fb"

    fun matchesAdvertisement(name: String?, manufacturerData: ByteArray?): Boolean =
        name?.trim()?.equals("YUTU", ignoreCase = true) == true &&
            manufacturerData?.size == 10 && manufacturerData[0].toInt() == 2

    fun firmwareQuery(): ByteArray = byteArrayOf(0x02, 0x00)

    sealed interface Value {
        data class Firmware(val version: String) : Value
        data class Battery(val percent: Int) : Value
    }

    fun parseNotification(packet: ByteArray): Value? {
        if (packet.size < 2) return null
        val header = packet[1].toInt() and 0xff
        val length = (header and 0x78) ushr 3
        // Only complete read/indication frames; no write acknowledgements or extensions.
        if (header and 0x87 != 0 || length != packet.size - 2) return null
        return when (packet[0].toInt() and 0xff) {
            0x02 -> {
                val payload = packet.copyOfRange(2, packet.size)
                if (payload.isEmpty() || payload.any { it.toInt() !in 0x20..0x7e }) return null
                Value.Firmware(payload.toString(Charsets.US_ASCII))
            }
            0x12 -> packet.takeIf { length == 1 }
                ?.get(2)?.toInt()?.and(0xff)?.takeIf { it <= 100 }?.let(Value::Battery)
            else -> null
        }
    }
}

internal data class YutuV2InfoResult(val firmware: String, val batteryPercent: Int?)
