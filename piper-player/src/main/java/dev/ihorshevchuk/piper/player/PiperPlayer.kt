package dev.ihorshevchuk.piper.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dev.ihorshevchuk.piper.engine.PiperEngine
import dev.ihorshevchuk.piper.utils.SentenceSplitter
import dev.ihorshevchuk.piper.utils.SpeedCurve
import dev.ihorshevchuk.piper.utils.SynthesisPlanner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming playback for [PiperEngine], the Android twin of PiperPlayer
 * (piper-player, Swift).
 *
 * play()/playSsml() run on a dedicated worker thread with sentence-queue
 * semantics: the input is split into sentences, each one is synthesized in
 * turn, and [onMarker] fires with the start time (ms) of every sentence
 * before its audio streams. stop() cancels between sentences. PCM is
 * float32 from the engine, converted to 16-bit and streamed through
 * AudioTrack.
 *
 * synthesizeToFile()/synthesizeSsmlToFile() mirror the Swift player's
 * to-file helpers: they synthesize the whole input to a WAV file and return
 * its path (null when synthesis produced nothing).
 */
class PiperPlayer {
    private val stopped = AtomicBoolean(false)
    private val worker = AtomicReference<ExecutorService?>(null)

    @Volatile
    private var engine: PiperEngine? = null

    @Volatile
    private var lastSampleRate: Int = 0

    fun play(
        engine: PiperEngine,
        text: String,
        rate: Float = 1.0f,
        speakerId: Int = 0,
        onMarker: (positionMs: Long) -> Unit = {}
    ) {
        launch(engine) { done ->
            val synthOptions = engine.defaultSynthesizeOptions()
                .copy(
                    lengthScale = SpeedCurve.lengthScaleForRate(rate),
                    speakerId = speakerId
                )
            var samplesWritten = 0L
            streamToTrack { onSamples ->
                for (sentence in SentenceSplitter.split(text)) {
                    if (stopped.get() || done()) break
                    onMarker(if (lastSampleRate > 0) samplesWritten * 1000L / lastSampleRate else 0L)
                    engine.synthesize(
                        sentence,
                        synthOptions,
                        onSamples = { s ->
                            samplesWritten += s.size
                            onSamples(s)
                        }
                    )
                }
            }
        }
    }

    /**
     * Streaming SSML playback (port of PiperPlayer.play(ssml:)). Each
     * fragment's prosody rate applies per sentence through
     * [SpeedCurve.lengthScaleForRate], exactly like
     * [PiperEngine.synthesizeSsml]; [onMarker] fires per sentence.
     */
    fun playSsml(
        engine: PiperEngine,
        ssml: String,
        speakerId: Int = 0,
        onMarker: (positionMs: Long) -> Unit = {}
    ) {
        launch(engine) { done ->
            val base = engine.defaultSynthesizeOptions()
            var samplesWritten = 0L
            streamToTrack { onSamples ->
                for (sentence in SynthesisPlanner.planSsml(ssml)) {
                    if (stopped.get() || done()) break
                    onMarker(if (lastSampleRate > 0) samplesWritten * 1000L / lastSampleRate else 0L)
                    val opts = base.copy(
                        speakerId = speakerId,
                        lengthScale = SpeedCurve.lengthScaleForRate(sentence.rate)
                    )
                    engine.synthesize(
                        sentence.text,
                        opts,
                        onSamples = { s ->
                            samplesWritten += s.size
                            onSamples(s)
                        }
                    )
                }
            }
        }
    }

    /**
     * Synthesizes [text] to a WAV file (port of PiperPlayer.synthesizeToFile).
     * Returns the file path, or null when synthesis produced no audio.
     */
    fun synthesizeToFile(
        engine: PiperEngine,
        text: String,
        rate: Float = 1.0f,
        speakerId: Int = 0,
        output: File? = null
    ): String? {
        val file = output ?: File.createTempFile("piper-player", ".wav")
        val options = engine.defaultSynthesizeOptions()
            .copy(
                lengthScale = SpeedCurve.lengthScaleForRate(rate),
                speakerId = speakerId
            )
        engine.synthesizeToFile(text, file.absolutePath, options)
        return file.takeIf { it.exists() && it.length() > 0 }?.absolutePath
    }

    /**
     * Synthesizes [ssml] to a WAV file (port of
     * PiperPlayer.synthesizeSSMLToFile). Returns the file path, or null when
     * synthesis produced no audio.
     */
    fun synthesizeSsmlToFile(
        engine: PiperEngine,
        ssml: String,
        speakerId: Int = 0,
        output: File? = null
    ): String? {
        val file = output ?: File.createTempFile("piper-player", ".wav")
        engine.synthesizeSsmlToFile(ssml, speakerId, file.absolutePath)
        return file.takeIf { it.exists() && it.length() > 0 }?.absolutePath
    }

    fun stop() {
        stopped.set(true)
        engine?.cancel()
        worker.getAndSet(null)?.shutdownNow()
    }

    private fun launch(engine: PiperEngine, block: (() -> Boolean) -> Unit) {
        stop()
        stopped.set(false)
        this.engine = engine
        lastSampleRate = 0
        val exec: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "piper-player") }
        worker.set(exec)
        // done() is true once stop() shuts this worker down.
        exec.execute { block { worker.get() !== exec } }
    }

    /**
     * Streams float32 chunks from [feed] through a lazily created AudioTrack,
     * converting to 16-bit PCM. Tracks the latest sample rate for markers.
     */
    private fun streamToTrack(feed: ((FloatArray) -> Unit) -> Unit) {
        var track: AudioTrack? = null
        try {
            feed { samples ->
                if (stopped.get()) return@feed
                val sr = engine?.currentSampleRate?.get() ?: 0
                if (sr > 0) lastSampleRate = sr
                var t = track
                if (t == null && lastSampleRate > 0) {
                    t = createTrack(lastSampleRate)
                    track = t
                    t.play()
                }
                if (t != null) {
                    val pcm = ShortArray(samples.size) { i ->
                        (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    t.write(pcm, 0, pcm.size)
                }
            }
            track?.stop()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!stopped.get()) Log.e(TAG, "playback failed", e)
        } finally {
            try {
                track?.release()
            } catch (_: Exception) {
            }
            track = null
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack(
            attributes,
            format,
            minBuffer * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    companion object {
        private const val TAG = "PiperPlayer"
    }
}
