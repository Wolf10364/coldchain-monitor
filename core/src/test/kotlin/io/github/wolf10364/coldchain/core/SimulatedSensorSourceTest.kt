package io.github.wolf10364.coldchain.core

import io.github.wolf10364.coldchain.core.ConnectionState.CONNECTED
import io.github.wolf10364.coldchain.core.ConnectionState.LOST
import io.github.wolf10364.coldchain.core.ConnectionState.RECONNECTING
import io.github.wolf10364.coldchain.core.ConnectionState.SCANNING
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedSensorSourceTest {
    private val t0: Instant = Instant.parse("2026-11-23T08:00:00Z")

    private fun source() = SimulatedSensorSource(model = FridgeModel(random = Random(1)), start = t0, tickMillis = 1_000)

    @Test
    fun `connects, then sends one reading per simulated minute`() = runTest {
        val source = source()
        val events = mutableListOf<SensorEvent>()
        backgroundScope.launch { source.events().collect { events += it } }

        advanceTimeBy(4_500)

        assertEquals(listOf(SCANNING, CONNECTED), events.filterIsInstance<SensorEvent.Connection>().map { it.state })
        val minutes = events.filterIsInstance<SensorEvent.NewReading>().map { Duration.between(t0, it.reading.time).toMinutes() }
        assertEquals(listOf(0L, 1L, 2L, 3L), minutes)
    }

    @Test
    fun `lost link is reported and readings resume after reconnecting`() = runTest {
        val source = source()
        val events = mutableListOf<SensorEvent>()
        backgroundScope.launch { source.events().collect { events += it } }

        advanceTimeBy(3_500) // scanning, connected, readings for minutes 0-2
        source.linkUp.value = false
        advanceTimeBy(5_000) // minutes 3-7 happen, but nothing arrives
        source.linkUp.value = true
        advanceTimeBy(2_000) // minutes 8-9 arrive again

        val states = events.filterIsInstance<SensorEvent.Connection>().map { it.state }
        assertEquals(listOf(SCANNING, CONNECTED, LOST, RECONNECTING, CONNECTED), states)
        val minutes = events.filterIsInstance<SensorEvent.NewReading>().map { Duration.between(t0, it.reading.time).toMinutes() }
        assertEquals(listOf(0L, 1L, 2L, 8L, 9L), minutes)
    }

    @Test
    fun `opening the door warms the readings`() = runTest {
        val source = source()
        val readings = mutableListOf<Double>()
        backgroundScope.launch {
            source.events().collect { if (it is SensorEvent.NewReading) readings += it.reading.celsius }
        }

        source.doorOpen.value = true
        advanceTimeBy(20_500)

        assertTrue(readings.last() > 8.0, "after 20 minutes with the door open it should be above 8 °C: ${readings.last()}")
    }
}
