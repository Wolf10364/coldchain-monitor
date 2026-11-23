package io.github.wolf10364.coldchain.core

import java.time.Instant
import java.util.Locale

/** One temperature sample from a sensor. */
data class Reading(val time: Instant, val celsius: Double)

/** The allowed temperature band for a shipment, inclusive at both ends. */
data class TemperatureRange(val minC: Double, val maxC: Double) {
    init {
        require(minC < maxC) { "minC ($minC) must be below maxC ($maxC)" }
    }

    operator fun contains(celsius: Double): Boolean = celsius in minC..maxC

    fun describe(): String =
        if (minC == Double.NEGATIVE_INFINITY) "at or below ${fmt(maxC)} °C"
        else "${fmt(minC)} to ${fmt(maxC)} °C"

    private fun fmt(c: Double) = String.format(Locale.ROOT, "%.1f", c)

    companion object {
        /** Chilled food and most vaccines. */
        val CHILLED = TemperatureRange(2.0, 8.0)

        /** Frozen food: -18 °C or colder, no lower limit. */
        val FROZEN = TemperatureRange(Double.NEGATIVE_INFINITY, -18.0)

        /** Controlled room temperature, common for medicines. */
        val CONTROLLED_ROOM = TemperatureRange(15.0, 25.0)
    }
}
