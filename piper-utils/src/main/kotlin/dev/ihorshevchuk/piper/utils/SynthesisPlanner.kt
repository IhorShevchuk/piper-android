package dev.ihorshevchuk.piper.utils

/**
 * One sentence ready for synthesis.
 *
 * @param text the sentence text.
 * @param range the sentence's range in the utterance source text (UTF-16
 * units), for speech markers. For SSML this is an offset into the
 * concatenated plain text, mirroring Swift's
 * `ssmlFragment.ssmlRange.location + locationInFragment`.
 * @param rate the SSML prosody rate multiplier that produced this sentence;
 * 1.0 for plain text.
 */
data class PlannedSentence(
    val text: String,
    val range: MarkerRange,
    val rate: Float = 1.0f
)

/**
 * Pure sentence-planning half of the Piper.doSynthesize port (piper-objc).
 *
 * Splits an utterance into sentences, locates each sentence in the source
 * text for markers (Swift's `text.range(of:)` sequential search), and for
 * SSML carries each fragment's prosody rate so the engine can resolve it to
 * a lengthScale via [SpeedCurve.lengthScaleForRate].
 */
object SynthesisPlanner {

    /**
     * Plans plain-text synthesis: every sentence gets rate 1.0 and a range
     * into [text].
     */
    fun plan(text: String): List<PlannedSentence> =
        locateSentences(text, 0, 1.0f)

    /**
     * Plans SSML synthesis: parses [ssml] into fragments, splits each
     * fragment into sentences, and offsets each sentence range by the
     * fragment's range in the concatenated plain text.
     */
    fun planSsml(ssml: String): List<PlannedSentence> {
        val out = mutableListOf<PlannedSentence>()
        for (fragment in SsmlParser.parse(ssml)) {
            out += locateSentences(fragment.text, fragment.range.first, fragment.rate)
        }
        return out
    }

    /**
     * Splits [text] into sentences and locates each one sequentially,
     * mirroring Swift's `text.range(of: sentence, range: searchStartIndex..<end)`.
     * Ranges are offset by [baseOffset] for SSML fragments. A sentence whose
     * normalized form cannot be located (whitespace was collapsed) still gets
     * planned - Swift synthesizes it with an NSNotFound range and no markers -
     * so it carries [RANGE_NOT_FOUND] and the orchestrator skips its markers.
     */
    private fun locateSentences(
        text: String,
        baseOffset: Int,
        rate: Float
    ): List<PlannedSentence> {
        val out = mutableListOf<PlannedSentence>()
        var searchFrom = 0
        for (sentence in SentenceSplitter.split(text)) {
            val at = text.indexOf(sentence, searchFrom)
            val range = if (at < 0) {
                RANGE_NOT_FOUND
            } else {
                searchFrom = at + sentence.length
                MarkerRange(baseOffset + at, sentence.length)
            }
            out.add(PlannedSentence(text = sentence, range = range, rate = rate))
        }
        return out
    }

    /**
     * Range for a sentence that could not be located in the source text
     * (mirrors NSNotFound); the orchestrator synthesizes it but emits no
     * markers, exactly like Swift's doSynthesize.
     */
    val RANGE_NOT_FOUND = MarkerRange(-1, 0)
}
