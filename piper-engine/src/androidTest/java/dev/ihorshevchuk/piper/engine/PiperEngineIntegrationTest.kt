package dev.ihorshevchuk.piper.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ihorshevchuk.piper.utils.SpeechMarkerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * On-device integration tests for [PiperEngine] - the Android equivalent of
 * PiperIntegrationTests (piper-objc, Swift).
 *
 * These need the real native library (libpiper_jni.so + libonnxruntime.so),
 * a voice model, and the compiled espeak-ng data, so they only run via
 * `connectedAndroidTest` on the M4 with a physical device attached - never in
 * the sandbox. See INTEGRATION_TESTING.md at the repo root for the runbook.
 *
 * The voice model (~32 MB fp16) is downloaded once from the HF test repo;
 * the espeak-ng data dir is passed as an instrumentation argument:
 *
 *   ./gradlew :piper-engine:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data
 */
@RunWith(AndroidJUnit4::class)
class PiperEngineIntegrationTest {

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
                ?: throw AssertionError(
                    "Missing instrumentation argument 'espeakDataPath'. " +
                        "Push the compiled espeak-ng-data dir to the device " +
                        "(adb push <staged>/espeak-ng-data /data/local/tmp/espeak-ng-data) " +
                        "and pass -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data"
                )
            require(File(espeakDataPath).isDirectory) {
                "espeak-ng data not found at $espeakDataPath"
            }

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
    }

    @Test
    fun engineInitializesAndReportsVersion() {
        assertNotNull(engine)
        // Version is populated by the native library on first engine creation.
        assertTrue(PiperEngine.version().isNotEmpty())
    }

    @Test
    fun plainSynthesisStreamsSamples() {
        val chunks = mutableListOf<FloatArray>()
        engine.synthesize(
            "Hello from the integration test.",
            onSamples = { chunks += it }
        )

        val totalSamples = chunks.sumOf { it.size }
        assertTrue("expected audio samples, got $totalSamples", totalSamples > 1000)
        val sampleRate = engine.currentSampleRate.get()
        assertTrue("unexpected sample rate $sampleRate", sampleRate == 16000 || sampleRate == 22050)
    }

    @Test
    fun synthesisToWavFileProducesValidFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.cacheDir, "integration-test.wav")
        if (out.exists()) out.delete()

        engine.synthesizeToFile("Testing the wave file output.", out.absolutePath)

        assertTrue(out.isFile)
        val bytes = out.readBytes()
        assertTrue("wav too small: ${bytes.size}", bytes.size > 44 + 1000)
        assertEquals("RIFF", bytes.slice(0..3).toByteArray().toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.slice(8..11).toByteArray().toString(Charsets.US_ASCII))
        out.delete()
    }

    @Test
    fun ssmlSynthesisProducesAudioAndCumulativeMarkers() {
        val ssml = "<speak><prosody rate=\"50%\">Slow part here.</prosody>" +
            "<prosody rate=\"200%\">Fast part here.</prosody></speak>"
        val chunks = mutableListOf<FloatArray>()
        val sentenceOffsets = mutableListOf<Int>()

        engine.synthesizeSsml(
            ssml,
            speakerId = 0,
            onSamples = { chunks += it },
            onMarkers = { markers ->
                sentenceOffsets += markers
                    .filter { it.type == SpeechMarkerType.SENTENCE }
                    .map { it.byteOffset }
            }
        )

        val totalSamples = chunks.sumOf { it.size }
        assertTrue("expected SSML audio, got $totalSamples samples", totalSamples > 1000)
        assertEquals(
            "expected one sentence marker per SSML fragment",
            2,
            sentenceOffsets.size
        )
        assertEquals(0, sentenceOffsets[0])
        assertTrue(
            "second fragment marker must advance past the first: $sentenceOffsets",
            sentenceOffsets[1] > sentenceOffsets[0]
        )
    }

    @Test
    fun memoryUsageIsReportedOnDevice() {
        // /proc/self/status exists on Android; null would mean the reader broke.
        assertNotNull(engine.getMemoryUsage())
    }
}
