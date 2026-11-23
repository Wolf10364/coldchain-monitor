package io.github.wolf10364.coldchain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wolf10364.coldchain.core.ConnectionState
import io.github.wolf10364.coldchain.core.FridgeModel
import io.github.wolf10364.coldchain.core.Handling
import io.github.wolf10364.coldchain.core.PickStatus
import io.github.wolf10364.coldchain.core.PickingCheck
import io.github.wolf10364.coldchain.core.PickingRules
import io.github.wolf10364.coldchain.core.Reading
import io.github.wolf10364.coldchain.core.ReceiptCheck
import io.github.wolf10364.coldchain.core.ReceiptDecision
import io.github.wolf10364.coldchain.core.ShipmentAnalyzer
import io.github.wolf10364.coldchain.core.ShipmentReport
import io.github.wolf10364.coldchain.core.SimEvent
import io.github.wolf10364.coldchain.core.Stage
import io.github.wolf10364.coldchain.core.SyncStatus
import io.github.wolf10364.coldchain.core.TemperatureRange
import io.github.wolf10364.coldchain.core.Verdict
import io.github.wolf10364.coldchain.core.WarehouseFlow
import io.github.wolf10364.coldchain.core.simulate
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF757575)

private const val CHART_POINTS = 120 // last two simulated hours

@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MonitorContent(
        state,
        MonitorActions(
            onDoorChange = viewModel::setDoorOpen,
            onLinkChange = viewModel::setLinkUp,
            onReceive = viewModel::receive,
            onStartPicking = viewModel::startPicking,
            onPack = viewModel::pack,
            onNewShipment = viewModel::newShipment,
        ),
    )
}

/** Everything the user can do on the screen. */
data class MonitorActions(
    val onDoorChange: (Boolean) -> Unit = {},
    val onLinkChange: (Boolean) -> Unit = {},
    val onReceive: () -> Unit = {},
    val onStartPicking: () -> Unit = {},
    val onPack: () -> Unit = {},
    val onNewShipment: () -> Unit = {},
)

@Composable
fun MonitorContent(state: MonitorUiState, actions: MonitorActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Shipment monitor", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (state.shipmentId.isNotEmpty()) Text("Shipment ${state.shipmentId}", color = Grey)
        Text("Allowed range: ${state.range.describe()}", color = Grey)
        ConnectionBadge(state.connection)
        CurrentTemperature(state)
        TemperatureChart(
            readings = state.readings.takeLast(CHART_POINTS),
            range = state.range,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        state.report?.let { ReportCard(it) }
        WarehouseCard(state.handling, actions)
        DashboardStatus(state.sync)
        DemoControls(state, actions)
    }
}

@Composable
private fun WarehouseCard(handling: Handling, actions: MonitorActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Warehouse checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            handling.receipt?.let { ReceiptSection(it) }
            when (handling.stage) {
                Stage.IN_TRANSIT -> {
                    Text("In transit. When the shipment is scanned in at the dock, the receipt check runs on its own: no probe thermometer needed.")
                    Button(onClick = actions.onReceive) { Text("Scan in at the dock") }
                }
                Stage.RECEIVED -> when (handling.receipt?.decision) {
                    ReceiptDecision.ACCEPT -> {
                        Text("Stored in the cold room. Picking may take the goods out of the cold for up to ${formatDuration(PickingRules.DEFAULT_MAX_OUT_OF_COLD)}.")
                        Button(onClick = actions.onStartPicking) { Text("Start picking") }
                    }
                    ReceiptDecision.QUARANTINE -> Text("Picking blocked until quality releases the goods.", color = Grey)
                    else -> Text("Picking blocked: the delivery is refused.", color = Grey)
                }
                Stage.PICKING -> {
                    handling.picking?.let { PickingSection(it) }
                    Button(onClick = actions.onPack) { Text("Packed into a cool box") }
                }
                Stage.PACKED -> handling.picking?.let { PickingSection(it) }
            }
        }
    }
}

@Composable
private fun ReceiptSection(receipt: ReceiptCheck) {
    val (label, color) = when (receipt.decision) {
        ReceiptDecision.ACCEPT -> "Goods receipt: ACCEPTED" to Green
        ReceiptDecision.QUARANTINE -> "Goods receipt: QUARANTINE, quality decides" to Amber
        ReceiptDecision.REJECT -> "Goods receipt: REJECTED, refuse the delivery" to Red
    }
    Text(label, color = color, fontWeight = FontWeight.Bold)
    receipt.reasons.forEach { Text("\u2022 $it") }
}

@Composable
private fun PickingSection(picking: PickingCheck) {
    val share = picking.outOfCold.seconds.toFloat() / picking.limit.seconds.coerceAtLeast(1)
    val running = picking.end == null
    val (label, color) = when (picking.status) {
        PickStatus.WITHIN_LIMITS ->
            "Picking: ${formatDuration(picking.outOfCold)} of ${formatDuration(picking.limit)} out of the cold" to
                (if (share > 0.75f) Amber else Green)
        PickStatus.PASSED -> "Picking: PASSED" to Green
        PickStatus.FAILED ->
            (if (running) "Picking: limit broken, return the goods to the cold room" else "Picking: FAILED") to Red
    }
    Text(label, color = color, fontWeight = FontWeight.Bold)
    if (running) {
        LinearProgressIndicator(progress = { share.coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth())
        picking.maxC?.let { Text("Warmest reading so far: ${formatC(it)}") }
    }
    picking.reasons.forEach { Text("\u2022 $it") }
}

@Composable
private fun DashboardStatus(sync: SyncStatus) {
    val ok = sync.lastAttemptOk
    val (text, color) = when {
        ok == null -> "Dashboard: connecting" to Grey
        // A reading or two always waits for the next upload (readings come every second, uploads every two).
        ok && sync.waiting <= 5 -> "Dashboard: up to date" to Green
        ok -> "Dashboard: catching up, ${count(sync.waiting, "reading")} to send" to Amber
        else -> "Dashboard: server unreachable, ${count(sync.waiting, "reading")} kept on the phone" to Amber
    }
    Text(text, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun ConnectionBadge(connection: ConnectionState) {
    val (label, color) = when (connection) {
        ConnectionState.SCANNING -> "Looking for sensor" to Grey
        ConnectionState.CONNECTED -> "Sensor connected" to Green
        ConnectionState.LOST -> "Sensor connection lost" to Red
        ConnectionState.RECONNECTING -> "Reconnecting" to Amber
    }
    Text(
        text = label,
        color = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun CurrentTemperature(state: MonitorUiState) {
    val current = state.readings.lastOrNull()
    val live = state.connection == ConnectionState.CONNECTED
    // A stale value shown as if it were live is exactly the mistake a cold chain cannot afford.
    val color = when {
        current == null || !live -> Grey
        current.celsius in state.range -> Green
        else -> Red
    }
    Column {
        Text(
            text = current?.let { formatC(it.celsius) } ?: "--",
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        if (current != null && !live) {
            Text("Last known reading, sensor not connected", color = Grey)
        }
    }
}

@Composable
private fun TemperatureChart(readings: List<Reading>, range: TemperatureRange, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.background(Color(0x11000000), RoundedCornerShape(8.dp))) {
        if (readings.size < 2) return@Canvas
        val values = readings.map { it.celsius }
        val low = minOf(values.min(), range.minC.takeIf { it.isFinite() } ?: values.min()) - 1.0
        val high = maxOf(values.max(), range.maxC) + 1.0
        fun y(c: Double) = (size.height * (1 - (c - low) / (high - low))).toFloat()

        // the allowed band
        val bandTop = y(range.maxC)
        val bandBottom = if (range.minC.isFinite()) y(range.minC) else size.height
        drawRect(Green.copy(alpha = 0.15f), topLeft = Offset(0f, bandTop), size = Size(size.width, bandBottom - bandTop))

        val stepX = size.width / (readings.size - 1)
        val path = Path().apply {
            values.forEachIndexed { i, c -> if (i == 0) moveTo(0f, y(c)) else lineTo(i * stepX, y(c)) }
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
    }
}

@Composable
private fun ReportCard(report: ShipmentReport) {
    val (label, color) = when (report.verdict) {
        Verdict.PASS -> "PASS: stayed in range" to Green
        Verdict.FAIL -> "FAIL: out of range for too long" to Red
        Verdict.INCOMPLETE -> "INCOMPLETE: gaps in the data" to Amber
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Bold)
            Text("${report.readingCount} readings over ${formatDuration(Duration.between(report.start, report.end))}")
            Text("Min ${formatC(report.minC)}, max ${formatC(report.maxC)}, MKT ${formatC(report.meanKineticC)}")
            Text(
                "Out of range: ${formatDuration(report.timeOutOfRange)} " +
                    "(${count(report.excursions.size, "excursion")}, ${count(report.alarms.size, "alarm")})",
            )
            if (report.gaps.isNotEmpty()) {
                Text("No data: ${formatDuration(report.timeWithoutData)} in ${count(report.gaps.size, "gap")}")
            }
        }
    }
}

@Composable
private fun DemoControls(state: MonitorUiState, actions: MonitorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Demo: simulated sensor, 1 second = 1 minute", style = MaterialTheme.typography.titleSmall)
        LabeledSwitch("Fridge door open", state.doorOpen, actions.onDoorChange)
        LabeledSwitch("Sensor within reach", state.linkUp, actions.onLinkChange)
        OutlinedButton(onClick = actions.onNewShipment, modifier = Modifier.fillMaxWidth()) { Text("Start a new shipment") }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatC(celsius: Double) = String.format(Locale.getDefault(), "%.1f °C", celsius)

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun formatDuration(d: Duration): String {
    val hours = d.toHours()
    val minutes = d.toMinutes() % 60
    return if (hours > 0) "$hours h $minutes min" else "${d.toMinutes()} min"
}

@Preview(showBackground = true)
@Composable
private fun MonitorPreview() {
    val readings = FridgeModel(random = Random(1)).simulate(
        start = Instant.parse("2026-11-23T08:00:00Z"),
        interval = Duration.ofMinutes(1),
        steps = 75,
        events = listOf(SimEvent.Picking(fromStep = 60, steps = 15)),
    )
    val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED)
    val flow = WarehouseFlow(analyzer)
    val received = flow.receive(Handling(), readings.take(60), readings[59].time)
    val picking = flow.refresh(flow.startPicking(received, readings.take(60), readings[59].time), readings, readings.last().time)
    val state = MonitorUiState(
        connection = ConnectionState.CONNECTED,
        readings = readings,
        report = analyzer.analyze(readings),
        handling = picking,
    )
    MaterialTheme { MonitorContent(state, MonitorActions()) }
}
