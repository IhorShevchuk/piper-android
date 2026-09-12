package dev.ihorshevchuk.piper.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dev.ihorshevchuk.piper.engine.PiperEngine
import dev.ihorshevchuk.piper.utils.SentenceSplitter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming playback for [PiperEngine], the Android twin of PiperPlayer
 * (piper-player, Swift).
 *
 * play() runs on a dedicated worker thread with sentence-queue semantics: the
 * text is split into sentences, each one is synthesized in turn, and
 * [onMarker] fires with the start time (ms) of every sentence. stop() cancels
 * between sentences. PCM is float32 from the engine, converted to 16-bit and
 * streamed through AudioTrack.
 */
class PiperPlayer {
    private val stopped = AtomicBoolean(false)
    private val worker = AtomicReference<ExecutorService?>(null)

    @Volatile
    private var engine: PiperEngine? = null

    fun play(
        engine: PiperEngine,
        text: String,
        rate: Float = 1.0f,
        speakerId: Int = 0,
        onMarker: (positionMs: Long) -> Unit = {}
    ) {
        stop()
        stopped.set(false)
        this.engine = engine
        val exec: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "piper-player") }
        worker.set(exec)
        exec.execute { runPlayback(engine, text, rate, speakerId, onMarker) }
    }

    fun stop() {
        stopped.set(true)
        engine?.cancel()
        worker.getAndSet(null)?.shutdownNow()
    }

    private fun runPlayback(
        engine: PiperEngine,
        text: String,
        rate: Float,
        speakerId: Int,
        onMarker: (positionMs: Long) -> Unit
    ) {
        var track: AudioTrack? = null
        try {
            val synthOptions = engine.defaultSynthesizeOptions()
                .copy(
                    lengthScale = SpeedCurve.lengthScaleForRate(rate),
                    speakerId = speakerId
                )
            var samplesWritten = 0L
            for (sentence in SentenceSplitter.split(text)) {
                if (stopped.get()) break
                val sampleRate = engine.currentSampleRate.get()
                onMarker(if (sampleRate > 0) samplesWritten * 1000L / sampleRate else 0L)
                engine.synthesize(
                    sentence,
                    synthOptions,
                    onSamples = { samples ->
                        if (stopped.get()) return@synthesize
                        val sr = engine.currentSampleRate.get()
                        var t = track
                        if (t == null) {
                            t = createTrack(sr)
                            track = t
                            t.play()
                        }
                        val pcm = ShortArray(samples.size) { i ->
                            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        t.write(pcm, 0, pcm.size)
                        samplesWritten += samples.size
                    }
                )
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
