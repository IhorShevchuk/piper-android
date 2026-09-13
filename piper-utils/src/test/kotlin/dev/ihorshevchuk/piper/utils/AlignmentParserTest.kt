package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM port of PiperAlignmentParserTests (piper-objc, Swift).
 * Guards the piper.h grouping rule: runs of non-zero codepoints form a group,
 * taking N ids and N alignments each; codepoint 0 is a separator only.
 * Special-ness is ID-based (all ids in 0..2), never codepoint-based.
 */
class AlignmentParserTest {

    @Test
    fun groupsPhonemesByZeroSeparator() {
        // phonemes: [a,a,0,b,0,c,c,0] -> 3 groups N=2,1,2
        val phonemes = intArrayOf(97, 97, 0, 98, 0, 99, 99, 0) // a=97,b=98,c=99
        val ids = intArrayOf(1, 0, 5, 7, 8) // N=2 BOS+PAD, N=1 real, N=2 real+real
        val alignments = intArrayOf(100, 50, 200, 150, 60)

        val groups = AlignmentParser.group(phonemes, ids, alignments)

        assertEquals("Expected 3 phoneme groups", 3, groups.size)
        assertArrayEquals(intArrayOf(97, 97), groups[0].codepoints)
        assertArrayEquals(intArrayOf(1, 0), groups[0].ids)
        assertArrayEquals(intArrayOf(100, 50), groups[0].alignments)
        assertEquals(150, groups[0].sampleCount)
        assertEquals(0, groups[0].cumulativeOffsetBefore)

        assertArrayEquals(intArrayOf(98), groups[1].codepoints)
        assertArrayEquals(intArrayOf(5), groups[1].ids)
        assertEquals(200, groups[1].sampleCount)
        assertEquals(150, groups[1].cumulativeOffsetBefore)

        assertArrayEquals(intArrayOf(99, 99), groups[2].codepoints)
        assertEquals(210, groups[2].sampleCount)
        assertEquals(350, groups[2].cumulativeOffsetBefore)
    }

    @Test
    fun bosPadEosGroupsAreSpecial() {
        val phonemes = intArrayOf(94, 94, 0, 97, 0) // '^','^' BOS, 'a'
        val ids = intArrayOf(1, 0, 10) // BOS=1,PAD=0, real=10
        val alignments = intArrayOf(30, 20, 300)

        val groups = AlignmentParser.group(phonemes, ids, alignments)

        assertEquals(2, groups.size)
        assertTrue("BOS+PAD group should be special", groups[0].isSpecial)
        assertFalse(groups[1].isSpecial)

        val filtered = AlignmentParser.phonemeOffsets(groups, includeSpecial = false)
        assertEquals("Only real phoneme should remain", 1, filtered.size)
        assertEquals(97, filtered[0].phoneme)
        assertEquals("Offset should include BOS silence before first real phoneme", 50, filtered[0].offset)
    }

    @Test
    fun emptyChunkReturnsEmptyGroups() {
        val groups = AlignmentParser.group(intArrayOf(), intArrayOf(), intArrayOf())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun pinyinMultiCodepointGrouping() {
        val pinyinCp = 0xE000 // private-use area
        val phonemes = intArrayOf(pinyinCp, pinyinCp, 0)
        val ids = intArrayOf(15, 0)
        val alignments = intArrayOf(180, 20)

        val groups = AlignmentParser.group(phonemes, ids, alignments)
        assertEquals(1, groups.size)
        assertTrue(groups[0].codepoints.all { it == pinyinCp })
        assertEquals(200, groups[0].sampleCount)
    }

    @Test
    fun cumulativeOffsetsSumIncludingPad() {
        val phonemes = intArrayOf(101, 101, 0, 102, 102, 0)
        val ids = intArrayOf(12, 0, 13, 0)
        val alignments = intArrayOf(100, 25, 120, 30)

        val groups = AlignmentParser.group(phonemes, ids, alignments)
        val offsets = AlignmentParser.phonemeOffsets(groups, includeSpecial = true)
        assertEquals(0, offsets[0].offset)
        assertEquals(125, offsets[0].count)
        assertEquals(125, offsets[1].offset)
        assertEquals(150, offsets[1].count)
    }

    @Test
    fun realGroupWithPadIdIsNotSpecial() {
        // [realId, 0]: PAD id as second element must NOT make the group special.
        val phonemes = intArrayOf(104, 104, 0)
        val ids = intArrayOf(12, 0)
        val alignments = intArrayOf(100, 20)

        val groups = AlignmentParser.group(phonemes, ids, alignments)
        assertEquals(1, groups.size)
        assertFalse(groups[0].isSpecial)
    }

    @Test
    fun malformedInputStopsGracefullyInsteadOfThrowing() {
        // Fewer ids than the phoneme run needs: must stop, not crash.
        val phonemes = intArrayOf(97, 97, 0, 98, 0)
        val ids = intArrayOf(5) // too short for the first N=2 group
        val alignments = intArrayOf(100)

        val groups = AlignmentParser.group(phonemes, ids, alignments)
        assertTrue(groups.isEmpty())
    }
}
