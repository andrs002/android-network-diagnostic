package com.andrs002.networkdiagnostic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class TelemetryUploader(private val context: Context) {
    private val lock = Any()
    private val queueFile = File(context.filesDir, "network_telemetry_v10.jsonl")
    private val deviceId: String by lazy {
        val p = context.getSharedPreferences("netdiag_telemetry", Context.MODE_PRIVATE)
        p.getString("device_id", null) ?: ("netdiag-" + UUID.randomUUID()).also { p.edit().putString("device_id", it).apply() }
    }

    fun enqueue(summary: String, rawLines: String) = synchronized(lock) {
        val ts = Instant.now().toString()
        val eventType = summary.substringBefore(':').substringBefore(',').ifBlank { "EVENT" }.take(64)
        val raw = JSONObject().put("summary", summary).put("log", rawLines.takeLast(24000)).put("app", "network-diagnostic-v10")
        val item = JSONObject()
            .put("record_key", sha256("$deviceId|$ts|$summary"))
            .put("start_time", ts).put("event_type", eventType).put("summary", summary)
            .put("source_device", android.os.Build.MODEL).put("app_version", "network-diagnostic-v10").put("raw", raw)
        queueFile.appendText(item.toString() + "\n")
        trimQueue()
    }

    fun flush(): String = synchronized(lock) {
        val all = readQueue()
        if (all.isEmpty()) return "EMPTY"
        try {
            val token = anonymousToken()
            val batch = all.take(10)
            val c = URL(DATA_URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal")
            c.doOutput = true; c.outputStream.use { it.write(JSONArray(batch).toString().toByteArray()) }
            val code = c.responseCode
            val err = if (code !in 200..299) runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
            c.disconnect()
            if (code in 200..299) {
                writeQueue(all.drop(batch.size))
                "UPLOADED:${batch.size}:REMAINING=${all.size-batch.size}"
            } else "HTTP:$code${if (!err.isNullOrBlank()) ":${err.take(160)}" else ""}:QUEUED=${all.size}"
        } catch (e: Exception) { "FAIL:${e.javaClass.simpleName}:${e.message ?: ""}:QUEUED=${all.size}" }
    }

    private fun readQueue(): List<JSONObject> = if (!queueFile.exists()) emptyList() else queueFile.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    private fun writeQueue(items: List<JSONObject>) { if (items.isEmpty()) queueFile.delete() else queueFile.writeText(items.joinToString("\n") { it.toString() } + "\n") }
    private fun trimQueue() { val q=readQueue(); if(q.size>300) writeQueue(q.takeLast(300)) }
    private fun anonymousToken(): String { val c=URL(AUTH_URL).openConnection() as HttpURLConnection;c.requestMethod="GET";c.connectTimeout=6000;c.readTimeout=6000;val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};c.disconnect();if(code !in 200..299) error("auth $code");return JSONObject(text).getString("token") }
    private fun sha256(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    companion object { private const val AUTH_URL="https://ep-autumn-shadow-aesref0t.neonauth.c-2.us-east-2.aws.neon.tech/hcbridge/auth/token/anonymous"; private const val DATA_URL="https://ep-autumn-shadow-aesref0t.apirest.c-2.us-east-2.aws.neon.tech/hcbridge/rest/v1/network_diagnostic_events?on_conflict=record_key" }
}
