package com.snap2print.app

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.snap2print.app.core.Mesh
import com.snap2print.app.core.MeshFit
import com.snap2print.app.core.MeshIO
import com.snap2print.app.core.SizeParser
import com.snap2print.app.core.SizeRequest
import com.snap2print.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val BAMBU_HANDY_PACKAGE = "bbl.intl.bambulab.com"
        const val MAKERWORLD_URL = "https://makerworld.com"
    }

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val photos = mutableListOf<Uri>()
    private var pendingPhoto: Uri? = null
    private var size: SizeRequest? = null
    private var stl: File? = null
    private var savedToDownloads: String? = null
    private var busy = false

    private val engine get() = prefs.getString("engine", "claude") ?: "claude"

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) photos.add(uri)
        pendingPhoto = null
        refresh()
    }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val best = heard.firstOrNull { SizeParser.parse(it) != null } ?: heard.firstOrNull()
        if (best != null) b.sizeInput.setText(best)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPhoto.setOnClickListener { takePhoto() }
        b.btnClearPhotos.setOnClickListener { photos.clear(); refresh() }
        b.btnSpeak.setOnClickListener { listen() }
        b.sizeInput.doAfterTextChanged { size = SizeParser.parse(it?.toString()); refresh() }
        b.btnGenerate.setOnClickListener { generate() }
        b.btnHandy.setOnClickListener { printWithHandy() }
        b.btnShare.setOnClickListener { openInOtherApp() }
        b.btnSend.setOnClickListener { sendToBridge() }
        b.btnSettings.setOnClickListener { showSettings() }

        if (activeKey().isBlank()) showSettings()
        refresh()
    }

    private fun activeKey(): String =
        prefs.getString(if (engine == "meshy") "meshy_key" else "claude_key", "").orEmpty().trim()

    // ---------- Step 1: photos ----------

    private fun takePhoto() {
        if (photos.size >= 4) {
            toast("4 photos is the maximum. Tap Clear to start over.")
            return
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        pendingPhoto = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast("No camera app found on this phone")
        }
    }

    // ---------- Step 2: size ----------

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Speech recognition isn't available. Type the size instead.")
        }
    }

    // ---------- Step 3: generate ----------

    private fun generate() {
        val key = activeKey()
        val req = size
        when {
            key.isEmpty() -> { toast("Enter your ${if (engine == "meshy") "Meshy" else "Claude"} API key first"); showSettings(); return }
            photos.isEmpty() -> { toast("Take a photo of the object first"); return }
            req == null -> { toast("Say or type the size first, e.g. 8 cm tall"); return }
        }
        val useMeshy = engine == "meshy"
        val model = prefs.getString("claude_model", "").orEmpty().trim().ifEmpty { ClaudeShapeClient.DEFAULT_MODEL }

        busy = true
        stl = null
        savedToDownloads = null
        refresh()
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = !useMeshy
        b.progress.progress = 0

        lifecycleScope.launch {
            try {
                val (file, summary) = withContext(Dispatchers.IO) {
                    val images = photos.map { ImageUtil.toDataUri(contentResolver, it) }
                    var notes = ""
                    var mesh: Mesh
                    if (useMeshy) {
                        val result = MeshyClient(key).generate(images) { status, pct ->
                            runOnUiThread { b.status.text = status; b.progress.progress = pct }
                        }
                        mesh = MeshIO.read(result.bytes, result.fileName)
                        if (result.fileName.endsWith(".obj")) mesh = MeshFit.yUpToZUp(mesh)
                    } else {
                        val result = ClaudeShapeClient(key, model).generate(images, req!!) { status ->
                            runOnUiThread { b.status.text = status }
                        }
                        mesh = result.mesh
                        notes = "${result.objectName.replaceFirstChar { it.uppercase() }}, ${result.partCount} shapes. ${result.notes}".trim()
                    }
                    runOnUiThread { b.status.text = "Scaling to ${req!!.describe()}…" }
                    mesh = MeshFit.fit(mesh, req!!)

                    val dir = File(filesDir, "models").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val out = File(dir, "snap2print_$stamp.stl")
                    out.outputStream().buffered().use { MeshIO.writeBinaryStl(mesh, it) }
                    val s = mesh.size()
                    val dims = String.format(Locale.US, "Ready: %.1f × %.1f × %.1f mm (W × D × H)", s[0], s[1], s[2])
                    out to (if (notes.isNotEmpty()) "$dims\n$notes" else dims)
                }
                stl = file
                b.status.text = summary
                savedToDownloads = withContext(Dispatchers.IO) { runCatching { saveToDownloads(file) }.getOrNull() }
            } catch (e: Exception) {
                b.status.text = "⚠ ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                b.progress.visibility = View.GONE
                refresh()
            }
        }
    }

    /** Copies the STL to Downloads/Snap2Print so the browser (MakerWorld upload) and other apps can pick it. */
    private fun saveToDownloads(file: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "model/stl")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Snap2Print")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        return "Downloads/Snap2Print/${file.name}"
    }

    // ---------- Step 4: print ----------

    /**
     * Bambu Handy can't open STL files itself: it prints models from your MakerWorld account, which Bambu's
     * cloud slices for your printer. So: upload the STL privately on makerworld.com, then print it from Handy.
     */
    private fun printWithHandy() {
        val where = savedToDownloads ?: "the Downloads folder"
        MaterialAlertDialogBuilder(this)
            .setTitle("Print with Bambu Handy")
            .setMessage(
                "Bambu Handy can't open STL files directly. It prints models from your MakerWorld account, " +
                    "which Bambu's cloud slices for your printer.\n\n" +
                    "1. Tap “Open MakerWorld”, log in with your Bambu account, and choose Upload.\n" +
                    "2. Pick the STL from $where. Set visibility to Private.\n" +
                    "3. Open Bambu Handy → Me → My uploads → your model → Print.\n\n" +
                    "The first upload takes a few minutes; after that it's quick."
            )
            .setPositiveButton("Open MakerWorld") { _, _ -> openUrl(MAKERWORLD_URL) }
            .setNeutralButton("Open Bambu Handy") { _, _ -> openBambuHandy() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openBambuHandy() {
        val launch = packageManager.getLaunchIntentForPackage(BAMBU_HANDY_PACKAGE)
        if (launch != null) {
            startActivity(launch)
        } else {
            toast("Bambu Handy isn't installed")
            openUrl("market://details?id=$BAMBU_HANDY_PACKAGE")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            if (url.startsWith("market://")) openUrl("https://play.google.com/store/apps/details?id=$BAMBU_HANDY_PACKAGE")
            else toast("No app can open $url")
        }
    }

    /** For other printers: any installed app that accepts STL files (slicer apps, Creality Cloud, file managers, mail…). */
    private fun openInOtherApp() {
        val file = stl ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "model/stl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "model/stl")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, getString(R.string.share_stl))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(view))
        startActivity(chooser)
    }

    private fun sendToBridge() {
        val file = stl ?: return
        val url = prefs.getString("bridge_url", "").orEmpty()
        if (url.isBlank()) return
        busy = true
        refresh()
        b.status.text = "Sending to PC…"
        lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) {
                    BridgeClient(url, prefs.getString("bridge_token", "").orEmpty()).send(file)
                }
                b.status.text = "✓ $msg"
            } catch (e: Exception) {
                b.status.text = "⚠ ${e.message}"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    // ---------- Settings & UI state ----------

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val engineGroup = v.findViewById<RadioGroup>(R.id.engine)
        val claudeKey = v.findViewById<EditText>(R.id.claudeKey)
        val claudeModel = v.findViewById<EditText>(R.id.claudeModel)
        val meshyKey = v.findViewById<EditText>(R.id.meshyKey)
        val url = v.findViewById<EditText>(R.id.bridgeUrl)
        val token = v.findViewById<EditText>(R.id.bridgeToken)
        engineGroup.check(if (engine == "meshy") R.id.engineMeshy else R.id.engineClaude)
        claudeKey.setText(prefs.getString("claude_key", ""))
        claudeModel.setText(prefs.getString("claude_model", ClaudeShapeClient.DEFAULT_MODEL))
        meshyKey.setText(prefs.getString("meshy_key", ""))
        url.setText(prefs.getString("bridge_url", ""))
        token.setText(prefs.getString("bridge_token", ""))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit()
                    .putString("engine", if (engineGroup.checkedRadioButtonId == R.id.engineMeshy) "meshy" else "claude")
                    .putString("claude_key", claudeKey.text.toString().trim())
                    .putString("claude_model", claudeModel.text.toString().trim().ifEmpty { ClaudeShapeClient.DEFAULT_MODEL })
                    .putString("meshy_key", meshyKey.text.toString().trim())
                    .putString("bridge_url", url.text.toString().trim())
                    .putString("bridge_token", token.text.toString().trim())
                    .apply()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        b.photoCount.text = when (photos.size) {
            0 -> "No photos yet"
            1 -> "1 photo"
            else -> "${photos.size} photos"
        }
        if (photos.isEmpty()) b.photoPreview.setImageDrawable(null) else b.photoPreview.setImageURI(photos.last())
        b.sizeParsed.text = size?.let { "Size: ${it.describe()}" } ?: "Size: not set"
        b.btnGenerate.text = if (engine == "meshy") "Create STL (Meshy)" else "Create STL (Claude)"
        b.btnPhoto.isEnabled = !busy
        b.btnClearPhotos.isEnabled = !busy && photos.isNotEmpty()
        b.btnSpeak.isEnabled = !busy
        b.btnGenerate.isEnabled = !busy
        val ready = !busy && stl != null
        b.btnHandy.isEnabled = ready
        b.btnShare.isEnabled = ready
        b.btnSend.isEnabled = ready
        b.btnSend.visibility = if (prefs.getString("bridge_url", "").isNullOrBlank()) View.GONE else View.VISIBLE
        b.savedPath.text = savedToDownloads?.let { "Saved to $it" } ?: ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
