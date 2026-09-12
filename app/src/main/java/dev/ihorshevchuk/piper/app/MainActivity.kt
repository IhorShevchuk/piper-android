package dev.ihorshevchuk.piper.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.ihorshevchuk.piper.engine.PiperCreateOptions
import dev.ihorshevchuk.piper.engine.PiperEngine
import dev.ihorshevchuk.piper.player.PiperPlayer
import java.io.File

/**
 * Minimal sample app: type text, pick a rate, speak.
 *
 * The voice (.onnx + .onnx.json) is resolved from <files>/voices/ (use
 * scripts/download-voice.sh, then push the files there, or bundle them under
 * app/src/main/assets/voices/ - they are copied to filesDir on first run).
 * espeak-ng-data is likewise copied from assets to <files>/espeak-ng-data on
 * first run; see README "espeak-ng-data packaging".
 *
 * Plain Views on purpose - no Compose dependency for the scaffold.
 */
class MainActivity : Activity() {

    private val player = PiperPlayer()

    @Volatile
    private var engine: PiperEngine? = null

    private lateinit var input: EditText
    private lateinit var rateBar: SeekBar
    private lateinit var rateLabel: TextView
    private lateinit var status: TextView
    private lateinit var speakButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        input = EditText(this).apply {
            hint = "Text to speak"
            minLines = 3
            gravity = Gravity.TOP
        }
        rateLabel = TextView(this)
        rateBar = SeekBar(this).apply { max = 180; progress = 80 } // 20..200 -> 0.2x..2.0x
        speakButton = Button(this).apply { text = "Speak" }
        val stopButton = Button(this).apply { text = "Stop" }
        status = TextView(this).apply { text = "Ready" }

        fun refreshRateLabel() {
            rateLabel.text = "Rate: ${"%.2f".format(currentRate())}x"
        }
        rateBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = refreshRateLabel()
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        refreshRateLabel()

        speakButton.setOnClickListener { speak() }
        stopButton.setOnClickListener {
            player.stop()
            status.text = "Stopped"
        }

        root.addView(input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(rateLabel)
        root.addView(rateBar)
        root.addView(speakButton)
        root.addView(stopButton)
        root.addView(status)
        setContentView(root)
    }

    override fun onDestroy() {
        player.stop()
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        super.onDestroy()
    }

    private fun currentRate(): Float = (rateBar.progress + 20) / 100f

    private fun speak() {
        val text = input.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Enter some text first", Toast.LENGTH_SHORT).show()
            return
        }
        val rate = currentRate()
        status.text = "Loading voice…"
        speakButton.isEnabled = false
        Thread {
            try {
                val eng = getEngine()
                runOnUiThread { status.text = "Speaking…" }
                player.play(eng, text, rate) { ms ->
                    runOnUiThread { status.text = "Playing… ${ms}ms" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
                runOnUiThread { status.text = "Error: ${e.message}" }
            } finally {
                runOnUiThread { speakButton.isEnabled = true }
            }
        }.start()
    }

    @Synchronized
    private fun getEngine(): PiperEngine {
        engine?.let { return it }

        val voiceName = "en_US-lessac-medium"
        val voicesDir = File(filesDir, "voices").apply { mkdirs() }
        val modelFile = File(voicesDir, "$voiceName.onnx")
        if (!modelFile.isFile) copyAsset("voices/$voiceName.onnx", modelFile)
        val configFile = File(voicesDir, "$voiceName.onnx.json")
        if (!configFile.isFile) copyAsset("voices/$voiceName.onnx.json", configFile)

        val espeakDir = File(filesDir, "espeak-ng-data")
        if (!espeakDir.isDirectory) copyAssetDir("espeak-ng-data", espeakDir)

        // configPath null -> native uses modelPath + ".json", same as iOS.
        val options = PiperCreateOptions(modelPath = modelFile.absolutePath)
        return PiperEngine(options, filesDir).also {
            engine = it
            Log.i(TAG, "Piper native version: ${PiperEngine.version()}")
        }
    }

    private fun copyAsset(assetPath: String, dest: File) {
        assets.open(assetPath).use { input ->
            dest.parentFile?.mkdirs()
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        val entries = assets.list(assetDir) ?: return
        if (entries.isEmpty()) {
            // Leaf file (assets.list returns empty for files).
            copyAsset(assetDir, File(destDir.parentFile, destDir.name))
            return
        }
        destDir.mkdirs()
        for (entry in entries) {
            copyAssetDir("$assetDir/$entry", File(destDir, entry))
        }
    }

    companion object {
        private const val TAG = "PiperApp"
    }
}
