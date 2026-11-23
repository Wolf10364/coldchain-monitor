package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FridgeModelTest {
    private val t0: Instant = Instant.parse("2026-11-23T08:00:00Z")
    private val minute: Duration = Duration.ofMinutes(1)
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED)

    private fun fridge(seed: Int = 1) = FridgeModel(random = Random(seed))

    @Test
    fun `closed fridge stays in range for four hours`() {
        val readings = fridge().simulate(t0, minute, steps = 240)
        assertEquals(Verdict.PASS, analyzer.analyze(readings).verdict)
    }

    @Test
    fun `a quick door opening does not cause an excursion`() {
        val readings = fridge().simulate(t0, minute, 60, listOf(SimEvent.DoorOpen(fromStep = 20, steps = 2)))
        assertTrue(analyzer.analyze(readings).excursions.isEmpty())
    }

    @Test
    fun `door left open for half an hour fails the shipment`() {
        val readings = fridge().simulate(t0, minute, 120, listOf(SimEvent.DoorOpen(fromStep = 20, steps = 30)))
        val report = analyzer.analyze(readings)
        assertEquals(Verdict.FAIL, report.verdict)
        assertTrue(report.maxC > 15.0, "expected the fridge to warm well past 8 °C, max was ${report.maxC}")
    }

    @Test
    fun `sensor dropout shows up as a data gap`() {
        val readings = fridge().simulate(t0, minute, 60, listOf(SimEvent.SensorDropout(fromStep = 10, steps = 20)))
        assertEquals(40, readings.size)
        assertEquals(Duration.ofMinutes(21), analyzer.analyze(readings).gaps.single().duration)
    }

    @Test
    fun `same seed gives the same run`() {
        assertEquals(fridge(7).simulate(t0, minute, 50), fridge(7).simulate(t0, minute, 50))
    }

    @Test
    fun `readings are rounded to the sensor's 0_01 degree steps`() {
        fridge(3).simulate(t0, minute, 50).forEach {
            assertEquals(it.celsius, Math.round(it.celsius * 100) / 100.0)
        }
    }
}
