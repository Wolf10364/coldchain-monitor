package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

enum class ConnectionState { SCANNING, CONNECTED, LOST, RECONNECTING }

sealed interface SensorEvent {
    data class Connection(val state: ConnectionState) : SensorEvent
    data class NewReading(val reading: Reading) : SensorEvent
}

/**
 * Anything that delivers live readings. The app only knows this interface, so the simulator today and a
 * Bluetooth sensor later are interchangeable.
 */
interface SensorSource {
    fun events(): Flow<SensorEvent>

    /** The current time in the sensor's timeline. A real sensor uses the clock; the simulator runs faster. */
    fun now(): Instant = Instant.now()
}

/**
 * Live readings from a [FridgeModel], one every [tickMillis] of real time, each stamped [simulatedStep] later
 * than the last (by default one simulated minute per real second, so a demo shows an hour in a minute).
 *
 * [doorOpen] and [linkUp] are switches the UI can flip to cause an excursion or a lost connection on purpose;
 * [picking] takes the goods out of the cold room, where they warm slowly. Collect [events] once: the model's
 * state is shared.
 */
class SimulatedSensorSource(
    private val model: FridgeModel = FridgeModel(),
    private val start: Instant = Instant.now(),
    private val simulatedStep: Duration = Duration.ofMinutes(1),
    private val tickMillis: Long = 1_000,
) : SensorSource {
    val doorOpen = MutableStateFlow(false)
    val linkUp = MutableStateFlow(true)
    val picking = MutableStateFlow(false)

    @Volatile
    private var current: Instant = start

    /** The simulated time of the latest step, whether or not its reading reached the phone. */
    override fun now(): Instant = current

    /** Cools the goods back to the set point, for example when a new shipment starts. */
    fun resetFridge() = model.reset()

    override fun events(): Flow<SensorEvent> = flow {
        emit(SensorEvent.Connection(ConnectionState.SCANNING))
        delay(tickMillis)
        emit(SensorEvent.Connection(ConnectionState.CONNECTED))
        var connected = true
        var step = 0L
        while (true) {
            // The fridge keeps warming or cooling while the link is down; the phone just doesn't hear about it.
            val exposure = when {
                doorOpen.value -> Exposure.DOOR_OPEN
                picking.value -> Exposure.PICKING
                else -> Exposure.COLD
            }
            val celsius = model.step(exposure)
            current = start.plus(simulatedStep.multipliedBy(step))
            if (linkUp.value) {
                if (!connected) {
                    emit(SensorEvent.Connection(ConnectionState.RECONNECTING))
                    emit(SensorEvent.Connection(ConnectionState.CONNECTED))
                    connected = true
                }
                emit(SensorEvent.NewReading(Reading(current, celsius)))
            } else if (connected) {
                emit(SensorEvent.Connection(ConnectionState.LOST))
                connected = false
            }
            step++
            delay(tickMillis)
        }
    }
}
