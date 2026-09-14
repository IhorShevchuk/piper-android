package dev.ihorshevchuk.piper.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dev.ihorshevchuk.piper.engine.PiperEngine
import dev.ihorshevchuk.piper.engine.PiperSynthesizeOptions
import dev.ihorshevchuk.piper.utils.AudioChunkQueue
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
    private val synthProducer = AtomicReference<Thread?>(null)
    private val chunkQueue = AtomicReference<AudioChunkQueue?>(null)

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
            val planned = SentenceSplitter.split(text).map { it to synthOptions }
            playPlanned(engine, planned, onMarker, done)
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
            val planned = SynthesisPlanner.planSsml(ssml).map { fragment ->
                fragment.text to base.copy(
                    speakerId = speakerId,
                    lengthScale = SpeedCurve.lengthScaleForRate(fragment.rate)
                )
            }
            playPlanned(engine, planned, onMarker, done)
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
        // Unblock a producer waiting on a full queue, then interrupt it out
        // of any in-flight native call.
        chunkQueue.getAndSet(null)?.abort()
        synthProducer.getAndSet(null)?.interrupt()
    }

    /**
     * Streaming playback with sentence lookahead.
     *
     * A dedicated synthesizer thread (producer) walks [planned] sentence by
     * sentence, offering PCM chunks into a bounded queue; the worker thread
     * (consumer) drains the queue into the AudioTrack. The next sentence's
     * `piper_synthesize_start` (espeak + encoder + first decoder inference)
     * therefore overlaps the current sentence's audio instead of gating it,
     * which removes the audible gap at sentence boundaries on slow devices.
     *
     * [onMarker] fires with the start time (ms) of every sentence as its
     * first chunk is written to the track.
     */
    private fun playPlanned(
        engine: PiperEngine,
        planned: List<Pair<String, PiperSynthesizeOptions>>,
        onMarker: (positionMs: Long) -> Unit,
        done: () -> Boolean
    ) {
        val queue = AudioChunkQueue()
        chunkQueue.set(queue)
        // Created unstarted: the reference must be visible before the
        // thread's first synthProducer.get() check, or it would exit
        // immediately thinking it was superseded.
        val producer = Thread {
            val self = Thread.currentThread()
            try {
                for ((text, options) in planned) {
                    if (stopped.get() || done() || synthProducer.get() !== self) break
                    var firstChunk = true
                    engine.synthesize(
                        text,
                        options,
                        onSamples = { s ->
                            if (stopped.get() || done() || synthProducer.get() !== self) return@synthesize
                            if (queue.offer(AudioChunkQueue.Chunk(s, firstChunk))) {
                                firstChunk = false
                            } else {
                                return@synthesize
                            }
                        }
                    )
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!stopped.get()) Log.e(TAG, "playback failed", e)
            } finally {
                queue.finish()
                synthProducer.compareAndSet(self, null)
            }
        }
        producer.isDaemon = true
        producer.name = "piper-player-synth"
        synthProducer.set(producer)
        producer.start()
        try {
            var samplesWritten = 0L
            streamToTrack { onSamples ->
                while (true) {
                    if (stopped.get() || done()) break
                    val chunk = try {
                        queue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } ?: break
                    if (chunk.sentenceStart) {
                        onMarker(if (lastSampleRate > 0) samplesWritten * 1000L / lastSampleRate else 0L)
                    }
                    samplesWritten += chunk.samples.size
                    onSamples(chunk.samples)
                }
            }
        } finally {
            queue.abort()
            // The producer is a daemon; never hang the worker on it, but
            // give it a moment so a second play() does not compete with a
            // stale producer for the engine thread.
            try {
                producer.join(10_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            chunkQueue.compareAndSet(queue, null)
        }
    }

    private fun launch(engine: PiperEngine, block: (() -> Boolean) -> Unit) {
        stop()
        stopped.set(false)
        this.engine = engine
        lastSampleRate = 0
        val exec: ExecutorService =
            Executors.newSingleThreadExecutor { r -> Thread(r, "piper-player") }
        worker.set(exec)
        // done() is true once stop() shuts this worker down. A stop() racing
        // playback startup interrupts the engine wait inside
        // defaultSynthesizeOptions()/synthesize() and surfaces as
        // InterruptedException/PiperException: that is a clean, expected stop,
        // never a crash (parity with PiperPlayer.stop on iOS, which just
        // cancels the queue and lets the worker exit quietly).
        exec.execute {
            try {
                block { worker.get() !== exec }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!stopped.get()) Log.e(TAG, "playback failed", e)
            }
        }
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
