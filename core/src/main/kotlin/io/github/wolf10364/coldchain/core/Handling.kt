package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Where the goods are in the warehouse flow. */
enum class Stage { IN_TRANSIT, RECEIVED, PICKING, PACKED }

/** Ordered from best to worst, so the worst finding of a check wins. */
enum class ReceiptDecision { ACCEPT, QUARANTINE, REJECT }

/** The automatic temperature check at goods receipt. [transportVerdict] is null if the trip has no readings. */
data class ReceiptCheck(
    val decision: ReceiptDecision,
    val at: Instant,
    val arrivalC: Double?,
    val transportVerdict: Verdict?,
    val reasons: List<String>,
)

enum class PickStatus { WITHIN_LIMITS, PASSED, FAILED }

/** The temperature check while goods are out of the cold room for picking. [end] is null while picking runs. */
data class PickingCheck(
    val start: Instant,
    val end: Instant?,
    val outOfCold: Duration,
    val limit: Duration,
    val maxC: Double?,
    val status: PickStatus,
    val reasons: List<String>,
)

/** Everything the warehouse knows about the shipment so far. */
data class Handling(
    val stage: Stage = Stage.IN_TRANSIT,
    val receipt: ReceiptCheck? = null,
    val picking: PickingCheck? = null,
) {
    /** Only goods that passed the receipt check may be picked. */
    val canStartPicking: Boolean
        get() = stage == Stage.RECEIVED && receipt?.decision == ReceiptDecision.ACCEPT
}

/**
 * Decides at the dock whether goods may be put away, from the temperature on arrival and the logger's history
 * of the trip. Nobody has to probe the goods: the check runs the moment the shipment is received.
 *
 * - REJECT: too warm or too cold on arrival, or an alarm in transit.
 * - QUARANTINE: gaps in the history, no history at all, or no current reading, so nobody can confirm the
 *   cold chain held. Quality decides.
 * - ACCEPT: everything else. Short excursions within the tolerance are noted, not held against the goods.
 */
class ReceivingInspector(
    private val analyzer: ShipmentAnalyzer,
    /** An arrival reading older than this does not count as a current measurement. */
    private val maxReadingAge: Duration = Duration.ofMinutes(10),
) {
    fun inspect(transport: ShipmentReport?, latest: Reading?, now: Instant): ReceiptCheck {
        val range = analyzer.range
        var decision = ReceiptDecision.ACCEPT
        val reasons = mutableListOf<String>()
        fun find(severity: ReceiptDecision, reason: String) {
            if (severity > decision) decision = severity
            reasons += reason
        }

        when {
            latest == null || Duration.between(latest.time, now) > maxReadingAge ->
                find(ReceiptDecision.QUARANTINE, "No current reading at arrival")
            latest.celsius > range.maxC ->
                find(ReceiptDecision.REJECT, "Arrived at ${celsius(latest.celsius)}, above the ${celsius(range.maxC)} limit")
            latest.celsius < range.minC ->
                find(ReceiptDecision.REJECT, "Arrived at ${celsius(latest.celsius)}, below the ${celsius(range.minC)} limit")
            else -> reasons += "Arrived at ${celsius(latest.celsius)}"
        }

        if (transport == null) {
            find(ReceiptDecision.QUARANTINE, "No temperature history for the trip")
        } else {
            val longestAlarm = transport.alarms.maxOfOrNull { it.duration }
            if (longestAlarm != null) {
                find(ReceiptDecision.REJECT,
                    "Out of range for ${minutes(longestAlarm)} in transit (limit ${minutes(analyzer.tolerance)})")
            }
            if (transport.gaps.isNotEmpty()) {
                find(ReceiptDecision.QUARANTINE, "No data for ${minutes(transport.timeWithoutData)} in transit")
            }
            val shortOnes = transport.excursions.size - transport.alarms.size
            if (shortOnes > 0) {
                reasons += "$shortOnes short excursion${if (shortOnes == 1) "" else "s"} in transit, within tolerance"
            }
            if (transport.excursions.isEmpty() && transport.gaps.isEmpty()) {
                reasons += "Stayed ${range.describe()} for the whole trip"
            }
        }

        val arrivalC = latest?.celsius?.takeIf { Duration.between(latest.time, now) <= maxReadingAge }
        return ReceiptCheck(decision, now, arrivalC, transport?.verdict, reasons)
    }
}

/**
 * Checks that goods stayed cold while out of the cold room for picking: no longer than [maxOutOfCold], never
 * outside the allowed range, and never without readings for longer than [maxGap]. While picking runs ([end]
 * is null) it reports WITHIN_LIMITS until a limit is broken, so the picker sees the problem as it happens.
 * Unconfirmed counts as failed here: after picking, the goods go to the customer.
 */
class PickingRules(
    private val range: TemperatureRange,
    val maxOutOfCold: Duration = DEFAULT_MAX_OUT_OF_COLD,
    private val maxGap: Duration = Duration.ofMinutes(10),
) {
    companion object {
        val DEFAULT_MAX_OUT_OF_COLD: Duration = Duration.ofMinutes(20)
    }

    fun check(start: Instant, end: Instant?, now: Instant, readings: List<Reading>): PickingCheck {
        val until = end ?: now
        val during = readings.filter { !it.time.isBefore(start) && !it.time.isAfter(until) }.sortedBy { it.time }
        val outOfCold = Duration.between(start, until)
        val maxC = during.maxOfOrNull { it.celsius }
        val minC = during.minOfOrNull { it.celsius }
        val times = listOf(start) + during.map { it.time } + until
        val longestSilence = times.zipWithNext { a, b -> Duration.between(a, b) }.max()

        val reasons = mutableListOf<String>()
        if (outOfCold > maxOutOfCold) {
            reasons += "Out of the cold room for ${minutes(outOfCold)} (limit ${minutes(maxOutOfCold)})"
        }
        if (maxC != null && maxC > range.maxC) {
            reasons += "Reached ${celsius(maxC)} while picking (limit ${celsius(range.maxC)})"
        }
        if (minC != null && minC < range.minC) {
            reasons += "Dropped to ${celsius(minC)} while picking (limit ${celsius(range.minC)})"
        }
        if (longestSilence > maxGap) {
            reasons += "No readings for ${minutes(longestSilence)} while picking"
        } else if (end != null && during.isEmpty()) {
            reasons += "No readings while picking, so the temperature cannot be confirmed"
        }

        val status = when {
            reasons.isNotEmpty() -> PickStatus.FAILED
            end == null -> PickStatus.WITHIN_LIMITS
            else -> PickStatus.PASSED
        }
        if (status == PickStatus.PASSED && maxC != null) {
            reasons += "Packed after ${minutes(outOfCold)}, warmest reading ${celsius(maxC)}"
        }
        return PickingCheck(start, end, outOfCold, maxOutOfCold, maxC, status, reasons)
    }
}

/**
 * Moves a shipment through the warehouse and runs the right temperature check at each step:
 * in transit, received at the dock (receipt check), picking (live check), packed into a cool box (final check).
 */
class WarehouseFlow(
    private val analyzer: ShipmentAnalyzer,
    private val inspector: ReceivingInspector = ReceivingInspector(analyzer),
    private val pickingRules: PickingRules = PickingRules(analyzer.range),
) {
    /** The goods arrive at the dock. The receipt check runs on its own, on the whole trip so far. */
    fun receive(current: Handling, readings: List<Reading>, now: Instant): Handling {
        require(current.stage == Stage.IN_TRANSIT) { "The shipment was already received" }
        val transport = readings.takeIf { it.isNotEmpty() }?.let(analyzer::analyze)
        val receipt = inspector.inspect(transport, readings.maxByOrNull { it.time }, now)
        return current.copy(stage = Stage.RECEIVED, receipt = receipt)
    }

    /** The goods leave the cold room. Only allowed after an accepted receipt. */
    fun startPicking(current: Handling, readings: List<Reading>, now: Instant): Handling {
        require(current.canStartPicking) { "Only received and accepted goods can be picked" }
        return current.copy(stage = Stage.PICKING, picking = pickingRules.check(now, null, now, readings))
    }

    /** Re-runs the live picking check. Outside picking, nothing changes. */
    fun refresh(current: Handling, readings: List<Reading>, now: Instant): Handling {
        val picking = current.picking
        if (current.stage != Stage.PICKING || picking == null) return current
        return current.copy(picking = pickingRules.check(picking.start, null, now, readings))
    }

    /** The goods are packed into a cool box: the picking check is final. */
    fun pack(current: Handling, readings: List<Reading>, now: Instant): Handling {
        val picking = current.picking
        require(current.stage == Stage.PICKING && picking != null) { "Picking has not started" }
        return current.copy(stage = Stage.PACKED, picking = pickingRules.check(picking.start, now, now, readings))
    }
}

internal fun celsius(c: Double): String = String.format(Locale.ROOT, "%.1f °C", c)

internal fun minutes(d: Duration): String = "${d.toMinutes()} min"
