package io.github.wolf10364.coldchain.core

/**
 * The standard Bluetooth "Temperature" characteristic (0x2A6E) of the Environmental Sensing Service (0x181A):
 * a little-endian signed 16-bit number in steps of 0.01 °C. The raw value 0x8000 means "value is not known".
 *
 * Check your sensor's documentation before relying on this: many cheap sensors send their own format instead,
 * and those need a decoder of their own (with tests built from bytes captured from the real device).
 */
object EssTemperature {
    const val SERVICE_UUID = "0000181a-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "00002a6e-0000-1000-8000-00805f9b34fb"

    private const val NOT_KNOWN = -0x8000 // 0x8000 read as a signed 16-bit number

    /** Returns the temperature in °C, or null if the sensor reports that it has no value. */
    fun decode(bytes: ByteArray): Double? {
        require(bytes.size == 2) { "Expected 2 bytes, got ${bytes.size}" }
        val raw = ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort().toInt()
        return if (raw == NOT_KNOWN) null else raw / 100.0
    }

    /** The bytes a standard sensor would send for [celsius]. Used by the simulator and the tests. */
    fun encode(celsius: Double): ByteArray {
        val raw = Math.round(celsius * 100).toInt()
        require(raw in (Short.MIN_VALUE + 1)..Short.MAX_VALUE.toInt()) { "$celsius °C does not fit the characteristic" }
        return byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
    }
}
