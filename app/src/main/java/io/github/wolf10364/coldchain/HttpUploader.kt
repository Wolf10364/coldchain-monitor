package io.github.wolf10364.coldchain

import io.github.wolf10364.coldchain.core.Uploader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Posts uploads to the dashboard server. Network errors throw; DashboardSync treats them as a failed attempt. */
class HttpUploader(private val baseUrl: String) : Uploader {
    override suspend fun upload(shipmentId: String, json: String): Boolean = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl/api/shipments/$shipmentId/upload").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
