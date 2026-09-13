package dev.ihorshevchuk.piper.engine

/**
 * One chunk of synthesized audio with the alignment data the native layer
 * captured for it. A null [NativeSynth.next] result ends the sentence.
 */
data class AudioChunk(
    /** Float32 PCM samples; may be empty when the chunk carries only alignment. */
    val samples: FloatArray,
    val sampleRate: Int,
    /** Raw phoneme codepoints, 0-separated groups (piper.h grouping rule). */
    val phonemes: IntArray? = null,
    /** Phoneme ids parallel to [phonemes] groups; needed for special detection. */
    val phonemeIds: IntArray? = null,
    /** Per-phoneme sample counts, N per group. */
    val alignments: IntArray? = null,
    /** True when this is the sentence's final chunk; null still ends the stream. */
    val isLast: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            phonemes.contentEquals(other.phonemes) &&
            phonemeIds.contentEquals(other.phonemeIds) &&
            alignments.contentEquals(other.alignments) &&
            isLast == other.isLast
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + (phonemes?.contentHashCode() ?: 0)
        result = 31 * result + (phonemeIds?.contentHashCode() ?: 0)
        result = 31 * result + (alignments?.contentHashCode() ?: 0)
        result = 31 * result + isLast.hashCode()
        return result
    }
}

/**
 * Native synthesis operations behind [PiperEngine], extracted so the
 * sentence-by-sentence orchestration ([SynthesisOrchestrator]) is
 * unit-testable with a fake instead of JNI.
 */
internal interface NativeSynth {
    companion object {
        /** piper_synthesize_start succeeded. */
        const val OK = 0
        /** Sentence failed (emoji/URL); skip it, keep the utterance going. */
        const val ERR_GENERIC = -1
    }

    /**
     * Starts synthesizing [sentence]. Returns [OK], or [ERR_GENERIC] when the
     * sentence cannot be synthesized (it is skipped); throws [PiperException]
     * for anything else.
     */
    fun start(sentence: String, options: PiperSynthesizeOptions): Int

    /**
     * Returns the next audio chunk, or null when the sentence is done
     * (PIPER_DONE) or errored mid-sentence (remainder is skipped).
     */
    fun next(): AudioChunk?
}
