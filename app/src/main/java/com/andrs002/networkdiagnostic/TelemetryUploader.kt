package com.andrs002.networkdiagnostic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class TelemetryUploader(private val context: Context) {
    private val pending = ConcurrentLinkedQueue<JSONObject>()
    private val deviceId: String by lazy {
        val p = context.getSharedPreferences("netdiag_telemetry", Context.MODE_PRIVATE)
        p.getString("device_id", null) ?: ("netdiag-" + UUID.randomUUID()).also {
            p.edit().putString("device_id", it).apply()
        }
    }

    fun enqueue(summary: String, rawLines: String) {
        val ts = Instant.now().toString()
        val eventType = summary.substringBefore(':').substringBefore(',').ifBlank { "EVENT" }.take(64)
        val raw = JSONObject().put("summary", summary).put("log", rawLines.takeLast(16000)).put("app", "network-diagnostic-v9")
        pending.add(JSONObject()
            .put("record_key", sha256("$deviceId|$ts|$summary"))
            .put("start_time", ts)
            .put("event_type", eventType)
            .put("summary", summary)
            .put("source_device", android.os.Build.MODEL)
            .put("app_version", "network-diagnostic-v9")
            .put("raw", raw))
        while (pending.size > 30) pending.poll()
    }

    fun flush(): String {
        if (pending.isEmpty()) return "EMPTY"
        return try {
            val token = anonymousToken()
            val batch = mutableListOf<JSONObject>()
            while (batch.size < 10) pending.poll()?.let { batch += it } ?: break
            val body = JSONArray(batch).toString()
            val c = URL(DATA_URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal")
            c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val err = if (code !in 200..299) runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
            c.disconnect()
            if (code in 200..299) "UPLOADED:${batch.size}" else {
                batch.forEach { pending.add(it) }
                "HTTP:$code${if (!err.isNullOrBlank()) ":${err.take(160)}" else ""}"
            }
        } catch (e: Exception) {
            "FAIL:${e.javaClass.simpleName}:${e.message ?: ""}"
        }
    }

    private fun anonymousToken(): String {
        val c = URL(AUTH_URL).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 6000; c.readTimeout = 6000
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        c.disconnect()
        if (code !in 200..299) error("auth $code")
        return JSONObject(text).getString("token")
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val AUTH_URL = "https://ep-autumn-shadow-aesref0t.neonauth.c-2.us-east-2.aws.neon.tech/hcbridge/auth/token/anonymous"
        private const val DATA_URL = "https://ep-autumn-shadow-aesref0t.apirest.c-2.us-east-2.aws.neon.tech/hcbridge/rest/v1/network_diagnostic_events?on_conflict=record_key"
    }
}
