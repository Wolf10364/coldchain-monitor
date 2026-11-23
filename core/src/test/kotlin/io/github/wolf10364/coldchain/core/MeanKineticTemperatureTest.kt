package io.github.wolf10364.coldchain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeanKineticTemperatureTest {
    @Test
    fun `constant temperature gives that temperature`() {
        assertEquals(5.0, meanKineticTemperature(List(10) { 5.0 }), 1e-9)
    }

    @Test
    fun `warm readings count more than cold ones`() {
        val mkt = meanKineticTemperature(listOf(2.0, 8.0))
        assertTrue(mkt > 5.0, "MKT $mkt should be above the plain average of 5.0")
        assertTrue(mkt < 8.0)
    }

    @Test
    fun `a short heat spike moves MKT much more than the average`() {
        val temps = List(23) { 5.0 } + 25.0
        val gap = meanKineticTemperature(temps) - temps.average()
        assertTrue(gap > 1.5, "MKT should sit well above the average after a spike, gap was $gap")
    }

    @Test
    fun `empty list is rejected`() {
        assertFailsWith<IllegalArgumentException> { meanKineticTemperature(emptyList()) }
    }
}
