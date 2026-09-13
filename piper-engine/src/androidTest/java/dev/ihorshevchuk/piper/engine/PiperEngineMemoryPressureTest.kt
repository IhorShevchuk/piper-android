package dev.ihorshevchuk.piper.engine

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * On-device tests for the memory-pressure port (Piper.memoryThresholdBytes +
 * DispatchSourceMemoryPressure handling in piper-objc, Swift).
 *
 * Needs the real native library, a voice model and espeak-ng data - runs via
 * `connectedAndroidTest` on the M4 with a physical device attached, same
 * runbook as PiperEngineIntegrationTest (see INTEGRATION_TESTING.md):
 *
 *   ./gradlew :piper-engine:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data
 */
@RunWith(AndroidJUnit4::class)
class PiperEngineMemoryPressureTest {

    companion object {
        private const val MODEL_BASE =
            "https://huggingface.co/IhorShevchuk/piper1-voices-fp16-quantized/resolve/main"
        private const val MODEL_DIR = "en/en_US/lessac/medium"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"

        private lateinit var engine: PiperEngine

        @JvmStatic
        @BeforeClass
        fun setUpOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "integration-test-voice").apply { mkdirs() }
            val modelFile = File(dir, MODEL_NAME)
            val configFile = File(dir, "$MODEL_NAME.json")
            if (!modelFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME", modelFile)
            if (!configFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME.json", configFile)

            val args = InstrumentationRegistry.getArguments()
            val espeakDataPath = args.getString("espeakDataPath")
                ?: throw AssertionError("Missing instrumentation argument 'espeakDataPath'.")

            engine = PiperEngine(
                PiperCreateOptions(
                    modelPath = modelFile.absolutePath,
                    configPath = configFile.absolutePath,
                    espeakDataPath = espeakDataPath
                )
            )
        }

        private fun download(url: String, dest: File) {
            URL(url).openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            require(dest.length() > 0) { "Downloaded file is empty: $url" }
        }

        private fun synthesizeSampleCount(text: String): Int {
            val chunks = mutableListOf<FloatArray>()
            engine.synthesize(text, onSamples = { chunks += it })
            return chunks.sumOf { it.size }
        }

        /** onTrimMemory is async on the engine thread; wait for the recreation. */
        private fun awaitRecreations(before: Int, timeoutMs: Long = 15_000L) {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (engine.recreateCount == before && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100)
            }
        }
    }

    @Test
    fun thresholdExceeded_recreatesSynthesizerTransparently() {
        // Mirrors PiperIntegrationTests' `piper.memoryThresholdBytes = 1`:
        // any real process usage exceeds 1 byte, so every sentence recreates.
        engine.memoryThresholdBytes = 1L
        try {
            val before = engine.recreateCount
            val total = synthesizeSampleCount("Memory pressure threshold test.")
            assertTrue("expected audio samples, got $total", total > 1000)
            assertTrue(
                "expected a synthesizer recreation, count stayed at $before",
                engine.recreateCount > before
            )
        } finally {
            engine.memoryThresholdBytes = null
        }
    }

    @Test
    fun trimMemoryCritical_releasesAndRecoversOnNextSynthesis() {
        val before = engine.recreateCount
        engine.onTrimMemory(15) // TRIM_MEMORY_RUNNING_CRITICAL
        // Release alone must not recreate; the single-thread engine executor
        // guarantees the release lands before the synthesis below, which then
        // rebuilds the synthesizer lazily.
        assertEquals(before, engine.recreateCount)

        val total = synthesizeSampleCount("After critical memory trim.")
        assertTrue("expected audio samples, got $total", total > 1000)
        assertTrue(engine.recreateCount > before)
    }

    @Test
    fun trimMemoryLow_recreatesImmediately() {
        val before = engine.recreateCount
        engine.onTrimMemory(10) // TRIM_MEMORY_RUNNING_LOW
        awaitRecreations(before)
        assertTrue(
            "expected a synthesizer recreation, count stayed at $before",
            engine.recreateCount > before
        )

        val total = synthesizeSampleCount("After low memory trim.")
        assertTrue("expected audio samples, got $total", total > 1000)
    }

    @Test
    fun trimMemoryUnknownLevel_isIgnored() {
        val before = engine.recreateCount
        engine.onTrimMemory(0)
        engine.onTrimMemory(20) // TRIM_MEMORY_UI_HIDDEN
        awaitRecreations(before, timeoutMs = 2_000L)
        assertEquals(before, engine.recreateCount)
    }
}
