package io.github.wolf10364.coldchain

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolf10364.coldchain.core.ConnectionState
import io.github.wolf10364.coldchain.core.DashboardSync
import io.github.wolf10364.coldchain.core.Reading
import io.github.wolf10364.coldchain.core.SensorEvent
import io.github.wolf10364.coldchain.core.ShipmentAnalyzer
import io.github.wolf10364.coldchain.core.ShipmentReport
import io.github.wolf10364.coldchain.core.SimulatedSensorSource
import io.github.wolf10364.coldchain.core.SyncStatus
import io.github.wolf10364.coldchain.core.TemperatureRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MonitorUiState(
    val shipmentId: String = "",
    val connection: ConnectionState = ConnectionState.SCANNING,
    val range: TemperatureRange = TemperatureRange.CHILLED,
    val readings: List<Reading> = emptyList(),
    val report: ShipmentReport? = null,
    val doorOpen: Boolean = false,
    val linkUp: Boolean = true,
    val sync: SyncStatus = SyncStatus(),
)

/**
 * Collects sensor events, keeps the last day of readings, re-runs the shipment report on each one and
 * keeps the dashboard server up to date.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    // Swap this for a Bluetooth source later; nothing else in the app has to change.
    private val source = SimulatedSensorSource()
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED)

    private val shipmentId = newShipmentId()
    private val sync = DashboardSync(
        shipmentId = shipmentId,
        device = deviceName(application),
        range = analyzer.range,
        uploader = HttpUploader(BuildConfig.DASHBOARD_URL),
    )

    private val _state = MutableStateFlow(MonitorUiState(shipmentId = shipmentId, range = analyzer.range))
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            source.events().collect { event ->
                when (event) {
                    is SensorEvent.Connection -> {
                        _state.update { it.copy(connection = event.state) }
                        sync.onConnection(event.state)
                    }
                    is SensorEvent.NewReading -> {
                        val readings = (_state.value.readings + event.reading).takeLast(MAX_READINGS)
                        val report = analyzer.analyze(readings)
                        _state.update { it.copy(readings = readings, report = report) }
                        sync.onReading(event.reading, report)
                    }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                sync.flush()
                delay(SYNC_EVERY_MILLIS)
            }
        }
        viewModelScope.launch {
            sync.status.collect { status -> _state.update { it.copy(sync = status) } }
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

    private companion object {
        const val MAX_READINGS = 24 * 60 // one day at one reading per minute
        const val SYNC_EVERY_MILLIS = 2_000L

        fun newShipmentId(): String {
            val stamp = DateTimeFormatter.ofPattern("yyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
            val suffix = (1..3).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
            return "SHP-$stamp-$suffix"
        }

        fun deviceName(app: Application): String =
            Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    }
}
