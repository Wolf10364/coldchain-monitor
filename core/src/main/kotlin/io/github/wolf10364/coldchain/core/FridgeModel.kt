package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** What the goods are exposed to during one simulator step. */
enum class Exposure {
    /** In the fridge or a cool box. */
    COLD,

    /** Fridge door open: the air inside warms quickly. */
    DOOR_OPEN,

    /** Out of the cold room in a tote: the goods themselves warm slowly. */
    PICKING,
}

/**
 * A simple fridge for the simulator. Each step, the temperature closes a fixed share of the distance to its
 * target: the set point when cold, room temperature with the door open, the picking area's temperature while
 * picking. The measured value adds a little sensor noise and is rounded to the 0.01 °C steps a real sensor reports.
 */
class FridgeModel(
    val setPointC: Double = 4.0,
    val roomC: Double = 22.0,
    val pickAreaC: Double = 18.0,
    private val coolingRate: Double = 0.15,
    private val warmingRate: Double = 0.08,
    private val pickingRate: Double = 0.015, // goods in a tote warm far slower than fridge air with the door open
    private val noiseC: Double = 0.1,
    private val random: Random = Random(42),
    startC: Double = setPointC,
) {
    /** The true temperature inside, without sensor noise. */
    var temperatureC: Double = startC
        private set

    /** Puts the true temperature back to [celsius], for example when a new shipment starts. */
    fun reset(celsius: Double = setPointC) {
        temperatureC = celsius
    }

    /** Advances one step and returns what the sensor would measure. */
    fun step(doorOpen: Boolean): Double = step(if (doorOpen) Exposure.DOOR_OPEN else Exposure.COLD)

    /** Advances one step and returns what the sensor would measure. */
    fun step(exposure: Exposure): Double {
        val (target, rate) = when (exposure) {
            Exposure.COLD -> setPointC to coolingRate
            Exposure.DOOR_OPEN -> roomC to warmingRate
            Exposure.PICKING -> pickAreaC to pickingRate
        }
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

    /** The goods are out of the cold room for picking. */
    data class Picking(override val fromStep: Int, override val steps: Int) : SimEvent
}

/** Runs the model for [steps] steps of length [interval] and returns the readings that reached the phone. */
fun FridgeModel.simulate(
    start: Instant,
    interval: Duration,
    steps: Int,
    events: List<SimEvent> = emptyList(),
): List<Reading> = (0 until steps).mapNotNull { i ->
    val exposure = when {
        events.any { it is SimEvent.DoorOpen && it.isActive(i) } -> Exposure.DOOR_OPEN
        events.any { it is SimEvent.Picking && it.isActive(i) } -> Exposure.PICKING
        else -> Exposure.COLD
    }
    val celsius = step(exposure)
    val silent = events.any { it is SimEvent.SensorDropout && it.isActive(i) }
    if (silent) null else Reading(start.plus(interval.multipliedBy(i.toLong())), celsius)
}
