package io.github.wolf10364.coldchain.core

import io.github.wolf10364.coldchain.core.ReceiptDecision.ACCEPT
import io.github.wolf10364.coldchain.core.ReceiptDecision.QUARANTINE
import io.github.wolf10364.coldchain.core.ReceiptDecision.REJECT
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HandlingTest {
    private val t0: Instant = Instant.parse("2026-11-23T08:00:00Z")
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED) // 2-8 °C, 15 min tolerance, 10 min max gap
    private val inspector = ReceivingInspector(analyzer)
    private val picking = PickingRules(TemperatureRange.CHILLED) // 20 min out of the cold
    private val flow = WarehouseFlow(analyzer)

    private fun at(minute: Long) = t0.plus(Duration.ofMinutes(minute))

    /** One reading per minute with the given temperatures, from minute 0. */
    private fun minutes(vararg celsius: Double) = celsius.mapIndexed { i, c -> Reading(at(i.toLong()), c) }

    /** The receipt check as it runs on arrival, right after the last reading. */
    private fun receive(readings: List<Reading>, now: Instant = readings.last().time) =
        inspector.inspect(analyzer.analyze(readings), readings.last(), now)

    // Goods receipt

    @Test
    fun `cold trip and cold arrival are accepted`() {
        val check = receive(minutes(4.0, 4.1, 3.9, 4.2))
        assertEquals(ACCEPT, check.decision)
        assertEquals(4.2, check.arrivalC)
        assertEquals(Verdict.PASS, check.transportVerdict)
        assertEquals(listOf("Arrived at 4.2 °C", "Stayed 2.0 to 8.0 °C for the whole trip"), check.reasons)
    }

    @Test
    fun `arriving too warm is rejected even after a good trip`() {
        val check = receive(minutes(4.0, 4.0, 4.0, 8.6))
        assertEquals(REJECT, check.decision)
        assertEquals("Arrived at 8.6 °C, above the 8.0 °C limit", check.reasons.first())
    }

    @Test
    fun `arriving too cold is rejected`() {
        assertEquals(REJECT, receive(minutes(4.0, 3.0, 1.4)).decision)
    }

    @Test
    fun `an alarm in transit is rejected even if the goods are cold again on arrival`() {
        // 16 minutes above 8 °C, then cooled down before arrival
        val trip = minutes(4.0, *DoubleArray(16) { 12.0 }, 6.0, 4.0)
        val check = receive(trip)
        assertEquals(REJECT, check.decision)
        assertTrue(check.reasons.any { it == "Out of range for 16 min in transit (limit 15 min)" }, check.reasons.toString())
    }

    @Test
    fun `a short excursion in transit is noted but accepted`() {
        val check = receive(minutes(4.0, 9.0, 9.5, 6.0, 4.0))
        assertEquals(ACCEPT, check.decision)
        assertTrue("1 short excursion in transit, within tolerance" in check.reasons)
    }

    @Test
    fun `a gap in the trip data sends the goods to quarantine`() {
        val trip = listOf(Reading(at(0), 4.0), Reading(at(30), 4.0), Reading(at(31), 4.0))
        val check = receive(trip)
        assertEquals(QUARANTINE, check.decision)
        assertTrue("No data for 30 min in transit" in check.reasons)
        assertEquals(Verdict.INCOMPLETE, check.transportVerdict)
    }

    @Test
    fun `no current reading at arrival sends the goods to quarantine`() {
        val trip = minutes(4.0, 4.0, 4.0)
        val check = receive(trip, now = at(2).plus(Duration.ofMinutes(11)))
        assertEquals(QUARANTINE, check.decision)
        assertNull(check.arrivalC)
        assertEquals("No current reading at arrival", check.reasons.first())
    }

    @Test
    fun `no history at all sends the goods to quarantine`() {
        val check = inspector.inspect(transport = null, latest = null, now = t0)
        assertEquals(QUARANTINE, check.decision)
        assertNull(check.transportVerdict)
        assertEquals(listOf("No current reading at arrival", "No temperature history for the trip"), check.reasons)
    }

    @Test
    fun `the worst finding wins`() {
        // a gap (quarantine) and arriving warm (reject)
        val trip = listOf(Reading(at(0), 4.0), Reading(at(30), 4.0), Reading(at(31), 9.0))
        assertEquals(REJECT, receive(trip).decision)
    }

    // Picking

    @Test
    fun `picking within the limits is fine while it runs and passes when packed`() {
        val readings = minutes(4.0, 4.5, 5.0, 5.4, 5.8, 6.1)
        val live = picking.check(start = at(0), end = null, now = at(5), readings = readings)
        assertEquals(PickStatus.WITHIN_LIMITS, live.status)
        assertEquals(Duration.ofMinutes(5), live.outOfCold)
        assertTrue(live.reasons.isEmpty())

        val done = picking.check(start = at(0), end = at(5), now = at(5), readings = readings)
        assertEquals(PickStatus.PASSED, done.status)
        assertEquals(6.1, done.maxC)
        assertEquals(listOf("Packed after 5 min, warmest reading 6.1 °C"), done.reasons)
    }

    @Test
    fun `picking fails as soon as the goods are out of the cold too long`() {
        val readings = minutes(*DoubleArray(22) { 5.0 })
        val live = picking.check(start = at(0), end = null, now = at(21), readings = readings)
        assertEquals(PickStatus.FAILED, live.status)
        assertEquals(listOf("Out of the cold room for 21 min (limit 20 min)"), live.reasons)
    }

    @Test
    fun `picking fails when the goods get too warm, even if it was quick`() {
        val check = picking.check(start = at(0), end = at(3), now = at(3), readings = minutes(4.0, 6.0, 8.4, 7.9))
        assertEquals(PickStatus.FAILED, check.status)
        assertEquals(listOf("Reached 8.4 °C while picking (limit 8.0 °C)"), check.reasons)
    }

    @Test
    fun `readings from before picking do not count`() {
        val readings = minutes(9.0, 9.0, 4.0, 4.1, 4.2) // warm at minutes 0-1, picking starts at minute 2
        val check = picking.check(start = at(2), end = at(4), now = at(4), readings = readings)
        assertEquals(PickStatus.PASSED, check.status)
        assertEquals(4.2, check.maxC)
    }

    @Test
    fun `picking cannot be confirmed without readings`() {
        val silent = picking.check(start = at(0), end = null, now = at(11), readings = listOf(Reading(at(0), 4.0)))
        assertEquals(PickStatus.FAILED, silent.status)
        assertEquals(listOf("No readings for 11 min while picking"), silent.reasons)

        val none = picking.check(start = at(0), end = at(2), now = at(2), readings = emptyList())
        assertEquals(PickStatus.FAILED, none.status)
        assertEquals(listOf("No readings while picking, so the temperature cannot be confirmed"), none.reasons)
    }

    // The flow

    @Test
    fun `a shipment moves from transit to receipt, picking and packing`() {
        val readings = minutes(4.0, 4.0, 4.0, 4.5, 5.0, 5.5)
        val received = flow.receive(Handling(), readings.take(3), now = at(2))
        assertEquals(Stage.RECEIVED, received.stage)
        assertTrue(received.canStartPicking)

        val picking = flow.startPicking(received, readings.take(3), now = at(2))
        assertEquals(Stage.PICKING, picking.stage)
        assertEquals(PickStatus.WITHIN_LIMITS, picking.picking?.status)

        val refreshed = flow.refresh(picking, readings, now = at(5))
        assertEquals(Duration.ofMinutes(3), refreshed.picking?.outOfCold)

        val packed = flow.pack(refreshed, readings, now = at(5))
        assertEquals(Stage.PACKED, packed.stage)
        assertEquals(PickStatus.PASSED, packed.picking?.status)
        assertEquals(ACCEPT, packed.receipt?.decision)
    }

    @Test
    fun `goods that were not accepted cannot be picked`() {
        val warm = flow.receive(Handling(), minutes(4.0, 9.0), now = at(1))
        assertEquals(REJECT, warm.receipt?.decision)
        assertFalse(warm.canStartPicking)
        assertFailsWith<IllegalArgumentException> { flow.startPicking(warm, emptyList(), at(1)) }
        assertFailsWith<IllegalArgumentException> { flow.startPicking(Handling(), emptyList(), at(1)) }
    }

    @Test
    fun `steps cannot be repeated or skipped`() {
        val received = flow.receive(Handling(), minutes(4.0), now = at(0))
        assertFailsWith<IllegalArgumentException> { flow.receive(received, minutes(4.0), now = at(0)) }
        assertFailsWith<IllegalArgumentException> { flow.pack(received, minutes(4.0), now = at(0)) }
        assertSame(received, flow.refresh(received, minutes(4.0), now = at(5)))
    }

    // The simulated picking area

    @Test
    fun `goods out of the cold warm slowly, unlike an open fridge door`() {
        val picked = FridgeModel(random = Random(1)).simulate(t0, Duration.ofMinutes(1), 31, listOf(SimEvent.Picking(0, 31)))
        assertTrue(picked[10].celsius < 8.0, "10 min of picking should stay below 8 °C: ${picked[10].celsius}")
        assertTrue(picked[30].celsius > 8.0, "30 min of picking should be above 8 °C: ${picked[30].celsius}")

        val doorOpen = FridgeModel(random = Random(1)).simulate(t0, Duration.ofMinutes(1), 11, listOf(SimEvent.DoorOpen(0, 11)))
        assertTrue(doorOpen[10].celsius > picked[10].celsius)
    }
}
