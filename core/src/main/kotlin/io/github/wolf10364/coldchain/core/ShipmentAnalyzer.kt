package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln

/** Which side of the allowed range a reading fell on. */
enum class Direction { TOO_WARM, TOO_COLD }

/** One continuous period outside the allowed range. [peakC] is the warmest (or coldest) reading in it. */
data class Excursion(
    val direction: Direction,
    val start: Instant,
    val end: Instant,
    val peakC: Double,
) {
    val duration: Duration get() = Duration.between(start, end)
}

/** A stretch with no readings for longer than the allowed gap. The temperature there is unknown. */
data class DataGap(val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)
}

enum class Verdict {
    /** Fully covered by readings, and no excursion lasted longer than the tolerance. */
    PASS,

    /** At least one excursion lasted longer than the tolerance. */
    FAIL,

    /** No alarm, but gaps in the data mean nobody can confirm the goods stayed in range. */
    INCOMPLETE,
}

data class ShipmentReport(
    val readingCount: Int,
    val start: Instant,
    val end: Instant,
    val minC: Double,
    val maxC: Double,
    val meanKineticC: Double,
    val excursions: List<Excursion>,
    val alarms: List<Excursion>,
    val gaps: List<DataGap>,
) {
    val timeOutOfRange: Duration
        get() = excursions.fold(Duration.ZERO) { total, e -> total + e.duration }

    val timeWithoutData: Duration
        get() = gaps.fold(Duration.ZERO) { total, g -> total + g.duration }

    val verdict: Verdict
        get() = when {
            alarms.isNotEmpty() -> Verdict.FAIL
            gaps.isNotEmpty() -> Verdict.INCOMPLETE
            else -> Verdict.PASS
        }
}

/**
 * Turns a list of readings into a [ShipmentReport].
 *
 * Each reading is taken to hold until the next one arrives. If the sensor is silent for longer than
 * [maxGap], that stretch is reported as a [DataGap] instead of being assumed fine, and any excursion
 * running at the time is cut there: unknown time is never counted as in range or out of range.
 */
class ShipmentAnalyzer(
    val range: TemperatureRange,
    /** How long goods may stay out of range before it counts as an alarm. */
    val tolerance: Duration = Duration.ofMinutes(15),
    /** The longest silence between two readings that still counts as covered. */
    val maxGap: Duration = Duration.ofMinutes(10),
) {
    private class Run(val direction: Direction, val start: Instant, var end: Instant, var peakC: Double) {
        fun toExcursion() = Excursion(direction, start, end, peakC)
    }

    fun analyze(readings: List<Reading>): ShipmentReport {
        require(readings.isNotEmpty()) { "A report needs at least one reading" }
        val sorted = readings.sortedBy { it.time }
        val excursions = mutableListOf<Excursion>()
        val gaps = mutableListOf<DataGap>()
        var run: Run? = null

        for ((i, reading) in sorted.withIndex()) {
            val next = sorted.getOrNull(i + 1)
            val gapAfter = next != null && Duration.between(reading.time, next.time) > maxGap
            val holdsUntil = if (next == null || gapAfter) reading.time else next.time
            val direction = directionOf(reading.celsius)

            val open = run
            if (open != null && open.direction != direction) {
                excursions += open.toExcursion()
                run = null
            }
            if (direction != null) {
                val active = run ?: Run(direction, reading.time, holdsUntil, reading.celsius).also { run = it }
                active.end = holdsUntil
                active.peakC = when (direction) {
                    Direction.TOO_WARM -> maxOf(active.peakC, reading.celsius)
                    Direction.TOO_COLD -> minOf(active.peakC, reading.celsius)
                }
            }
            if (gapAfter) {
                run?.let { excursions += it.toExcursion() }
                run = null
                gaps += DataGap(reading.time, next.time)
            }
        }
        run?.let { excursions += it.toExcursion() }

        return ShipmentReport(
            readingCount = sorted.size,
            start = sorted.first().time,
            end = sorted.last().time,
            minC = sorted.minOf { it.celsius },
            maxC = sorted.maxOf { it.celsius },
            meanKineticC = meanKineticTemperature(sorted.map { it.celsius }),
            excursions = excursions,
            alarms = excursions.filter { it.duration > tolerance },
            gaps = gaps,
        )
    }

    private fun directionOf(celsius: Double): Direction? = when {
        celsius > range.maxC -> Direction.TOO_WARM
        celsius < range.minC -> Direction.TOO_COLD
        else -> null
    }
}

private const val KELVIN = 273.15

/**
 * Mean kinetic temperature (MKT) in °C: a single number that weighs warm periods more than cold ones,
 * because heat speeds up the breakdown of medicines and food. Pharma storage uses it to judge a
 * fluctuating temperature history.
 *
 * [activationEnergyOverR] is ΔH/R in kelvin; 10,000 K (ΔH = 83.144 kJ/mol) is the usual default.
 * Readings are weighted equally, so they should be evenly spaced.
 */
fun meanKineticTemperature(celsius: List<Double>, activationEnergyOverR: Double = 10_000.0): Double {
    require(celsius.isNotEmpty()) { "MKT needs at least one temperature" }
    val mean = celsius.map { exp(-activationEnergyOverR / (it + KELVIN)) }.average()
    return activationEnergyOverR / -ln(mean) - KELVIN
}
