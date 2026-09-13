package dev.ihorshevchuk.piper.engine

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ihorshevchuk.piper.player.PiperPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On-device tests for [PiperPlayer] - the first automated coverage of the
 * piper-player module (previously only exercised through the
 * EarValidationTest stress run).
 *
 * Needs the real native stack, a voice model and espeak-ng data, so these
 * only run via `connectedAndroidTest` on a physical device - see
 * INTEGRATION_TESTING.md at the repo root for the runbook.
 */
@RunWith(AndroidJUnit4::class)
class PiperPlayerTest {

    companion object {
        private const val MODEL_BASE =
            "https://huggingface.co/IhorShevchuk/piper1-voices-fp16-quantized/resolve/main"
        private const val MODEL_DIR = "en/en_US/lessac/medium"
        private const val MODEL_NAME = "en_US-lessac-medium.onnx"

        private lateinit var engine: PiperEngine
        private val player = PiperPlayer()

        @JvmStatic
        @BeforeClass
        fun setUpOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "integration-test-voice").apply { mkdirs() }
            val modelFile = File(dir, MODEL_NAME)
            val configFile = File(dir, "$MODEL_NAME.json")
            if (!modelFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME", modelFile)
            if (!configFile.isFile) download("$MODEL_BASE/$MODEL_DIR/$MODEL_NAME.json", configFile)

            val espeakDataPath = InstrumentationRegistry.getArguments()
                .getString("espeakDataPath")
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

        private fun awaitMarkers(
            markers: MutableList<Long>,
            count: Int,
            timeoutMs: Long = 60_000L
        ): Boolean {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (markers.size < count && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100)
            }
            return markers.size >= count
        }
    }

    @Test
    fun stopWhenIdle_doesNotThrow() {
        // No playback running: stop() must be a safe no-op.
        player.stop()
        player.stop()
    }

    @Test
    fun playEmitsSentenceMarkers() {
        val markers = CopyOnWriteArrayList<Long>()
        try {
            player.play(
                engine,
                "First sentence. Second sentence. Third sentence.",
                onMarker = { markers += it }
            )
            assertTrue(
                "expected 3 sentence markers, got ${markers.size}",
                awaitMarkers(markers, 3)
            )
            assertEquals(0L, markers.first())
            assertTrue(
                "markers not monotonic: $markers",
                markers.zipWithNext().all { (a, b) -> b >= a }
            )
        } finally {
            player.stop()
        }
    }

    @Test
    fun stopDuringPlayback_thenPlayAgain_recovers() {
        // Regression test for the stop-during-startup crash (2adec86):
        // stop() racing playback startup must not kill the process, and the
        // player must stay usable afterwards.
        val markers = CopyOnWriteArrayList<Long>()
        player.play(engine, "A short sentence to interrupt.")
        player.stop() // immediate: races worker startup, must not throw
        Thread.sleep(500)
        try {
            player.play(
                engine,
                "Playback after an immediate stop.",
                onMarker = { markers += it }
            )
            assertTrue(
                "player did not recover after immediate stop",
                awaitMarkers(markers, 1)
            )
        } finally {
            player.stop()
        }
    }

    @Test
    fun synthesizeToFile_writesValidWav() {
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "player-test.wav"
        )
        val path = player.synthesizeToFile(
            engine,
            "Player file synthesis test.",
            output = out
        )
        assertTrue("expected a file path, got null", path != null)
        val file = File(path!!)
        assertTrue("WAV is too small: ${file.length()}", file.length() > 44)
        file.inputStream().use { stream ->
            val magic = ByteArray(4)
            stream.read(magic)
            assertEquals("RIFF", String(magic))
        }
    }
}
