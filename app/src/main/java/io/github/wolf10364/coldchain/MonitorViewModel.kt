package io.github.wolf10364.coldchain

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolf10364.coldchain.core.ConnectionState
import io.github.wolf10364.coldchain.core.DashboardSync
import io.github.wolf10364.coldchain.core.Handling
import io.github.wolf10364.coldchain.core.Reading
import io.github.wolf10364.coldchain.core.SensorEvent
import io.github.wolf10364.coldchain.core.ShipmentAnalyzer
import io.github.wolf10364.coldchain.core.ShipmentReport
import io.github.wolf10364.coldchain.core.SimulatedSensorSource
import io.github.wolf10364.coldchain.core.Stage
import io.github.wolf10364.coldchain.core.SyncStatus
import io.github.wolf10364.coldchain.core.TemperatureRange
import io.github.wolf10364.coldchain.core.WarehouseFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MonitorUiState(
    val shipmentId: String = "",
    val connection: ConnectionState = ConnectionState.SCANNING,
    val range: TemperatureRange = TemperatureRange.CHILLED,
    val readings: List<Reading> = emptyList(),
    val report: ShipmentReport? = null,
    val handling: Handling = Handling(),
    val doorOpen: Boolean = false,
    val linkUp: Boolean = true,
    val sync: SyncStatus = SyncStatus(),
)

/**
 * Collects sensor events, keeps the last day of readings, re-runs the shipment report on each one, runs the
 * warehouse checks (goods receipt, picking) and keeps the dashboard server up to date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    // Swap this for a Bluetooth source later; nothing else in the app has to change.
    private val source = SimulatedSensorSource()
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED)
    private val warehouse = WarehouseFlow(analyzer)
    private val device = deviceName(application)

    private val sync = MutableStateFlow(newSync(newShipmentId()))

    /** Earlier shipments whose last readings have not reached the server yet. They are never dropped. */
    private val finishing = mutableListOf<DashboardSync>()

    private val _state = MutableStateFlow(MonitorUiState(shipmentId = sync.value.shipmentId, range = analyzer.range))
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            source.events().collect { event ->
                when (event) {
                    is SensorEvent.Connection -> {
                        _state.update { it.copy(connection = event.state) }
                        sync.value.onConnection(event.state)
                    }
                    is SensorEvent.NewReading -> {
                        val readings = (_state.value.readings + event.reading).takeLast(MAX_READINGS)
                        val report = analyzer.analyze(readings)
                        _state.update { it.copy(readings = readings, report = report) }
                        sync.value.onReading(event.reading, report)
                        refreshPicking()
                    }
                }
            }
        }
        // The picking clock keeps running while the sensor is out of reach, so refresh on time, not only on readings.
        viewModelScope.launch {
            while (isActive) {
                refreshPicking()
                delay(TICK_MILLIS)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                sync.value.flush()
                for (old in finishing.toList()) {
                    if (old.flush() && old.status.value.waiting == 0) finishing.remove(old)
                }
                delay(SYNC_EVERY_MILLIS)
            }
        }
        viewModelScope.launch {
            sync.flatMapLatest { it.status }.collect { status -> _state.update { it.copy(sync = status) } }
        }
    }

    fun setDoorOpen(open: Boolean) {
        source.doorOpen.value = open
        _state.update { it.copy(doorOpen = open) }
    }

    fun setLinkUp(up: Boolean) {
        source.linkUp.value = up
        _state.update { it.copy(linkUp = up) }
    }

    /** Stands in for scanning the shipment at the dock: the receipt check then runs on its own. */
    fun receive() = changeHandling { warehouse.receive(it, _state.value.readings, source.now()) }

    fun startPicking() {
        if (changeHandling { warehouse.startPicking(it, _state.value.readings, source.now()) }) {
            source.picking.value = true
        }
    }

    fun pack() {
        if (changeHandling { warehouse.pack(it, _state.value.readings, source.now()) }) {
            source.picking.value = false
        }
    }

    /** Starts over with a fresh shipment ID. The old shipment keeps uploading until the server has all of it. */
    fun newShipment() {
        finishing += sync.value
        source.picking.value = false
        source.doorOpen.value = false
        source.linkUp.value = true
        source.resetFridge()
        sync.value = newSync(newShipmentId())
        _state.update {
            MonitorUiState(shipmentId = sync.value.shipmentId, connection = it.connection, range = analyzer.range)
        }
    }

    private fun refreshPicking() {
        if (_state.value.handling.stage == Stage.PICKING) {
            changeHandling { warehouse.refresh(it, _state.value.readings, source.now()) }
        }
    }

    /** Applies a step of the warehouse flow. Returns false if the step was not allowed (for example a double tap). */
    private fun changeHandling(change: (Handling) -> Handling): Boolean {
        val before = _state.value.handling
        val after = try {
            change(before)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (after != before) {
            _state.update { it.copy(handling = after) }
            sync.value.onHandling(after)
        }
        return true
    }

    private fun newSync(shipmentId: String) = DashboardSync(
        shipmentId = shipmentId,
        device = device,
        range = analyzer.range,
        uploader = HttpUploader(BuildConfig.DASHBOARD_URL),
    )

    private companion object {
        const val MAX_READINGS = 24 * 60 // one day at one reading per minute
        const val SYNC_EVERY_MILLIS = 2_000L
        const val TICK_MILLIS = 1_000L

        fun newShipmentId(): String {
            val stamp = DateTimeFormatter.ofPattern("yyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
            val suffix = (1..3).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
            return "SHP-$stamp-$suffix"
        }

        fun deviceName(app: Application): String =
            Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    }
}
