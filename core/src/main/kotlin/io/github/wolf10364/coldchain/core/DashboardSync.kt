package io.github.wolf10364.coldchain.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sends one upload to the dashboard server. Returns true only when the server confirmed it stored it. */
interface Uploader {
    suspend fun upload(shipmentId: String, json: String): Boolean
}

/** What the app shows about the dashboard connection. [lastAttemptOk] is null before the first attempt. */
data class SyncStatus(val waiting: Int = 0, val lastAttemptOk: Boolean? = null)

/**
 * Keeps the dashboard up to date for one shipment.
 *
 * New readings wait in a queue until the server confirms them, so a dead network loses nothing: the next
 * successful [flush] sends what piled up. The verdict travels with the readings, so the shipment rules live
 * in one place (this module) and the server only stores and shows them.
 *
 * Not thread-safe: call it from one thread (the app uses the main thread).
 */
class DashboardSync(
    private val shipmentId: String,
    private val device: String,
    private val range: TemperatureRange,
    private val uploader: Uploader,
    private val maxBatch: Int = 500,
    private val maxQueued: Int = 20_000,
) {
    private val pending = ArrayDeque<Reading>()
    private var connection = ConnectionState.SCANNING
    private var report: ShipmentReport? = null
    private var changes = 1L // starts "dirty" so the server hears about the shipment straight away
    private var sentChanges = 0L

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun onReading(reading: Reading, latestReport: ShipmentReport) {
        // Until readings are stored on the phone (see TASKS.md), cap the queue so memory cannot run out.
        if (pending.size >= maxQueued) pending.removeFirst()
        pending.addLast(reading)
        report = latestReport
        changes++
        _status.value = _status.value.copy(waiting = pending.size)
    }

    fun onConnection(state: ConnectionState) {
        if (state == connection) return
        connection = state
        changes++
    }

    /** Sends up to [maxBatch] waiting readings plus the latest state. Returns false if the upload failed. */
    suspend fun flush(): Boolean {
        if (pending.isEmpty() && changes == sentChanges) return true
        val batch = pending.take(maxBatch)
        val version = changes
        val json = jsonFormat.encodeToString(
            UploadDto(
                device = device,
                rangeMinC = range.minC.takeIf { it.isFinite() },
                rangeMaxC = range.maxC,
                connection = connection.name,
                readings = batch.map { ReadingDto(it.time.toString(), it.celsius) },
                report = report?.toDto(),
            ),
        )
        val ok = try {
            uploader.upload(shipmentId, json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            // Readings that arrived during the upload sit behind the batch, so dropping from the front is safe.
            repeat(batch.size) { pending.removeFirst() }
            if (pending.isEmpty()) sentChanges = version
        }
        _status.value = SyncStatus(waiting = pending.size, lastAttemptOk = ok)
        return ok
    }

    private companion object {
        val jsonFormat = Json { encodeDefaults = true }
    }
}

@Serializable
internal data class ReadingDto(val time: String, val celsius: Double)

@Serializable
internal data class ExcursionDto(
    val direction: String,
    val start: String,
    val end: String,
    @SerialName("peak_c") val peakC: Double,
)

@Serializable
internal data class GapDto(val start: String, val end: String)

@Serializable
internal data class ReportDto(
    val verdict: String,
    @SerialName("reading_count") val readingCount: Int,
    @SerialName("min_c") val minC: Double,
    @SerialName("max_c") val maxC: Double,
    @SerialName("mean_kinetic_c") val meanKineticC: Double,
    @SerialName("time_out_of_range_s") val timeOutOfRangeS: Long,
    val alarms: Int,
    val excursions: List<ExcursionDto>,
    val gaps: List<GapDto>,
)

@Serializable
internal data class UploadDto(
    val device: String,
    @SerialName("range_min_c") val rangeMinC: Double?,
    @SerialName("range_max_c") val rangeMaxC: Double,
    val connection: String,
    val readings: List<ReadingDto>,
    val report: ReportDto?,
)

internal fun ShipmentReport.toDto() = ReportDto(
    verdict = verdict.name,
    readingCount = readingCount,
    minC = minC,
    maxC = maxC,
    meanKineticC = meanKineticC,
    timeOutOfRangeS = timeOutOfRange.seconds,
    alarms = alarms.size,
    excursions = excursions.map { ExcursionDto(it.direction.name, it.start.toString(), it.end.toString(), it.peakC) },
    gaps = gaps.map { GapDto(it.start.toString(), it.end.toString()) },
)
