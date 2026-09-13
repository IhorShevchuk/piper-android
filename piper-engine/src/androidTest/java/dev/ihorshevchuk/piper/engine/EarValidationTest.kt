package dev.ihorshevchuk.piper.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ihorshevchuk.piper.player.PiperPlayer
import dev.ihorshevchuk.piper.utils.SpeedCurve
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * Ear-validation harness for INTEGRATION_TESTING.md.
 *
 * Automated tests cannot hear, so this test writes one WAV per checklist
 * item into the test app's external files dir (pullable without root), and
 * stress-tests rapid play/stop/play through [PiperPlayer] + AudioTrack.
 *
 *   ./gradlew :piper-engine:connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data
 *
 * Then listen on the M4:
 *
 *   adb pull /sdcard/Android/data/dev.ihorshevchuk.piper.engine.test/files/ear-validation .
 *
 * Checklist (listen in order):
 *  1. ear-01-plain-en.wav      - plain English paragraph, natural, no dropouts.
 *  2. ear-02-ssml-slow.wav     - SSML rate="50%", audibly slower.
 *  3. ear-03-ssml-fast.wav     - SSML rate="200%", audibly faster.
 *  4. ear-04-long-text.wav      - 10+ sentences, no mid-utterance dropout.
 *  5. ear-05-ptbr-cadu.wav     - PT-BR Cadu at 100%, sibilants intact.
 */
@RunWith(AndroidJUnit4::class)
class EarValidationTest {

    companion object {
        private const val TAG = "EarValidation"
        private const val MODEL_BASE =
            "https://huggingface.co/IhorShevchuk/piper1-voices-fp16-quantized/resolve/main"

        private const val EN_DIR = "en/en_US/lessac/medium"
        private const val EN_MODEL = "en_US-lessac-medium.onnx"

        private const val PT_DIR = "pt/pt_BR/cadu/medium"
        private const val PT_MODEL = "pt_BR-cadu-medium.onnx"

        private lateinit var espeakDataPath: String
        private lateinit var outDir: File
        private lateinit var enEngine: PiperEngine
        private lateinit var ptEngine: PiperEngine

        @JvmStatic
        @BeforeClass
        fun setUpOnce() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val args = InstrumentationRegistry.getArguments()
            espeakDataPath = args.getString("espeakDataPath")
                ?: throw AssertionError(
                    "Missing instrumentation argument 'espeakDataPath'. " +
                        "See INTEGRATION_TESTING.md."
                )
            require(File(espeakDataPath).isDirectory) {
                "espeak-ng data not found at $espeakDataPath"
            }

            enEngine = createEngine(EN_DIR, EN_MODEL)
            ptEngine = createEngine(PT_DIR, PT_MODEL)

            outDir = File(
                context.getExternalFilesDir(null) ?: context.cacheDir,
                "ear-validation"
            ).apply { mkdirs() }
            Log.i(TAG, "Ear WAVs will be written to ${outDir.absolutePath}")
        }

        private fun createEngine(modelDir: String, modelName: String): PiperEngine {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "ear-validation-voice-$modelName").apply { mkdirs() }
            val modelFile = File(dir, modelName)
            val configFile = File(dir, "$modelName.json")
            if (!modelFile.isFile) download("$MODEL_BASE/$modelDir/$modelName", modelFile)
            if (!configFile.isFile) download("$MODEL_BASE/$modelDir/$modelName.json", configFile)
            return PiperEngine(
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
    fun generateEarValidationWavs() {
        val files = mapOf(
            "ear-01-plain-en.wav" to {
                enEngine.synthesizeToFile(
                    "Good morning. The weather is clear and the birds are singing. " +
                        "Today is a good day to test the speech engine on a real device.",
                    out("ear-01-plain-en.wav")
                )
            },
            "ear-02-ssml-slow.wav" to {
                enEngine.synthesizeSsmlToFile(
                    "<speak><prosody rate=\"50%\">" +
                        "This sentence is spoken slowly, at half speed." +
                        "</prosody></speak>",
                    0,
                    out("ear-02-ssml-slow.wav")
                )
            },
            "ear-03-ssml-fast.wav" to {
                enEngine.synthesizeSsmlToFile(
                    "<speak><prosody rate=\"200%\">" +
                        "This sentence is spoken quickly, at double speed." +
                        "</prosody></speak>",
                    0,
                    out("ear-03-ssml-fast.wav")
                )
            },
            "ear-04-long-text.wav" to {
                enEngine.synthesizeToFile(
                    Texts.LONG_PARAGRAPH,
                    out("ear-04-long-text.wav")
                )
            },
            "ear-05-ptbr-cadu.wav" to {
                // PT-BR sibilant guard: at 100% the length_scale must stay >= 0.45.
                val lengthScale = SpeedCurve.lengthScaleForRate(1.0f)
                assertTrue(
                    "PT-BR 100% must keep length_scale >= 0.45, got $lengthScale",
                    lengthScale >= 0.45f
                )
                ptEngine.synthesizeToFile(
                    "A chuva passa depressa na praça central. Os pássaros cantam nas casas " +
                        "próximas. Susana assa pães doces às seis da manhã. O sol nasce e as " +
                        "flores desabrocham. Crianças brincam e sorriem sem pressa.",
                    out("ear-05-ptbr-cadu.wav")
                )
            }
        )

        for ((name, generate) in files) {
            generate()
            val f = File(outDir, name)
            assertTrue("$name: missing or trivially small (${f.length()} bytes)", f.isFile && f.length() > 44 + 1000)
            Log.i(TAG, "Wrote $name (${f.length()} bytes)")
        }
        Log.i(
            TAG,
            "Pull with: adb pull " +
                "/sdcard/Android/data/${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/files/ear-validation ."
        )
    }

    @Test
    fun rapidPlayStopPlayDoesNotCrashOrStall() {
        val player = PiperPlayer()
        repeat(5) {
            player.play(enEngine, Texts.LONG_PARAGRAPH)
            Thread.sleep(250)
            player.stop()
        }
        // The engine must still work after the rapid cycles: full synthesis
        // must produce audio again, proving nothing got stuck mid-utterance.
        val chunks = mutableListOf<FloatArray>()
        enEngine.synthesize("Recovery check after rapid stop.", onSamples = { chunks += it })
        assertTrue(
            "engine produced no audio after rapid play/stop cycles",
            chunks.sumOf { it.size } > 1000
        )
    }

    private fun out(name: String): String {
        val f = File(outDir, name)
        if (f.exists()) f.delete()
        return f.absolutePath
    }

    private object Texts {
        private const val LONG_PARAGRAPH =
            "The quick brown fox jumps over the lazy dog. " +
                "Pack my box with five dozen liquor jugs. " +
                "How vexingly quick daft zebras jump. " +
                "The five boxing wizards jump quickly. " +
                "We promptly judged antique ivory buckles for the next prize. " +
                "Amazingly few discotheques provide jukeboxes. " +
                "Heavy boxes perform quick waltzes and jigs. " +
                "Weave a circle round him thrice and close your eyes with holy dread. " +
                "Jackdaws love my big sphinx of quartz. " +
                "The jay, pig, fox, zebra and my wolves quack. " +
                "Blowzy night-frumps vex'd Jack Q. " +
                "A very bad quack might jinx zippy fowls."
    }
}
