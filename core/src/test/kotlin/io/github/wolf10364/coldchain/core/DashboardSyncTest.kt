package io.github.wolf10364.coldchain.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DashboardSyncTest {
    private val t0: Instant = Instant.parse("2026-11-23T08:00:00Z")
    private val analyzer = ShipmentAnalyzer(TemperatureRange.CHILLED)

    /** A fake server: records what it was sent and answers with [accept]. */
    private class FakeServer(var accept: Boolean = true) : Uploader {
        val received = mutableListOf<JsonObject>()
        var duringUpload: (() -> Unit)? = null
        override suspend fun upload(shipmentId: String, json: String): Boolean {
            duringUpload?.invoke()
            if (accept) received += Json.parseToJsonElement(json).jsonObject
            return accept
        }
    }

    private fun reading(minute: Long, celsius: Double = 4.0) = Reading(t0.plus(Duration.ofMinutes(minute)), celsius)

    private fun DashboardSync.add(vararg minutes: Long) {
        minutes.forEach { m -> onReading(reading(m), analyzer.analyze(listOf(reading(m)))) }
    }

    private fun JsonObject.readingTimes() = this["readings"]!!.jsonArray.map { it.jsonObject["time"]!!.jsonPrimitive.content }

    private fun sync(server: FakeServer, range: TemperatureRange = TemperatureRange.CHILLED, maxBatch: Int = 500) =
        DashboardSync("SHP-1", "Test phone", range, server, maxBatch)

    @Test
    fun `sends readings with the verdict, then stays quiet when nothing changed`() = runTest {
        val server = FakeServer()
        val sync = sync(server)
        sync.add(0, 1)

        assertTrue(sync.flush())
        assertTrue(sync.flush())

        assertEquals(1, server.received.size)
        val upload = server.received.single()
        assertEquals(listOf("2026-11-23T08:00:00Z", "2026-11-23T08:01:00Z"), upload.readingTimes())
        assertEquals("PASS", upload["report"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
        assertEquals(SyncStatus(waiting = 0, lastAttemptOk = true), sync.status.value)
    }

    @Test
    fun `nothing is lost while the server is unreachable`() = runTest {
        val server = FakeServer(accept = false)
        val sync = sync(server)
        sync.add(0, 1)
        assertFalse(sync.flush())
        sync.add(2)
        assertFalse(sync.flush())
        assertEquals(SyncStatus(waiting = 3, lastAttemptOk = false), sync.status.value)

        server.accept = true
        assertTrue(sync.flush())

        assertEquals(1, server.received.size)
        assertEquals(3, server.received.single().readingTimes().size)
        assertEquals(0, sync.status.value.waiting)
    }

    @Test
    fun `a crashing uploader counts as a failed attempt`() = runTest {
        val crashing = object : Uploader {
            override suspend fun upload(shipmentId: String, json: String): Boolean = error("no network")
        }
        val sync = DashboardSync("SHP-1", "Test phone", TemperatureRange.CHILLED, crashing)
        sync.add(0)
        assertFalse(sync.flush())
        assertEquals(1, sync.status.value.waiting)
    }

    @Test
    fun `readings arriving during an upload are kept for the next one`() = runTest {
        val server = FakeServer()
        val sync = sync(server)
        sync.add(0)
        server.duringUpload = { sync.add(1); server.duringUpload = null }

        assertTrue(sync.flush())
        assertTrue(sync.flush())

        assertEquals(listOf(listOf("2026-11-23T08:00:00Z"), listOf("2026-11-23T08:01:00Z")), server.received.map { it.readingTimes() })
    }

    @Test
    fun `a connection change alone is reported`() = runTest {
        val server = FakeServer()
        val sync = sync(server)
        sync.flush() // first contact
        sync.onConnection(ConnectionState.LOST)

        assertTrue(sync.flush())

        assertEquals(2, server.received.size)
        assertEquals("LOST", server.received.last()["connection"]!!.jsonPrimitive.content)
        assertTrue(server.received.last().readingTimes().isEmpty())
    }

    @Test
    fun `big backlogs go out in batches`() = runTest {
        val server = FakeServer()
        val sync = sync(server, maxBatch = 500)
        sync.add(*LongArray(1_200) { it.toLong() })

        repeat(3) { assertTrue(sync.flush()) }
        assertTrue(sync.flush()) // nothing left: no fourth upload

        assertEquals(listOf(500, 500, 200), server.received.map { it.readingTimes().size })
    }

    @Test
    fun `frozen range is sent with no lower limit`() = runTest {
        val server = FakeServer()
        val sync = sync(server, range = TemperatureRange.FROZEN)
        sync.flush()

        assertEquals(JsonNull, server.received.single()["range_min_c"])
        assertEquals(-18.0, server.received.single()["range_max_c"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `warehouse checks reach the dashboard, and only when they change`() = runTest {
        val server = FakeServer()
        val sync = sync(server)
        sync.add(0)
        sync.flush()
        assertEquals(JsonNull, server.received.single()["handling"])

        val received = WarehouseFlow(analyzer).receive(Handling(), listOf(reading(0)), now = reading(0).time)
        sync.onHandling(received)
        sync.flush()
        sync.onHandling(received.copy()) // equal: nothing new to send
        sync.flush()

        assertEquals(2, server.received.size)
        val handling = server.received.last()["handling"]!!.jsonObject
        assertEquals("RECEIVED", handling["stage"]!!.jsonPrimitive.content)
        val receipt = handling["receipt"]!!.jsonObject
        assertEquals("ACCEPT", receipt["decision"]!!.jsonPrimitive.content)
        assertEquals(4.0, receipt["arrival_c"]!!.jsonPrimitive.content.toDouble())
        assertEquals(JsonNull, handling["picking"])
    }
}
