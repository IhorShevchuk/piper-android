package dev.ihorshevchuk.piper.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ihorshevchuk.piper.utils.PhonemeGroup
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device behavior tests for [PiperEngine] beyond the core integration
 * suite: cancellation, alignment callbacks, error paths, and the public
 * synthesizer-recreation API.
 *
 * Needs the real native stack, a voice model and espeak-ng data, so these
 * only run via `connectedAndroidTest` on a physical device - see
 * INTEGRATION_TESTING.md at the repo root for the runbook.
 */
@RunWith(AndroidJUnit4::class)
class PiperEngineBehaviorTest {

    companion object {
        private const val MODEL_BASE =
            "https://huggingface.co/IhorShevchuk/piper1-voices-fp16-quantized/resolve/main"
        private const val MODEL_DIR = "en/en_US/lessac/medium"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"

        private lateinit var engine: PiperEngine
        private lateinit var modelFile: File
        private lateinit var configFile: File
        private lateinit var espeakDataPath: String

        @JvmStatic
        @BeforeClass
        fun setUpOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "integration-test-voice").apply { mkdirs() }
            modelFile = File(dir, MODEL_NAME)
            configFile = File(dir, "$MODEL_NAME.json")
            if (!modelFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME", modelFile)
            if (!configFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME.json", configFile)

            espeakDataPath = InstrumentationRegistry.getArguments()
                .getString("espeakDataPath")
                ?: throw AssertionError("Missing instrumentation argument 'espeakDataPath'.")

            engine = newEngine()
        }

        private fun download(url: String, dest: File) {
            URL(url).openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            require(dest.length() > 0) { "Downloaded file is empty: $url" }
        }

        private fun newEngine(modelPath: String = modelFile.absolutePath): PiperEngine =
            PiperEngine(
                PiperCreateOptions(
                    modelPath = modelPath,
                    configPath = configFile.absolutePath,
                    espeakDataPath = espeakDataPath
                )
            )
    }

    @Test
    fun cancel_stopsSynthesisBetweenSentences() {
        val text = (1..30).joinToString(" ") { "Sentence number $it for cancellation testing." }
        val chunks = AtomicInteger(0)
        val firstChunk = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val worker = Thread {
            try {
                engine.synthesize(text, onSamples = {
                    chunks.incrementAndGet()
                    firstChunk.countDown()
                })
            } finally {
                finished.set(true)
            }
        }
        worker.start()
        assertTrue(
            "no audio arrived before cancel()",
            firstChunk.await(60, TimeUnit.SECONDS)
        )
        engine.cancel()
        worker.join(60_000)
        assertTrue("synthesize() did not return after cancel()", finished.get())
        assertTrue("expected some audio before cancel, got none", chunks.get() > 0)
    }

    @Test
    fun alignmentCallback_noGroupsForSingleOutputModel() {
        // The catalog voices are standard single-output VITS models: piper1-gpl
        // only fills chunk->alignments when the model graph has a second output
        // tensor (verified by parsing en_US-lessac-medium.onnx: its graph
        // outputs are ["output"]). So onAlignment never fires here, and word
        // markers fall back to the character-proportion path. This test pins
        // that contract: audio plus markers, zero alignment groups.
        val groups = CopyOnWriteArrayList<PhonemeGroup>()
        val markerBatches = AtomicInteger(0)
        val total = AtomicInteger(0)
        engine.synthesize(
            "Alignment fallback test.",
            onSamples = { total.addAndGet(it.size) },
            onAlignment = { groups += it },
            onMarkers = { markerBatches.incrementAndGet() }
        )
        assertTrue("expected audio samples, got ${total.get()}", total.get() > 1000)
        assertTrue(
            "expected no alignment groups from a single-output model, got ${groups.size}",
            groups.isEmpty()
        )
        assertTrue(
            "expected sentence markers via the fallback path",
            markerBatches.get() > 0
        )
    }

    @Test
    fun invalidModelPath_throwsPiperException() {
        try {
            newEngine(modelPath = "/nonexistent/voice.onnx")
            fail("expected PiperException for a missing model file")
        } catch (e: PiperException) {
            // expected
        }
    }

    @Test
    fun close_isIdempotent() {
        val disposable = newEngine()
        disposable.close()
        disposable.close() // must not throw
    }

    @Test
    fun recreateSynthesizer_keepsEngineUsable() {
        engine.recreateSynthesizer()
        val total = AtomicInteger(0)
        engine.synthesize(
            "After explicit recreation.",
            onSamples = { total.addAndGet(it.size) }
        )
        assertTrue("expected audio samples, got ${total.get()}", total.get() > 1000)
    }
}
