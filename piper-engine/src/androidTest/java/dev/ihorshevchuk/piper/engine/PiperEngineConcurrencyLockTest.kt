package dev.ihorshevchuk.piper.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device-only regression tests for the process-wide espeak-ng session lock
 * (piper_jni.cpp / native_synthesis_lock.h).
 *
 * espeak-ng keeps process-global state (voice tables, phoneme data), so
 * concurrent synthesis on separate PiperEngine instances used to crash the
 * process: SIGSEGV in InterpretPhoneme <- MakePhonemeList <-
 * TranslateClauseWithTerminator <- espeak_TextToPhonemesWithTerminator <-
 * piper_synthesize_start (reproduced on Linux with 4 threads 5/5 runs).
 *
 * These need the real native library (libpiper_jni.so + libonnxruntime.so),
 * a voice model, and the compiled espeak-ng data, so they only run via
 * `connectedAndroidTest` on a physical device - never in the sandbox.
 */
@RunWith(AndroidJUnit4::class)
class PiperEngineConcurrencyLockTest {

    companion object {
        private const val MODEL_BASE =
            "https://huggingface.co/IhorShevchuk/piper1-voices-fp16-quantized/resolve/main"
        private const val MODEL_DIR = "en/en_US/lessac/medium"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"

        private lateinit var modelFile: File
        private lateinit var configFile: File
        private lateinit var espeakDataPath: String

        @JvmStatic
        @BeforeClass
        fun setUpOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "concurrency-test-voice").apply { mkdirs() }
            modelFile = File(dir, MODEL_NAME)
            configFile = File(dir, "$MODEL_NAME.json")
            if (!modelFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME", modelFile)
            if (!configFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME.json", configFile)

            val args = InstrumentationRegistry.getArguments()
            espeakDataPath = args.getString("espeakDataPath")
                ?: throw AssertionError(
                    "Missing instrumentation argument 'espeakDataPath'. " +
                        "Push the compiled espeak-ng-data dir to the device " +
                        "(adb push <staged>/espeak-ng-data /data/local/tmp/espeak-ng-data)."
                )
            require(File(espeakDataPath).isDirectory) {
                "espeak-ng data not found at $espeakDataPath"
            }
        }

        private fun download(url: String, dest: File) {
            URL(url).openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            require(dest.length() > 0) { "Downloaded file is empty: $url" }
        }

        private fun newEngine(): PiperEngine = PiperEngine(
            PiperCreateOptions(
                modelPath = modelFile.absolutePath,
                configPath = configFile.absolutePath,
                espeakDataPath = espeakDataPath
            )
        )
    }

    /**
     * Two engines synthesizing at the same time must both complete with
     * audio. Without the process-wide session lock this crashed the process
     * intermittently; with it the sessions serialize and both finish.
     */
    @Test(timeout = 180000)
    fun concurrentEnginesBothComplete() {
        val engineA = newEngine()
        val engineB = newEngine()
        try {
            val pool = Executors.newFixedThreadPool(2)
            val startGate = CountDownLatch(1)
            val samplesA = AtomicInteger(0)
            val samplesB = AtomicInteger(0)
            val text = "The quick brown fox jumps over the lazy dog. " +
                "Pack my box with five dozen liquor jugs."
            val futureA = pool.submit {
                startGate.await()
                engineA.synthesize(text, onSamples = { samplesA.addAndGet(it.size) })
            }
            val futureB = pool.submit {
                startGate.await()
                engineB.synthesize(text, onSamples = { samplesB.addAndGet(it.size) })
            }
            startGate.countDown()
            futureA.get(150, TimeUnit.SECONDS)
            futureB.get(150, TimeUnit.SECONDS)
            pool.shutdown()
            assertTrue(
                "engine A produced ${samplesA.get()} samples",
                samplesA.get() > 1000
            )
            assertTrue(
                "engine B produced ${samplesB.get()} samples",
                samplesB.get() > 1000
            )
        } finally {
            engineA.close()
            engineB.close()
        }
    }

    /**
     * Cancelling engine A mid-utterance abandons its session before
     * PIPER_DONE. Engine B must still synthesize afterwards: the finally
     * block in synthesize() ends A's session and releases the lock.
     * Without it, B would block on the lock forever.
     */
    @Test(timeout = 180000)
    fun cancelledUtteranceReleasesLockForOtherEngine() {
        val engineA = newEngine()
        val engineB = newEngine()
        try {
            val longText =
                "This is a deliberately long sentence to keep synthesis running. "
                    .repeat(20)
            val worker = Thread {
                engineA.synthesize(longText, onSamples = { })
            }
            worker.start()
            // Let A get deep into its utterance, then cancel mid-stream.
            Thread.sleep(1500)
            engineA.cancel()
            worker.join(60000)

            val samplesB = AtomicInteger(0)
            engineB.synthesize(
                "Hello from engine B.",
                onSamples = { samplesB.addAndGet(it.size) }
            )
            assertTrue(
                "engine B produced ${samplesB.get()} samples after A's cancel",
                samplesB.get() > 1000
            )
        } finally {
            engineA.close()
            engineB.close()
        }
    }
}
