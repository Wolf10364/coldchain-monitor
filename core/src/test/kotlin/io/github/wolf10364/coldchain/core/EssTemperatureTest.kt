package io.github.wolf10364.coldchain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EssTemperatureTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `positive value`() {
        assertEquals(5.0, EssTemperature.decode(bytes(0xF4, 0x01))) // 500 hundredths
    }

    @Test
    fun `negative value`() {
        assertEquals(-5.0, EssTemperature.decode(bytes(0x0C, 0xFE))) // -500 hundredths
    }

    @Test
    fun `hundredths are kept`() {
        assertEquals(23.45, EssTemperature.decode(bytes(0x29, 0x09))!!, 1e-9) // 2345 = 0x0929
    }

    @Test
    fun `0x8000 means the sensor has no value`() {
        assertNull(EssTemperature.decode(bytes(0x00, 0x80)))
    }

    @Test
    fun `wrong length is rejected`() {
        assertFailsWith<IllegalArgumentException> { EssTemperature.decode(bytes(0x01)) }
        assertFailsWith<IllegalArgumentException> { EssTemperature.decode(bytes(0x01, 0x02, 0x03)) }
    }

    @Test
    fun `encode and decode round trip from -40 to +40 degrees`() {
        for (hundredths in -4000..4000 step 7) {
            val celsius = hundredths / 100.0
            assertEquals(celsius, EssTemperature.decode(EssTemperature.encode(celsius))!!, 1e-9)
        }
    }

    @Test
    fun `values outside the characteristic are rejected`() {
        assertFailsWith<IllegalArgumentException> { EssTemperature.encode(400.0) }
    }
}
