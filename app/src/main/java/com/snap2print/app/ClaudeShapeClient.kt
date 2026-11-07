package com.snap2print.app

import com.snap2print.app.core.Mesh
import com.snap2print.app.core.Shapes
import com.snap2print.app.core.SizeRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks Claude (Anthropic Messages API, with vision) to look at the photos and rebuild the object
 * from simple solid shapes. The app then turns those shapes into a printable mesh on the phone.
 *
 * The API key is typed in by the user in Settings and stored only on the phone. It is never in the code.
 */
class ClaudeShapeClient(private val apiKey: String, private val model: String) {

    data class Result(val mesh: Mesh, val objectName: String, val notes: String, val partCount: Int)

    fun generate(imageDataUris: List<String>, size: SizeRequest, onProgress: (String) -> Unit): Result {
        require(imageDataUris.isNotEmpty()) { "Take at least one photo" }

        val content = JSONArray()
        for (uri in imageDataUris) {
            val comma = uri.indexOf(',')
            val mediaType = uri.substringAfter("data:").substringBefore(';').ifEmpty { "image/jpeg" }
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", uri.substring(comma + 1))
                })
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", userPrompt(imageDataUris.size, size))
        })

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16000)
            put("system", SYSTEM_PROMPT)
            put("tools", JSONArray().put(TOOL))
            // "auto": newer models don't accept a forced tool choice; the system prompt asks for the tool instead.
            put("tool_choice", JSONObject().put("type", "auto"))
        }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))

        onProgress("Claude is studying the photo${if (imageDataUris.size > 1) "s" else ""}…")
        var input: JSONObject? = null
        for (attempt in 1..2) {
            body.put("messages", messages)
            val response = post(body.toString())
            val blocks = response.optJSONArray("content") ?: throw IOException("Claude returned no content")
            input = findModel(blocks)
            if (input != null) break
            if (response.optString("stop_reason") == "max_tokens") throw IOException("Claude's answer was cut off; try a simpler photo")
            if (attempt == 2) throw IOException("Claude did not return a model description. Try again or use a clearer photo.")
            // Claude answered in words only: keep its reply and ask once more for the tool call.
            onProgress("Asking Claude for the 3D shapes…")
            messages.put(JSONObject().put("role", "assistant").put("content", blocks))
            messages.put(JSONObject().put("role", "user").put("content",
                "Now call the build_model tool with the complete model. Don't answer in text."))
        }
        val spec = input ?: throw IOException("Claude did not return a model description")

        onProgress("Building the 3D shapes…")
        val parts = spec.optJSONArray("parts") ?: throw IOException("Claude returned no shapes")
        val built = ArrayList<FloatArray>()
        val problems = ArrayList<String>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            try {
                built.add(buildPart(p))
            } catch (e: Exception) {
                problems.add("${p.optString("label", p.optString("type"))}: ${e.message}")
            }
        }
        if (built.isEmpty()) throw IOException("None of Claude's shapes could be built. ${problems.joinToString("; ")}")

        val notes = buildString {
            append(spec.optString("notes"))
            if (problems.isNotEmpty()) append("\nSkipped ${problems.size} invalid shape(s).")
        }.trim()
        return Result(Shapes.combine(built), spec.optString("object_name", "object"), notes, built.size)
    }

    /** The build_model tool call, or else a JSON object with "parts" written in Claude's text. */
    private fun findModel(blocks: JSONArray): JSONObject? {
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "tool_use" && b.optString("name") == "build_model") return b.getJSONObject("input")
        }
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") != "text") continue
            val text = b.optString("text")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) continue
            val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            if (json?.optJSONArray("parts") != null) return json
        }
        return null
    }

    // ---------- Shapes from JSON ----------

    private fun buildPart(p: JSONObject): FloatArray {
        val shape = when (val type = p.optString("type")) {
            "box" -> {
                val s = vec(p, "size", 3)
                Shapes.box(pos(s[0]), pos(s[1]), pos(s[2]))
            }
            "cylinder" -> Shapes.cylinder(pos(p.getDouble("radius")), pos(p.getDouble("height")))
            "cone" -> Shapes.cone(
                p.getDouble("radius_bottom").coerceAtLeast(0.0),
                p.getDouble("radius_top").coerceAtLeast(0.0),
                pos(p.getDouble("height"))
            )
            "tube" -> Shapes.tube(pos(p.getDouble("outer_radius")), p.getDouble("inner_radius"), pos(p.getDouble("height")))
            "sphere" -> Shapes.sphere(pos(p.getDouble("radius")))
            "revolve" -> Shapes.revolve(points(p, "profile"))
            "extrude" -> Shapes.extrude(points(p, "outline"), pos(p.getDouble("height")))
            else -> throw IllegalArgumentException("unknown shape type '$type'")
        }
        val c = if (p.has("center")) vec(p, "center", 3) else doubleArrayOf(0.0, 0.0, 0.0)
        val r = if (p.has("rotation")) vec(p, "rotation", 3) else doubleArrayOf(0.0, 0.0, 0.0)
        return Shapes.place(shape, c[0], c[1], c[2], r[0], r[1], r[2])
    }

    private fun pos(v: Double): Double {
        if (v.isNaN() || v <= 0) throw IllegalArgumentException("sizes must be positive")
        return v
    }

    private fun vec(p: JSONObject, key: String, n: Int): DoubleArray {
        val a = p.getJSONArray(key)
        if (a.length() < n) throw IllegalArgumentException("$key needs $n numbers")
        return DoubleArray(n) { a.getDouble(it) }
    }

    private fun points(p: JSONObject, key: String): Array<DoubleArray> {
        val a = p.getJSONArray(key)
        return Array(a.length()) { i ->
            val pt = a.getJSONArray(i)
            doubleArrayOf(pt.getDouble(0), pt.getDouble(1))
        }
    }

    // ---------- HTTP ----------

    private fun post(json: String): JSONObject {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 300_000
            c.doOutput = true
            c.setRequestProperty("x-api-key", apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
                throw IOException(
                    when (code) {
                        401 -> "Claude rejected the API key. Check it in Settings."
                        403 -> "This API key isn't allowed to use that model. $msg"
                        404 -> "Unknown Claude model '$model'. Check the model name in Settings."
                        429 -> "Claude API rate limit or spending limit reached. Wait a moment and try again."
                        529, 503 -> "Claude is overloaded right now. Try again in a minute."
                        else -> "Claude API error $code: ${msg.ifEmpty { text.take(300) }}"
                    }
                )
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        private val SYSTEM_PROMPT = """
            You turn photos of a real object into a 3D-printable model built from simple solid shapes.
            Coordinates are millimetres. Z points up. The build plate is the plane z = 0 and the model
            sits on it, centred near x = 0, y = 0. Rules:
            - Model only the main object in the photo, never the background, hands or the surface it stands on.
            - Estimate real proportions carefully from the photo(s). If several photos are given, they show
              the same object from different sides; combine them.
            - Make the result one connected solid: every part must overlap or touch another part by at least 0.5 mm.
              Overlapping parts are merged when printing, so it's fine for shapes to intersect.
            - There is no subtraction. Make holes and hollows with the shapes that have them built in:
              'tube' for round holes, rings, bushings and pipes; 'revolve' for round objects with recesses (cups, caps,
              knobs, pulleys, bottles); 'extrude' for flat parts with a custom outline (brackets, plates, clips, letters).
            - Keep walls at least 1.2 mm thick and avoid thin floating details; it must survive printing in PLA.
            - Prefer few accurate shapes (typically 1–30) over many tiny ones. Use 'revolve' and 'extrude' generously:
              they capture most real shapes far better than stacks of boxes.
            - Put the object in its most stable printing orientation, flat side down.
            Always answer by calling the build_model tool.
        """.trimIndent()

        private fun userPrompt(photos: Int, size: SizeRequest): String =
            "Here ${if (photos == 1) "is a photo" else "are $photos photos"} of the object. " +
                "Build it so the final print is ${size.describe()} (the app will scale your model exactly to that, " +
                "so get the proportions right). In 'notes', say briefly in plain language what you simplified or were unsure about."

        private fun num() = JSONObject().put("type", "number")
        private fun arr(items: JSONObject, n: Int? = null) = JSONObject().put("type", "array").put("items", items).apply {
            if (n != null) put("minItems", n).put("maxItems", n)
        }

        private val TOOL: JSONObject = JSONObject().apply {
            put("name", "build_model")
            put("description", "Create the printable model as a list of simple solid shapes (millimetres, Z up, bed at z=0).")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("object_name").put("parts"))
                put("properties", JSONObject().apply {
                    put("object_name", JSONObject().put("type", "string").put("description", "Short name, e.g. 'machine knob'"))
                    put("notes", JSONObject().put("type", "string").put("description", "What was simplified or uncertain, 1–3 sentences"))
                    put("parts", arr(JSONObject().apply {
                        put("type", "object")
                        put("required", JSONArray().put("type").put("center"))
                        put("properties", JSONObject().apply {
                            put("type", JSONObject().put("type", "string").put("enum",
                                JSONArray().put("box").put("cylinder").put("cone").put("tube").put("sphere").put("revolve").put("extrude")))
                            put("label", JSONObject().put("type", "string"))
                            put("center", arr(num(), 3).put("description", "Where the shape's centre goes [x,y,z] in mm"))
                            put("rotation", arr(num(), 3).put("description", "Degrees around X, then Y, then Z. Default [0,0,0]"))
                            put("size", arr(num(), 3).put("description", "box: [x,y,z] lengths"))
                            put("radius", num().put("description", "cylinder, sphere"))
                            put("height", num().put("description", "cylinder, cone, tube, extrude: length along the shape's own Z, centred on 'center'"))
                            put("radius_bottom", num().put("description", "cone/frustum radius at the bottom (0 for a point)"))
                            put("radius_top", num().put("description", "cone/frustum radius at the top (0 for a point)"))
                            put("outer_radius", num().put("description", "tube"))
                            put("inner_radius", num().put("description", "tube hole radius"))
                            put("profile", arr(arr(num(), 2)).put("description",
                                "revolve: closed outline of [radius, z] points (radius >= 0) turned around the shape's Z axis; z is relative to 'center'"))
                            put("outline", arr(arr(num(), 2)).put("description",
                                "extrude: closed outline of [x, y] points (no self-crossings), relative to 'center'"))
                        })
                    }))
                })
            })
        }
    }
}
