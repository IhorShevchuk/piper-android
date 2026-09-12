package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.SentenceSplitter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin port of Piper (piper-objc, Swift).
 *
 * Threading: libpiper is NOT thread-safe, so every native call is serialized
 * through a dedicated single-thread executor (the equivalent of the iOS serial
 * OperationQueue). Public methods block the calling thread while the native
 * work runs on the engine thread; call them off the UI thread.
 */
class PiperEngine(
    private val options: PiperCreateOptions,
    appFilesDir: File? = null
) : AutoCloseable {

    companion object {
        /** piper.h: PIPER_OK */
        const val PIPER_OK = 0
        /** piper.h: PIPER_DONE */
        const val PIPER_DONE = 1
        /** piper.h: generic error. A failed sentence is skipped, synthesis continues. */
        const val PIPER_ERR_GENERIC = -1
        /** Alias kept for parity with the C header name. */
        const val PIPER_ERR = PIPER_ERR_GENERIC

        @Volatile
        private var cachedNativeVersion: String? = null

        init {
            System.loadLibrary("piper_jni")
        }

        /**
         * Version string reported by the native library. Populated when the
         * first PiperEngine is created in this process; "uninitialized" before that,
         * because the native version() call needs no engine handle but the JNI
         * binding itself is an instance method (see piper_jni.cpp).
         */
        fun version(): String = cachedNativeVersion ?: "uninitialized"
    }

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "piper-engine") }
    private val cancelled = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)

    /** Sample rate (Hz) of the most recently synthesized chunk. Updated per chunk. */
    val currentSampleRate = AtomicInteger(22050)

    private val handle: Long

    init {
        val modelFile = File(options.modelPath)
        if (!modelFile.isFile) {
            throw PiperException("Voice model not found: ${options.modelPath}")
        }
        val espeakPath = resolveEspeakDataPath(options, appFilesDir)
        handle = runNative("piper_create_with_options") {
            nativeCreate(
                options.modelPath,
                options.configPath,
                espeakPath,
                options.dataDir,
                options.g2pwModelDir
            )
        }
        if (handle == 0L) {
            throw PiperException("piper_create_with_options failed for ${options.modelPath}")
        }
        cachedNativeVersion = runNative("piper_version") { nativeVersion() }
    }

    /**
     * Synthesizes [text] sentence by sentence.
     *
     * For each sentence [onSamples] receives raw float32 PCM chunks as they are
     * produced; [onAlignment] receives the chunk's phoneme codepoints and their
     * per-phoneme sample counts (parallel arrays, piper.h grouping applies).
     *
     * Resilience (Sawyer long-utterance fix, ported from Swift): if
     * piper_synthesize_start fails for a sentence with PIPER_ERR_GENERIC, that
     * sentence is skipped and synthesis continues with the next one instead of
     * aborting the whole utterance.
     */
    fun synthesize(
        text: String,
        synthOptions: PiperSynthesizeOptions? = null,
        onSamples: (FloatArray) -> Unit,
        onAlignment: (phonemes: IntArray, alignments: IntArray) -> Unit = { _, _ -> }
    ) {
        val opts = synthOptions ?: defaultSynthesizeOptions()
        cancelled.set(false)
        for (sentence in SentenceSplitter.split(text)) {
            if (cancelled.get()) break
            val rc = runNative("piper_synthesize_start") {
                nativeSynthesizeStart(
                    handle, sentence,
                    opts.speakerId, opts.lengthScale, opts.noiseScale, opts.noiseWScale
                )
            }
            if (rc == PIPER_ERR_GENERIC) continue // skip failed sentence, keep going
            if (rc != PIPER_OK) {
                throw PiperException(
                    "piper_synthesize_start failed (rc=$rc) for sentence: ${sentence.take(64)}"
                )
            }
            while (true) {
                if (cancelled.get()) break
                // null == PIPER_DONE (or a mid-sentence error: partial audio is kept)
                val chunk: FloatArray = runNative("piper_synthesize_next") {
                    nativeSynthesizeNext(handle)
                } ?: break
                currentSampleRate.set(runNative("piper_last_chunk_sample_rate") {
                    nativeLastChunkSampleRate(handle)
                })
                onSamples(chunk)
                val phonemes = runNative("piper_last_chunk_phonemes") {
                    nativeLastChunkPhonemes(handle)
                }
                val alignments = runNative("piper_last_chunk_alignments") {
                    nativeLastChunkAlignments(handle)
                }
                if (phonemes != null && alignments != null) {
                    onAlignment(phonemes, alignments)
                }
                val isLast = runNative("piper_last_chunk_is_last") {
                    nativeLastChunkIsLast(handle)
                }
                if (isLast) break
            }
        }
    }

    /**
     * Synthesizes [text] and writes it to [path] as a WAV file: 44-byte header,
     * IEEE float (format tag 3), mono, 32-bit, with unspecified data size -
     * the same layout the Swift side writes.
     */
    fun synthesizeToFile(
        text: String,
        path: String,
        synthOptions: PiperSynthesizeOptions? = null
    ) {
        val chunks = mutableListOf<FloatArray>()
        var sampleRate = currentSampleRate.get()
        synthesize(text, synthOptions, onSamples = { chunk ->
            chunks.add(chunk)
            sampleRate = currentSampleRate.get()
        })
        val out = File(path)
        out.parentFile?.mkdirs()
        writeWavFloatMono(out, chunks, sampleRate)
    }

    /** Reads the voice's default synthesis options from the native layer. */
    fun defaultSynthesizeOptions(): PiperSynthesizeOptions {
        val a = runNative("piper_default_synthesize_options") { nativeDefaultOptions(handle) }
        require(a.size >= 4) { "nativeDefaultOptions returned ${a.size} values, expected 4" }
        return PiperSynthesizeOptions(
            speakerId = a[0].toInt(),
            lengthScale = a[1],
            noiseScale = a[2],
            noiseWScale = a[3]
        )
    }

    /** Requests cancellation; the current synthesize() stops between sentences. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Destroys the native synthesizer. Idempotent. Queued behind any in-flight
     * native call on the engine thread, so it never races synthesis.
     */
    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        cancelled.set(true)
        try {
            executor.submit { nativeDestroy(handle) }.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Best effort: the process is tearing the engine down anyway.
        }
        executor.shutdown()
    }

    private fun <T> runNative(name: String, block: () -> T): T {
        if (closedFlag.get()) throw PiperException("PiperEngine is closed ($name)")
        try {
            return executor.submit(Callable(block)).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            throw if (cause is PiperException) cause
            else PiperException("Native call $name failed", cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PiperException("Native call $name interrupted", e)
        }
    }

    private fun resolveEspeakDataPath(
        options: PiperCreateOptions,
        appFilesDir: File?
    ): String? {
        options.espeakDataPath?.let {
            val dir = File(it)
            if (!dir.isDirectory) throw PiperException("espeak-ng data not found: $it")
            return dir.absolutePath
        }
        appFilesDir?.let {
            val candidate = File(it, "espeak-ng-data")
            if (candidate.isDirectory) return candidate.absolutePath
        }
        options.dataDir?.let {
            val candidate = File(it, "espeak-ng-data")
            if (candidate.isDirectory) return candidate.absolutePath
        }
        // null: let the native side fall back to auto-discovery / bundled data
        return null
    }

    private fun writeWavFloatMono(file: File, chunks: List<FloatArray>, sampleRate: Int) {
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(-1) // RIFF chunk size: unspecified (streamed), like Swift
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // fmt chunk size
            header.putShort(3) // IEEE float
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 4) // byte rate: sampleRate * channels * bytesPerSample
            header.putShort(4) // block align
            header.putShort(32) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(-1) // data chunk size: unspecified, like Swift
            fos.write(header.array())

            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in chunks) {
                var i = 0
                while (i < chunk.size) {
                    buf.clear()
                    while (i < chunk.size && buf.remaining() >= 4) {
                        buf.putFloat(chunk[i++])
                    }
                    buf.flip()
                    fos.write(buf.array(), 0, buf.limit())
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // JNI. Instance methods so the bindings use the plain
    // Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeXxx names.
    // The native side holds the piper_synthesizer* as a jlong.
    // ------------------------------------------------------------------

    private external fun nativeCreate(
        modelPath: String,
        configPath: String?,
        espeakDataPath: String?,
        dataDir: String?,
        g2pwModelDir: String?
    ): Long

    private external fun nativeDestroy(handle: Long)

    /** Returns [speakerId, lengthScale, noiseScale, noiseWScale]. */
    private external fun nativeDefaultOptions(handle: Long): FloatArray

    private external fun nativeSynthesizeStart(
        handle: Long,
        text: String,
        speakerId: Int,
        lengthScale: Float,
        noiseScale: Float,
        noiseWScale: Float
    ): Int

    /** Returns the next PCM chunk, or null at PIPER_DONE (or on error). */
    private external fun nativeSynthesizeNext(handle: Long): FloatArray?

    private external fun nativeLastChunkSampleRate(handle: Long): Int

    private external fun nativeLastChunkIsLast(handle: Long): Boolean

    private external fun nativeLastChunkPhonemes(handle: Long): IntArray?

    private external fun nativeLastChunkAlignments(handle: Long): IntArray?

    private external fun nativeVersion(): String
}
