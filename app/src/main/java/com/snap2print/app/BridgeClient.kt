package com.snap2print.app

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sends the STL to the small "Snap2Print bridge" program on your PC (see bridge/ in the project).
 * Bambu printers only print sliced files, so the PC slices it with Bambu Studio and sends it to the printer.
 */
class BridgeClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    /** Returns the bridge's message, e.g. "Opened in Bambu Studio" or "Printing on P1S". */
    fun send(stl: File): String {
        val name = URLEncoder.encode(stl.name, "UTF-8")
        val c = URL("$base/print?name=$name").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 10 * 60_000 // automatic slicing can take a while
            c.doOutput = true
            c.setFixedLengthStreamingMode(stl.length())
            c.setRequestProperty("Content-Type", "model/stl")
            if (token.isNotBlank()) c.setRequestProperty("X-Snap2Print-Token", token)
            c.outputStream.use { out -> stl.inputStream().use { it.copyTo(out) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty().ifEmpty { text.take(300) }
            if (code !in 200..299) throw IOException(if (code == 401) "Bridge token is wrong. Check Settings." else "Bridge error $code: $msg")
            return msg.ifEmpty { "Sent" }
        } catch (e: java.net.ConnectException) {
            throw IOException("Can't reach the PC bridge at $base. Is it running, and is the phone on the same Wi-Fi?")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The PC bridge at $base did not answer in time.")
        } finally {
            c.disconnect()
        }
    }
}
