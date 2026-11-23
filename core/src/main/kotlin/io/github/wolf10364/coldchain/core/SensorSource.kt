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
}

/**
 * Live readings from a [FridgeModel], one every [tickMillis] of real time, each stamped [simulatedStep] later
 * than the last (by default one simulated minute per real second, so a demo shows an hour in a minute).
 *
 * [doorOpen] and [linkUp] are switches the UI can flip to cause an excursion or a lost connection on purpose.
 * Collect [events] once: the model's state is shared.
 */
class SimulatedSensorSource(
    private val model: FridgeModel = FridgeModel(),
    private val start: Instant = Instant.now(),
    private val simulatedStep: Duration = Duration.ofMinutes(1),
    private val tickMillis: Long = 1_000,
) : SensorSource {
    val doorOpen = MutableStateFlow(false)
    val linkUp = MutableStateFlow(true)

    override fun events(): Flow<SensorEvent> = flow {
        emit(SensorEvent.Connection(ConnectionState.SCANNING))
        delay(tickMillis)
        emit(SensorEvent.Connection(ConnectionState.CONNECTED))
        var connected = true
        var step = 0L
        while (true) {
            // The fridge keeps warming or cooling while the link is down; the phone just doesn't hear about it.
            val celsius = model.step(doorOpen.value)
            if (linkUp.value) {
                if (!connected) {
                    emit(SensorEvent.Connection(ConnectionState.RECONNECTING))
                    emit(SensorEvent.Connection(ConnectionState.CONNECTED))
                    connected = true
                }
                emit(SensorEvent.NewReading(Reading(start.plus(simulatedStep.multipliedBy(step)), celsius)))
            } else if (connected) {
                emit(SensorEvent.Connection(ConnectionState.LOST))
                connected = false
            }
            step++
            delay(tickMillis)
        }
    }
}
