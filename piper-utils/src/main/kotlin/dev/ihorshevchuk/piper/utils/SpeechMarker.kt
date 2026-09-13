package dev.ihorshevchuk.piper.utils

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** UTF-16 range of a marker inside the source text (NSRange equivalent). */
data class MarkerRange(val location: Int, val length: Int)

/** Sentence markers fire at sentence starts; word markers drive highlighting. */
enum class SpeechMarkerType {
    SENTENCE,
    WORD
}

/**
 * One speech marker: a [range] into the source text and a [byteOffset] into
 * the synthesized float32 PCM byte stream.
 *
 * Port of PiperSpeechMarker (piper-objc, Swift).
 *
 * Generates the sentence + word markers the reader UI uses for highlighting.
 * Two strategies:
 * - [generateMarkers]: legacy heuristic, distributes a sentence's bytes across
 *   its words by character proportion. Used when no alignment data is
 *   available (e.g. the file-synthesis path).
 * - [generateMarkersWithAlignment]: accurate offsets derived from per-phoneme
 *   sample counts (see [AlignmentParser] and the piper.h grouping rule).
 *
 * Offset units: UTF-16 code units throughout, matching how Android spans index
 * strings. (The Swift legacy path mixes grapheme-cluster distances for the
 * location with UTF-16 lengths; for non-BMP text that mix is inconsistent, so
 * the port standardizes on UTF-16. All ported Swift test expectations use BMP
 * text, where both agree.)
 */
data class SpeechMarker(
    val range: MarkerRange,
    val byteOffset: Int,
    val type: SpeechMarkerType = SpeechMarkerType.SENTENCE
) {
    companion object {

    /** Bytes per float32 PCM sample in the synthesized stream. */
    private const val BYTES_PER_SAMPLE = 4

    /**
     * Punctuation stripped from word edges when locating cores (#31):
     * curly quotes U+2018/U+2019, straight quotes, and common ASCII punctuation
     * including em/en dashes.
     */
    private val punctuationTrimSet: Set<Char> =
        setOf('‘', '’', '\'', '"', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']', '{', '}', '—', '–')

    /**
     * Legacy heuristic: estimate word byte offsets by character proportion.
     * Retained for fallback when alignment data is unavailable.
     */
    fun generateMarkers(
        sentence: String,
        sentenceRange: MarkerRange,
        startByteOffset: Int,
        totalBytes: Int
    ): List<SpeechMarker> {
        val words = splitWords(sentence)
        if (words.isEmpty() || totalBytes <= 0 || sentenceRange.location < 0) {
            return listOf(SpeechMarker(sentenceRange, startByteOffset, SpeechMarkerType.SENTENCE))
        }

        val totalCharacters = words.sumOf { it.length }
        if (totalCharacters == 0) {
            return listOf(SpeechMarker(sentenceRange, startByteOffset, SpeechMarkerType.SENTENCE))
        }

        val markers = mutableListOf(
            SpeechMarker(sentenceRange, startByteOffset, SpeechMarkerType.SENTENCE)
        )
        var currentByteOffset = startByteOffset
        var searchFrom = 0

        for (word in words) {
            val idx = sentence.indexOf(word, searchFrom)
            if (idx < 0) continue
            val wordRange = MarkerRange(sentenceRange.location + idx, word.length)
            val wordBytes = (totalBytes * (word.length.toDouble() / totalCharacters)).toInt()
            markers.add(SpeechMarker(wordRange, currentByteOffset, SpeechMarkerType.WORD))
            currentByteOffset += wordBytes
            searchFrom = idx + word.length
        }
        return markers
    }

    /**
     * Alignment-based marker generation.
     *
     * Uses per-phoneme sample counts from the audio chunk alignments (grouped
     * by the piper.h rule) to derive accurate byte offsets. BOS/PAD/EOS groups
     * are ignored for highlighting but their sample counts still advance the
     * cumulative offset. Phoneme groups are distributed across words
     * proportionally to stripped core-word character counts.
     *
     * Falls back to [generateMarkers] when there is no usable alignment data.
     */
    fun generateMarkersWithAlignment(
        sentence: String,
        sentenceRange: MarkerRange,
        startByteOffset: Int,
        groups: List<PhonemeGroup>
    ): List<SpeechMarker> {
        if (sentenceRange.location < 0) {
            return listOf(SpeechMarker(sentenceRange, startByteOffset, SpeechMarkerType.SENTENCE))
        }

        val sentenceMarker = SpeechMarker(sentenceRange, startByteOffset, SpeechMarkerType.SENTENCE)

        val rawTokens = splitWords(sentence)
        if (rawTokens.isEmpty()) return listOf(sentenceMarker)

        val totalSamplesFromGroups = groups.sumOf { it.sampleCount }
        if (totalSamplesFromGroups <= 0) {
            val totalBytesFallback = max(1, rawTokens.sumOf { it.length } * 200)
            return generateMarkers(sentence, sentenceRange, startByteOffset, totalBytesFallback)
        }

        val realGroups = groups.filter { !it.isSpecial }
        if (realGroups.isEmpty()) {
            val totalBytes = totalSamplesFromGroups * BYTES_PER_SAMPLE
            return generateMarkers(sentence, sentenceRange, startByteOffset, totalBytes)
        }

        // Strip surrounding punctuation (#31) so "‘idealists’." still highlights "idealists".
        val coreWords = rawTokens.mapNotNull { token ->
            val core = token.trim { it in punctuationTrimSet }
            if (core.isEmpty()) null else core
        }
        if (coreWords.isEmpty()) return listOf(sentenceMarker)

        val totalCoreChars = coreWords.sumOf { it.length }
        if (totalCoreChars == 0) return listOf(sentenceMarker)

        val markers = mutableListOf(sentenceMarker)
        var groupIdx = 0
        var searchFrom = 0
        // Include initial BOS silence: the first real group's offset already accounts for it.
        var cumulativeSamples = realGroups.first().cumulativeOffsetBefore
        val lastWordIndex = coreWords.size - 1

        for ((i, core) in coreWords.withIndex()) {
            val idx = sentence.indexOf(core, searchFrom)
            if (idx < 0) continue
            val wordRange = MarkerRange(sentenceRange.location + idx, core.length)

            // Distribute phoneme groups across words proportionally; the last
            // word takes whatever remains so the sum is exact.
            val groupsForWord = if (i == lastWordIndex) {
                realGroups.size - groupIdx
            } else {
                val proportion = core.length.toDouble() / totalCoreChars
                max(1, (proportion * realGroups.size).roundToInt())
            }
            val clampedGroups = min(groupsForWord, realGroups.size - groupIdx)

            var wordSampleCount = 0
            for (g in realGroups.subList(groupIdx, groupIdx + clampedGroups)) {
                wordSampleCount += g.sampleCount
            }

            val byteOffset = startByteOffset + cumulativeSamples * BYTES_PER_SAMPLE
            markers.add(SpeechMarker(wordRange, byteOffset, SpeechMarkerType.WORD))

            cumulativeSamples += wordSampleCount
            groupIdx += clampedGroups
            searchFrom = idx + core.length
            // If groups were consumed early, remaining words share the final
            // offset rather than being dropped.
        }

        return markers
    }

    /** Splits on Unicode whitespace, mirroring Swift's .whitespacesAndNewlines. */
    private fun splitWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (c in text) {
            if (c.isWhitespace()) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }
}

}
