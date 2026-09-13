package dev.ihorshevchuk.piper.utils

/**
 * One phoneme group: a run of non-zero codepoints with the model phoneme ids
 * and per-phoneme sample counts that produced it.
 */
data class PhonemeGroup(
    val phoneme: Int,
    val codepoints: IntArray,
    val ids: IntArray,
    val alignments: IntArray,
    /** Total audio samples this group spans (sum of [alignments]). */
    val sampleCount: Int,
    /** Samples before this group started, specials included; markers stay monotonic. */
    val cumulativeOffsetBefore: Int,
    val isSpecial: Boolean
) {
    val cumulativeOffsetAfter: Int get() = cumulativeOffsetBefore + sampleCount

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhonemeGroup) return false
        return phoneme == other.phoneme &&
            codepoints.contentEquals(other.codepoints) &&
            ids.contentEquals(other.ids) &&
            alignments.contentEquals(other.alignments) &&
            sampleCount == other.sampleCount &&
            cumulativeOffsetBefore == other.cumulativeOffsetBefore &&
            isSpecial == other.isSpecial
    }

    override fun hashCode(): Int {
        var result = phoneme
        result = 31 * result + codepoints.contentHashCode()
        result = 31 * result + ids.contentHashCode()
        result = 31 * result + alignments.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + cumulativeOffsetBefore
        result = 31 * result + isSpecial.hashCode()
        return result
    }
}

/** One entry of [AlignmentParser.phonemeOffsets]. */
data class PhonemeOffset(
    val phoneme: Int,
    val offset: Int,
    val count: Int
)

/**
 * Port of PiperAlignmentParser (piper-objc): applies the piper.h grouping rule.
 *
 * Grouping rule (from piper.h, verbatim): phonemes look like
 * [p1, p1, 0, p2, p2, 0, ...] where the same phoneme codepoint is repeated for
 * each id from that phoneme. Groups are separated by a 0. Read N codepoints
 * until 0; the next N ids and N alignments belong to that phoneme.
 *
 * - Codepoint 0 is a separator only, never a group of its own.
 * - Special-ness is ID-based: a group is special when every id is in 0..2
 *   (BOS=1, PAD=0, EOS=2 in id space). A real phoneme group may carry a PAD id
 *   as a second element (e.g. [realId, 0]) and is NOT special.
 * - Special groups are ignored for highlighting, but their sample counts still
 *   advance the cumulative offset.
 * - Malformed input (ids/alignments shorter than a phoneme run needs) stops
 *   grouping gracefully instead of throwing.
 */
object AlignmentParser {

    fun group(phonemes: IntArray, ids: IntArray, alignments: IntArray): List<PhonemeGroup> {
        val groups = mutableListOf<PhonemeGroup>()
        var pCursor = 0
        var idCursor = 0
        var cumulative = 0

        while (pCursor < phonemes.size) {
            // Count N until the 0 separator.
            var n = 0
            while (pCursor + n < phonemes.size && phonemes[pCursor + n] != 0) {
                n++
            }

            if (n == 0) {
                // Zero separator alone: skip it.
                if (pCursor < phonemes.size && phonemes[pCursor] == 0) {
                    pCursor++
                    continue
                }
                break
            }

            // Malformed: not enough ids/alignments for this run - stop gracefully.
            if (idCursor + n > ids.size || idCursor + n > alignments.size) break

            val codepoints = phonemes.copyOfRange(pCursor, pCursor + n)
            val idSlice = ids.copyOfRange(idCursor, idCursor + n)
            val alignSlice = alignments.copyOfRange(idCursor, idCursor + n)
            val sampleCount = alignSlice.sum()
            val isSpecial = idSlice.all { it in 0..2 }

            groups.add(
                PhonemeGroup(
                    phoneme = codepoints[0],
                    codepoints = codepoints,
                    ids = idSlice,
                    alignments = alignSlice,
                    sampleCount = sampleCount,
                    cumulativeOffsetBefore = cumulative,
                    isSpecial = isSpecial
                )
            )
            cumulative += sampleCount

            pCursor += n
            if (pCursor < phonemes.size && phonemes[pCursor] == 0) {
                pCursor++ // skip the separator
            }
            idCursor += n
        }

        return groups
    }

    /**
     * Convenience over [group]: (phoneme, offset, count) triples.
     * Special groups are skipped for highlighting but still advance the
     * running offset, so the first real phoneme's offset includes leading
     * BOS silence.
     */
    fun phonemeOffsets(
        groups: List<PhonemeGroup>,
        includeSpecial: Boolean = false
    ): List<PhonemeOffset> {
        val out = mutableListOf<PhonemeOffset>()
        var running = 0
        for (g in groups) {
            if (!includeSpecial && g.isSpecial) {
                running += g.sampleCount
                continue
            }
            out.add(PhonemeOffset(phoneme = g.phoneme, offset = running, count = g.sampleCount))
            running += g.sampleCount
        }
        return out
    }
}
