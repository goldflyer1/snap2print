package com.snap2print.app

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for Meshy's Image-to-3D API (https://docs.meshy.ai).
 * One photo  -> POST /openapi/v1/image-to-3d
 * 2–4 photos -> POST /openapi/v1/multi-image-to-3d
 * Then poll GET /openapi/v1/<endpoint>/<taskId> until SUCCEEDED and download the mesh.
 */
class MeshyClient(private val apiKey: String) {

    data class Result(val bytes: ByteArray, val fileName: String)

    private val base = "https://api.meshy.ai/openapi/v1"

    /** Runs the whole job. Call from a background dispatcher. [onProgress] gets (status, percent). */
    suspend fun generate(imageDataUris: List<String>, onProgress: (String, Int) -> Unit): Result {
        require(imageDataUris.isNotEmpty()) { "Take at least one photo" }
        val endpoint = if (imageDataUris.size == 1) "image-to-3d" else "multi-image-to-3d"

        val body = JSONObject().apply {
            if (imageDataUris.size == 1) put("image_url", imageDataUris[0])
            else put("image_urls", JSONArray(imageDataUris))
            put("ai_model", "latest")
            put("should_texture", false) // printing needs shape only; skipping texture is cheaper and faster
        }
        onProgress("Uploading photo${if (imageDataUris.size > 1) "s" else ""}…", 0)
        val created = request("POST", "$base/$endpoint", body.toString())
        val taskId = created.optString("result").ifEmpty { throw IOException("Meshy did not return a task id: $created") }

        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (true) {
            if (System.currentTimeMillis() > deadline) throw IOException("Meshy took longer than 15 minutes; try again later")
            val task = request("GET", "$base/$endpoint/$taskId", null)
            val status = task.optString("status")
            val pct = task.optInt("progress", 0)
            when (status) {
                "SUCCEEDED" -> {
                    val urls = task.optJSONObject("model_urls") ?: throw IOException("Meshy returned no model files")
                    // OBJ is preferred: it keeps Meshy's Y-up convention, which the app converts to Z-up for printing.
                    val (url, name) = when {
                        urls.optString("obj").isNotEmpty() -> urls.getString("obj") to "model.obj"
                        urls.optString("stl").isNotEmpty() -> urls.getString("stl") to "model.stl"
                        else -> throw IOException("Meshy returned no OBJ or STL file (got: ${urls.keys().asSequence().toList()})")
                    }
                    onProgress("Downloading model…", 100)
                    return Result(download(url), name)
                }
                "FAILED", "CANCELED", "EXPIRED" -> {
                    val msg = task.optJSONObject("task_error")?.optString("message").orEmpty()
                    throw IOException("Meshy could not build the model${if (msg.isNotEmpty()) ": $msg" else ""}")
                }
                else -> onProgress(if (status == "PENDING") "Waiting in Meshy's queue…" else "Building 3D model…", pct)
            }
            delay(4000)
        }
    }

    private fun request(method: String, url: String, json: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Accept", "application/json")
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty().ifEmpty { text.take(300) }
                throw IOException(
                    when (code) {
                        401 -> "Meshy rejected the API key. Check it in Settings."
                        402 -> "Your Meshy account is out of credits."
                        429 -> "Meshy is rate-limiting requests; wait a minute and try again."
                        else -> "Meshy error $code: $msg"
                    }
                )
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            if (c.responseCode !in 200..299) throw IOException("Model download failed (HTTP ${c.responseCode})")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
