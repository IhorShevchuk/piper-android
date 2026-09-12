package dev.ihorshevchuk.piper.engine

/**
 * Per-utterance synthesis options. Mirrors piper_synthesize_options.
 * Obtain sane voice defaults via [PiperEngine.defaultSynthesizeOptions].
 */
data class PiperSynthesizeOptions(
    /** Speaker id for multi-speaker voices. */
    val speakerId: Int = 0,
    /** Speaking speed; lower is faster. 1.0 = normal. */
    val lengthScale: Float = 1.0f,
    /** Piper default. */
    val noiseScale: Float = 0.667f,
    /** Piper default. */
    val noiseWScale: Float = 0.8f
)
