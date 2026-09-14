package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.MemoryInfo
import dev.ihorshevchuk.piper.utils.MemoryPressurePolicy
import dev.ihorshevchuk.piper.utils.PhonemeGroup
import dev.ihorshevchuk.piper.utils.SpeechMarker
import dev.ihorshevchuk.piper.utils.SynthesisPlanner
import java.io.File
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

    /**
     * Optional memory threshold in bytes, mirroring Piper.memoryThresholdBytes
     * (piper-objc). When set, the native synthesizer is recreated before any
     * sentence that would start while [getMemoryUsage] exceeds it.
     */
    @Volatile
    var memoryThresholdBytes: Long? = null

    /**
     * How many times the native synthesizer has been (re)created since init.
     * Test hook: lets device tests observe threshold/trim recreations.
     */
    @Volatile
    internal var recreateCount: Int = 0

    @Volatile
    private var handle: Long = 0L

    /** Resolved once; reused when the synthesizer is recreated. */
    private val resolvedEspeakPath: String? = resolveEspeakDataPath(options, appFilesDir)

    /**
     * Sentence orchestration (port of Piper.doSynthesize) running on the
     * calling thread; every native call funnels through [JniSynth], which
     * serializes onto the engine thread via [runNative].
     */
    private val orchestrator = SynthesisOrchestrator(JniSynth())

    init {
        val modelFile = File(options.modelPath)
        if (!modelFile.isFile) {
            throw PiperException("Voice model not found: ${options.modelPath}")
        }
        runNative("piper_create_with_options") { createLocked() }
        cachedNativeVersion = runNative("piper_version") { nativeVersion() }
    }

    /**
     * Synthesizes [text] sentence by sentence.
     *
     * For each sentence [onSamples] receives raw float32 PCM chunks as they are
     * produced; [onAlignment] receives the chunk's [PhonemeGroup]s (piper.h
     * grouping rule); [onMarkers] receives sentence/word [SpeechMarker]s with
     * cumulative float32 byte offsets into the utterance (alignment-aware when
     * groups exist, legacy character-proportion otherwise).
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
        onAlignment: (List<PhonemeGroup>) -> Unit = {},
        onMarkers: (List<SpeechMarker>) -> Unit = {}
    ) {
        val opts = synthOptions ?: defaultSynthesizeOptions()
        cancelled.set(false)
        try {
            orchestrator.synthesize(
                planned = SynthesisPlanner.plan(text),
                // Per-sentence memory check (port of the doSynthesize loop in
                // piper-objc): runs before native.start for every sentence.
                resolveOptions = { ensureSynthesizer(); opts },
                isCancelled = { cancelled.get() },
                callbacks = SynthesisOrchestrator.Callbacks(
                    onSamples = onSamples,
                    onAlignment = onAlignment,
                    onMarkers = onMarkers,
                    onSampleRate = { currentSampleRate.set(it) }
                )
            )
        } finally {
            endEspeakSession()
        }
    }

    /**
     * Synthesizes [ssml], applying each fragment's prosody rate as the
     * sentence lengthScale (port of Piper.synthesizeSSML).
     *
     * [speakerId] overrides the voice default for every fragment; each
     * fragment's rate resolves through [SpeedCurve.lengthScaleForRate].
     * Marker ranges are offsets into the concatenated SSML plain text.
     */
    fun synthesizeSsml(
        ssml: String,
        speakerId: Int,
        onSamples: (FloatArray) -> Unit,
        onAlignment: (List<PhonemeGroup>) -> Unit = {},
        onMarkers: (List<SpeechMarker>) -> Unit = {}
    ) {
        val base = defaultSynthesizeOptions()
        cancelled.set(false)
        val resolved = resolveSsmlPlan(ssml, base, speakerId)
        val optionsBySentence = resolved.associate { it.sentence to it.options }
        try {
            orchestrator.synthesize(
                planned = resolved.map { it.sentence },
                resolveOptions = { ensureSynthesizer(); optionsBySentence.getValue(it) },
                isCancelled = { cancelled.get() },
                callbacks = SynthesisOrchestrator.Callbacks(
                    onSamples = onSamples,
                    onAlignment = onAlignment,
                    onMarkers = onMarkers,
                    onSampleRate = { currentSampleRate.set(it) }
                )
            )
        } finally {
            endEspeakSession()
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

    /**
     * Synthesizes [ssml] and writes it to [path] as a WAV file (same layout as
     * [synthesizeToFile]; port of Piper.synthesizeSSML toFileAtPath).
     */
    fun synthesizeSsmlToFile(ssml: String, speakerId: Int, path: String) {
        val chunks = mutableListOf<FloatArray>()
        var sampleRate = currentSampleRate.get()
        synthesizeSsml(ssml, speakerId, onSamples = { chunk ->
            chunks.add(chunk)
            sampleRate = currentSampleRate.get()
        })
        val out = File(path)
        out.parentFile?.mkdirs()
        writeWavFloatMono(out, chunks, sampleRate)
    }

    /**
     * Current process memory usage in bytes, or null when it cannot be
     * determined. Mirrors Piper.getMemoryUsage() (piper-objc).
     */
    fun getMemoryUsage(): Long? = MemoryInfo.getMemoryUsage()

    /** Reads the voice's default synthesis options from the native layer. */
    fun defaultSynthesizeOptions(): PiperSynthesizeOptions {
        val a = runNative("piper_default_synthesize_options") {
            ensureLocked(checkThreshold = false)
            nativeDefaultOptions(handle)
        }
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
     * Forwards an Android memory-pressure signal to the engine. The host
     * (e.g. the TTS service) should call this from
     * `ComponentCallbacks2.onTrimMemory` with the raw trim level. Critical
     * pressure releases the native synthesizer (it is rebuilt lazily on next
     * use); low/moderate running pressure recreates it immediately. This
     * never blocks the caller: the work is queued on the engine thread behind
     * any in-flight synthesis, mirroring the async DispatchSourceMemoryPressure
     * handler in Piper (piper-objc).
     */
    fun onTrimMemory(level: Int) {
        val action = MemoryPressurePolicy.actionForTrimLevel(level)
        if (action == MemoryPressurePolicy.Action.NONE || closedFlag.get()) return
        try {
            executor.submit {
                if (closedFlag.get()) return@submit
                when (action) {
                    MemoryPressurePolicy.Action.RECREATE -> {
                        destroyLocked()
                        createLocked()
                        recreateCount++
                    }
                    MemoryPressurePolicy.Action.RELEASE -> destroyLocked()
                    MemoryPressurePolicy.Action.NONE -> Unit
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Engine is shut down; nothing to release.
        }
    }

    /**
     * Destroys and immediately rebuilds the native synthesizer, blocking the
     * caller until the engine thread finishes. Port of Piper.recreateSynthesizer()
     * (piper-objc).
     */
    fun recreateSynthesizer() {
        runNative("piper_recreate_synthesizer") {
            destroyLocked()
            createLocked()
            recreateCount++
        }
    }

    /**
     * Ensures a live native synthesizer, marshalled onto the engine thread.
     * With the default [checkThreshold] = true this is the per-sentence
     * memory check from Piper.doSynthesize (piper-objc): when
     * [memoryThresholdBytes] is set and exceeded, the synthesizer is
     * recreated before the sentence starts. It also lazily (re)creates the
     * handle after a critical-pressure release.
     */
    private fun ensureSynthesizer(checkThreshold: Boolean = true) {
        runNative("piper_ensure_synthesizer") { ensureLocked(checkThreshold) }
    }

    /**
     * Engine-thread only. Recreates the synthesizer when [checkThreshold]
     * and the threshold is exceeded; (re)creates it whenever there is no
     * live handle.
     */
    private fun ensureLocked(checkThreshold: Boolean) {
        val overThreshold = checkThreshold &&
            MemoryPressurePolicy.shouldRecreate(MemoryInfo.getMemoryUsage(), memoryThresholdBytes)
        if (handle != 0L && !overThreshold) return
        destroyLocked()
        createLocked()
        recreateCount++
    }

    /** Engine-thread only. */
    private fun destroyLocked() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Engine-thread only. Throws [PiperException] when creation fails. */
    private fun createLocked() {
        handle = nativeCreate(
            options.modelPath,
            options.configPath,
            resolvedEspeakPath,
            options.dataDir,
            options.g2pwModelDir
        )
        if (handle == 0L) {
            throw PiperException("piper_create_with_options failed for ${options.modelPath}")
        }
    }

    /**
     * Destroys the native synthesizer. Idempotent. Queued behind any in-flight
     * native call on the engine thread, so it never races synthesis.
     */
    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        cancelled.set(true)
        try {
            executor.submit { destroyLocked() }.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Best effort: the process is tearing the engine down anyway.
        }
        executor.shutdown()
    }

    /**
     * Releases the process-wide espeak session lock held by the current
     * utterance, if any (see native_synthesis_lock.h). Utterances that
     * drain normally already released it on the final chunk; this covers
     * cancelled or errored utterances that never reached PIPER_DONE - a
     * long-lived engine holding the lock would otherwise wedge every
     * other engine in the process. Best effort: never masks the
     * utterance's own result.
     */
    private fun endEspeakSession() {
        if (closedFlag.get()) return
        try {
            runNative("piper_end_session") { nativeEndSession(handle) }
        } catch (_: Exception) {
            // The engine is closing or the call failed; the session lock is
            // released by destroy as well, so there is no leak either way.
        }
    }

    /**
     * [NativeSynth] backed by JNI. Each call is serialized onto the engine
     * thread through [runNative]; the orchestrator itself runs on the caller
     * thread, so these must never be wrapped in another [runNative] (that
     * would deadlock the single-thread executor).
     */
    private inner class JniSynth : NativeSynth {
        override fun start(sentence: String, options: PiperSynthesizeOptions): Int {
            val rc = runNative("piper_synthesize_start") {
                // Lazy create only (no threshold check): the per-sentence
                // threshold check already ran in resolveOptions above.
                ensureLocked(checkThreshold = false)
                nativeSynthesizeStart(
                    handle, sentence,
                    options.speakerId, options.lengthScale,
                    options.noiseScale, options.noiseWScale
                )
            }
            if (rc != NativeSynth.OK && rc != NativeSynth.ERR_GENERIC) {
                throw PiperException(
                    "piper_synthesize_start failed (rc=$rc) for sentence: ${sentence.take(64)}"
                )
            }
            return rc
        }

        override fun next(): AudioChunk? =
            runNative("piper_synthesize_next") {
                // A critical-pressure release can land between chunks; the
                // lazy create here keeps the utterance going after a reload.
                ensureLocked(checkThreshold = false)
                // null == PIPER_DONE (or a mid-sentence error: partial audio is kept)
                val samples = nativeSynthesizeNext(handle) ?: return@runNative null
                AudioChunk(
                    samples = samples,
                    sampleRate = nativeLastChunkSampleRate(handle),
                    phonemes = nativeLastChunkPhonemes(handle),
                    phonemeIds = nativeLastChunkPhonemeIds(handle),
                    alignments = nativeLastChunkAlignments(handle),
                    isLast = nativeLastChunkIsLast(handle)
                )
            }
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

    /**
     * Returns the next PCM chunk, or null at PIPER_DONE (or on error: partial
     * audio is kept). May return an empty array when the chunk carries only
     * alignment data (e.g. punctuation); null is reserved for end of sentence.
     */
    private external fun nativeSynthesizeNext(handle: Long): FloatArray?

    private external fun nativeLastChunkSampleRate(handle: Long): Int

    private external fun nativeLastChunkIsLast(handle: Long): Boolean

    private external fun nativeLastChunkPhonemes(handle: Long): IntArray?

    private external fun nativeLastChunkPhonemeIds(handle: Long): IntArray?

    private external fun nativeLastChunkAlignments(handle: Long): IntArray?

    /**
     * Ends the current espeak session early (releases the process-wide
     * lock). Idempotent; safe with no session in flight.
     */
    private external fun nativeEndSession(handle: Long)

    private external fun nativeVersion(): String
}
