package dev.ihorshevchuk.piper.utils

/**
 * Memory-pressure rules ported from Piper (piper-objc, Swift).
 *
 * The Swift side combines two mechanisms:
 * - a per-sentence `memoryThresholdBytes` check in `doSynthesize`: when set
 *   and current usage exceeds it, the native synthesizer is recreated;
 * - a `DispatchSourceMemoryPressure` source: `.warning` recreates the
 *   synthesizer, `.critical` releases it (it is lazily rebuilt on next use).
 *
 * On Android the OS delivers memory pressure through
 * `ComponentCallbacks2.onTrimMemory`; the host (e.g. the TTS service)
 * forwards the raw trim level to [PiperEngine.onTrimMemory], which maps it
 * here. The level constants mirror `ComponentCallbacks2` values so the
 * mapping stays correct without an Android-framework dependency in this
 * pure-JVM module.
 */
object MemoryPressurePolicy {

    /** Mirrors ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE. */
    const val TRIM_MEMORY_RUNNING_MODERATE = 5

    /** Mirrors ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW. */
    const val TRIM_MEMORY_RUNNING_LOW = 10

    /** Mirrors ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL. */
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15

    /** Mirrors ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN. */
    const val TRIM_MEMORY_UI_HIDDEN = 20

    enum class Action {
        /** No memory action for this trim level. */
        NONE,

        /** Destroy and immediately rebuild the native synthesizer. */
        RECREATE,

        /** Destroy the native synthesizer; it is rebuilt lazily on next use. */
        RELEASE
    }

    /**
     * Whether the native synthesizer should be recreated before the next
     * sentence: true only when a threshold is set, usage is known, and usage
     * exceeds the threshold. Mirrors the Swift
     * `if let memoryThresholdBytes, let memory = getMemoryUsage(), memory > memoryThresholdBytes`
     * check.
     */
    fun shouldRecreate(usageBytes: Long?, thresholdBytes: Long?): Boolean =
        thresholdBytes != null && usageBytes != null && usageBytes > thresholdBytes

    /**
     * Maps a `ComponentCallbacks2.onTrimMemory` level to an action.
     * `.critical` -> release (Swift `.critical` -> `releaseSynthesizer()`);
     * running low/moderate -> recreate (Swift `.warning` -> `recreateSynthesizer()`).
     */
    fun actionForTrimLevel(level: Int): Action = when (level) {
        TRIM_MEMORY_RUNNING_CRITICAL -> Action.RELEASE
        TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_MODERATE -> Action.RECREATE
        else -> Action.NONE
    }
}
