package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * A simple fridge for the simulator. Each step, the temperature closes a fixed share of the distance to its
 * target: the set point while the door is shut, room temperature while it is open. The measured value adds a
 * little sensor noise and is rounded to the 0.01 °C steps a real sensor reports.
 */
class FridgeModel(
    val setPointC: Double = 4.0,
    val roomC: Double = 22.0,
    private val coolingRate: Double = 0.15,
    private val warmingRate: Double = 0.08,
    private val noiseC: Double = 0.1,
    private val random: Random = Random(42),
    startC: Double = setPointC,
) {
    /** The true temperature inside, without sensor noise. */
    var temperatureC: Double = startC
        private set

    /** Advances one step and returns what the sensor would measure. */
    fun step(doorOpen: Boolean): Double {
        val (target, rate) = if (doorOpen) roomC to warmingRate else setPointC to coolingRate
        temperatureC += (target - temperatureC) * rate
        val measured = temperatureC + (random.nextDouble() * 2 - 1) * noiseC
        return Math.round(measured * 100) / 100.0
    }
}

/** Something that happens during a simulated run, counted in steps from the start. */
sealed interface SimEvent {
    val fromStep: Int
    val steps: Int

    fun isActive(step: Int) = step >= fromStep && step < fromStep + steps

    /** The door stands open, so the fridge warms toward room temperature. */
    data class DoorOpen(override val fromStep: Int, override val steps: Int) : SimEvent

    /** The sensor is out of reach: the fridge keeps changing, but no readings arrive. */
    data class SensorDropout(override val fromStep: Int, override val steps: Int) : SimEvent
}

/** Runs the model for [steps] steps of length [interval] and returns the readings that reached the phone. */
fun FridgeModel.simulate(
    start: Instant,
    interval: Duration,
    steps: Int,
    events: List<SimEvent> = emptyList(),
): List<Reading> = (0 until steps).mapNotNull { i ->
    val doorOpen = events.any { it is SimEvent.DoorOpen && it.isActive(i) }
    val celsius = step(doorOpen)
    val silent = events.any { it is SimEvent.SensorDropout && it.isActive(i) }
    if (silent) null else Reading(start.plus(interval.multipliedBy(i.toLong())), celsius)
}
