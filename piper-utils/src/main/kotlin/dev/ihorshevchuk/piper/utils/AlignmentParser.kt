package dev.ihorshevchuk.piper.utils

/**
 * One phoneme group: a run of identical codepoints with the model phoneme ids
 * and per-phoneme sample counts that produced it.
 */
data class PhonemeGroup(
    val phoneme: Int,
    val codepoints: IntArray,
    val ids: IntArray,
    val alignments: IntArray,
    /** Total audio samples this group spans (sum of [alignments]). */
    val sampleCount: Int,
    /** Samples before this group started; markers stay monotonic. */
    val cumulativeOffsetBefore: Int,
    val isSpecial: Boolean
)

/**
 * Port of PiperAlignmentParser (piper-objc): applies the piper.h grouping rule.
 *
 * The three arrays are parallel (same length). Codepoint 0 (PAD) separates
 * groups and contributes no group of its own. Runs of a repeated codepoint
 * form one group. BOS (1) and EOS (2) each form a single-phoneme group marked
 * [PhonemeGroup.isSpecial]; they still advance the cumulative sample offset.
 */
object AlignmentParser {
    private const val PAD = 0
    private const val BOS = 1
    private const val EOS = 2

    fun group(phonemes: IntArray, ids: IntArray, alignments: IntArray): List<PhonemeGroup> {
        require(phonemes.size == ids.size && ids.size == alignments.size) {
            "phonemes (${phonemes.size}), ids (${ids.size}) and alignments (${alignments.size}) must be parallel"
        }
        val groups = mutableListOf<PhonemeGroup>()
        var cumulative = 0
        var i = 0
        while (i < phonemes.size) {
            val p = phonemes[i]
            if (p == PAD) {
                i++ // separator only
                continue
            }
            if (p == BOS || p == EOS) {
                val sampleCount = alignments[i]
                groups.add(
                    PhonemeGroup(
                        phoneme = p,
                        codepoints = intArrayOf(p),
                        ids = intArrayOf(ids[i]),
                        alignments = intArrayOf(sampleCount),
                        sampleCount = sampleCount,
                        cumulativeOffsetBefore = cumulative,
                        isSpecial = true
                    )
                )
                cumulative += sampleCount
                i++
                continue
            }
            var j = i
            while (j < phonemes.size && phonemes[j] == p) j++
            val codepoints = phonemes.copyOfRange(i, j)
            val idSlice = ids.copyOfRange(i, j)
            val alignmentSlice = alignments.copyOfRange(i, j)
            val sampleCount = alignmentSlice.sum()
            groups.add(
                PhonemeGroup(
                    phoneme = p,
                    codepoints = codepoints,
                    ids = idSlice,
                    alignments = alignmentSlice,
                    sampleCount = sampleCount,
                    cumulativeOffsetBefore = cumulative,
                    isSpecial = false
                )
            )
            cumulative += sampleCount
            i = j
        }
        return groups
    }
}
