package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShipmentAnalyzerTest {
    private val t0: Instant = Instant.parse("2026-11-23T08:00:00Z")
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED) // 2-8 °C, 15 min tolerance, 10 min max gap

    /** One reading per minute with the given temperatures. */
    private fun minutes(vararg celsius: Double) =
        celsius.mapIndexed { i, c -> Reading(t0.plus(Duration.ofMinutes(i.toLong())), c) }

    private fun at(minute: Long, celsius: Double) = Reading(t0.plus(Duration.ofMinutes(minute)), celsius)

    @Test
    fun `steady fridge passes`() {
        val report = analyzer.analyze(minutes(4.0, 4.1, 3.9, 4.0))
        assertEquals(Verdict.PASS, report.verdict)
        assertTrue(report.excursions.isEmpty())
        assertEquals(Duration.ZERO, report.timeOutOfRange)
    }

    @Test
    fun `short excursion is logged but does not fail the shipment`() {
        // above 8 °C from minute 2, back in range at minute 7: five minutes out
        val report = analyzer.analyze(minutes(4.0, 4.0, 9.0, 10.0, 11.5, 10.0, 9.0, 6.0, 4.0))
        val excursion = report.excursions.single()
        assertEquals(Direction.TOO_WARM, excursion.direction)
        assertEquals(Duration.ofMinutes(5), excursion.duration)
        assertEquals(11.5, excursion.peakC)
        assertEquals(Verdict.PASS, report.verdict)
    }

    @Test
    fun `excursion longer than the tolerance fails the shipment`() {
        val temps = DoubleArray(30) { i -> if (i in 5..25) 12.0 else 4.0 }
        val report = analyzer.analyze(minutes(*temps))
        assertEquals(Verdict.FAIL, report.verdict)
        assertEquals(Duration.ofMinutes(21), report.alarms.single().duration)
    }

    @Test
    fun `exactly the tolerance is not an alarm, one minute more is`() {
        val atLimit = DoubleArray(20) { i -> if (i in 2 until 17) 9.0 else 4.0 } // 15 minutes out
        val overLimit = DoubleArray(20) { i -> if (i in 2 until 18) 9.0 else 4.0 } // 16 minutes out
        assertEquals(Verdict.PASS, analyzer.analyze(minutes(*atLimit)).verdict)
        assertEquals(Verdict.FAIL, analyzer.analyze(minutes(*overLimit)).verdict)
    }

    @Test
    fun `too cold is an excursion too`() {
        val excursion = analyzer.analyze(minutes(4.0, 1.5, 0.5, 3.0)).excursions.single()
        assertEquals(Direction.TOO_COLD, excursion.direction)
        assertEquals(0.5, excursion.peakC)
        assertEquals(Duration.ofMinutes(2), excursion.duration)
    }

    @Test
    fun `switching straight from too cold to too warm gives two excursions`() {
        val report = analyzer.analyze(minutes(4.0, 1.0, 9.0, 4.0))
        assertEquals(listOf(Direction.TOO_COLD, Direction.TOO_WARM), report.excursions.map { it.direction })
    }

    @Test
    fun `silent sensor makes the shipment incomplete instead of passing`() {
        val readings = minutes(4.0, 4.0, 4.0) + at(30, 4.0) + at(31, 4.0)
        val report = analyzer.analyze(readings)
        assertEquals(Verdict.INCOMPLETE, report.verdict)
        assertEquals(DataGap(t0.plus(Duration.ofMinutes(2)), t0.plus(Duration.ofMinutes(30))), report.gaps.single())
    }

    @Test
    fun `a gap cuts an excursion so unknown time is never counted`() {
        val readings = minutes(4.0, 9.0, 9.0) + at(40, 9.0) + at(41, 4.0)
        val report = analyzer.analyze(readings)
        assertEquals(2, report.excursions.size)
        assertEquals(Duration.ofMinutes(2), report.timeOutOfRange)
        assertEquals(Verdict.INCOMPLETE, report.verdict)
    }

    @Test
    fun `a gap shorter than the limit counts as covered`() {
        val report = analyzer.analyze(minutes(4.0, 4.0) + at(11, 4.0)) // 10 minutes of silence is still fine
        assertTrue(report.gaps.isEmpty())
        assertEquals(Verdict.PASS, report.verdict)
    }

    @Test
    fun `readings out of order are sorted first`() {
        val report = analyzer.analyze(minutes(4.0, 9.0, 4.0).reversed())
        assertEquals(Duration.ofMinutes(1), report.timeOutOfRange)
    }

    @Test
    fun `frozen range has no lower limit`() {
        val frozen = ShipmentAnalyzer(TemperatureRange.FROZEN)
        assertEquals(Verdict.PASS, frozen.analyze(minutes(-25.0, -40.0, -19.0)).verdict)
        val excursion = frozen.analyze(minutes(-20.0, -15.0, -20.0)).excursions.single()
        assertEquals(Direction.TOO_WARM, excursion.direction)
    }

    @Test
    fun `report has min, max and reading count`() {
        val report = analyzer.analyze(minutes(4.0, 3.2, 6.8, 5.0))
        assertEquals(4, report.readingCount)
        assertEquals(3.2, report.minC)
        assertEquals(6.8, report.maxC)
    }

    @Test
    fun `empty list is rejected`() {
        assertFailsWith<IllegalArgumentException> { analyzer.analyze(emptyList()) }
    }
}
