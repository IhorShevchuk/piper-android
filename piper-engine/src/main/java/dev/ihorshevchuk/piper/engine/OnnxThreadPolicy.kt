package dev.ihorshevchuk.piper.engine

/**
 * Selects how many ONNX Runtime intra-op threads the native engine should
 * request, based on the device's CPU count.
 *
 * Pinned piper1-gpl hard-codes `SetIntraOpNumThreads(1)`, leaving 7 of 8
 * cores idle on e.g. a Galaxy A13 - one sentence takes 7-10 s to synthesize,
 * slower than realtime. The native patch calls [intraOpThreads] (via JNI,
 * once per engine creation) and passes the result to
 * `SetIntraOpNumThreads` instead.
 *
 * Policy: use all but two of the cores, capped at 8, keeping headroom for
 * AudioTrack I/O and the UI. Two or fewer cores stay single-threaded - there
 * is nothing to gain and oversubscription hurts.
 */
object OnnxThreadPolicy {
    fun intraOpThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int {
        if (cores <= 2) return 1
        return minOf(8, cores - 2).coerceAtLeast(1)
    }
}
