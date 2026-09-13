package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM port of PiperSpeechMarkerTests + AlignmentWordMarkerTests (piper-objc, Swift).
 *
 * Markers drive reader word highlighting: one sentence marker plus one word
 * marker per word, each with a UTF-16 range into the source text and a byte
 * offset into the synthesized float32 PCM stream.
 */
class SpeechMarkerTest {

    // MARK: - Legacy (character-proportion) generation

    @Test
    fun generatesSentenceAndWordMarkers() {
        val sentence = "Hello world."
        val sentenceRange = MarkerRange(location = 10, length = 12)
        val startOffset = 1000
        val totalBytes = 8800 // e.g. 2200 samples * 4 bytes/sample

        val markers = SpeechMarker.generateMarkers(
            sentence = sentence,
            sentenceRange = sentenceRange,
            startByteOffset = startOffset,
            totalBytes = totalBytes
        )

        assertEquals("1 sentence marker + 2 word markers", 3, markers.size)

        val sentenceMarker = markers[0]
        assertEquals(SpeechMarkerType.SENTENCE, sentenceMarker.type)
        assertEquals(sentenceRange, sentenceMarker.range)
        assertEquals(startOffset, sentenceMarker.byteOffset)

        val helloMarker = markers[1]
        assertEquals(SpeechMarkerType.WORD, helloMarker.type)
        assertEquals(MarkerRange(location = 10, length = 5), helloMarker.range) // "Hello"
        assertEquals(startOffset, helloMarker.byteOffset)

        val worldMarker = markers[2]
        assertEquals(SpeechMarkerType.WORD, worldMarker.type)
        assertEquals(MarkerRange(location = 16, length = 6), worldMarker.range) // "world."

        val helloBytes = (totalBytes * (5.0 / 11.0)).toInt() // "Hello" is 5 of 11 chars
        assertEquals(startOffset + helloBytes, worldMarker.byteOffset)
    }

    @Test
    fun emptySentenceYieldsOnlySentenceMarker() {
        val markers = SpeechMarker.generateMarkers(
            sentence = "  ",
            sentenceRange = MarkerRange(location = 0, length = 2),
            startByteOffset = 0,
            totalBytes = 100
        )

        assertEquals(1, markers.size)
        assertEquals(SpeechMarkerType.SENTENCE, markers[0].type)
    }

    @Test
    fun punctuationOnlySentenceStillGetsWordMarker() {
        val markers = SpeechMarker.generateMarkers(
            sentence = ".!?",
            sentenceRange = MarkerRange(location = 5, length = 3),
            startByteOffset = 100,
            totalBytes = 200
        )

        assertEquals(2, markers.size)
        assertEquals(SpeechMarkerType.SENTENCE, markers[0].type)
        assertEquals(MarkerRange(location = 5, length = 3), markers[0].range)
    }

    @Test
    fun zeroTotalBytesYieldsOnlySentenceMarker() {
        val markers = SpeechMarker.generateMarkers(
            sentence = "Hello",
            sentenceRange = MarkerRange(location = 0, length = 5),
            startByteOffset = 0,
            totalBytes = 0
        )
        assertEquals("Only a sentence marker when totalBytes is zero", 1, markers.size)
        assertEquals(SpeechMarkerType.SENTENCE, markers[0].type)
    }

    // MARK: - Punctuation cases (#31, ported from AlignmentWordMarkerTests)

    @Test
    fun apostrophePunctuationRetainsWordRanges() {
        val sentence = "Such philosophers are called ‘idealists’."
        val range = MarkerRange(location = 0, length = sentence.length)
        val markers = SpeechMarker.generateMarkers(
            sentence = sentence,
            sentenceRange = range,
            startByteOffset = 0,
            totalBytes = 4400
        )

        val wordMarkers = markers.filter { it.type == SpeechMarkerType.WORD }
        assertTrue("Markers for each word even with curly quotes", wordMarkers.size >= 5)

        var lastOffset = -1
        for (m in wordMarkers) {
            assertTrue("Byte offsets must be monotonic", m.byteOffset >= lastOffset)
            lastOffset = m.byteOffset
        }

        val idealistsAt = sentence.indexOf("idealists")
        assertTrue("idealists substring must exist", idealistsAt >= 0)
        val idealistsEnd = idealistsAt + "idealists".length
        val hasIdealistsMarker = wordMarkers.any { marker ->
            val start = marker.range.location
            val end = start + marker.range.length
            start < idealistsEnd && end > idealistsAt
        }
        assertTrue("Marker covering 'idealists' despite surrounding punctuation", hasIdealistsMarker)
    }

    @Test
    fun emDashAndSemicolonDoNotBreakMarkers() {
        val sentence = "First sentence, with a comma, and more; second sentence: with colons"
        val range = MarkerRange(location = 10, length = sentence.length)
        val markers = SpeechMarker.generateMarkers(
            sentence = sentence,
            sentenceRange = range,
            startByteOffset = 100,
            totalBytes = 8000
        )
        val wordMarkers = markers.filter { it.type == SpeechMarkerType.WORD }
        assertTrue(wordMarkers.size >= 8)
        assertEquals(10, wordMarkers.first().range.location)
    }

    // MARK: - Alignment-aware generation

    @Test
    fun wordMarkersPreferAlignmentDerivedOffsets() {
        val groups = listOf(
            PhonemeGroup(
                phoneme = 104, codepoints = intArrayOf(104, 104),
                ids = intArrayOf(12, 0), alignments = intArrayOf(100, 20),
                sampleCount = 120, cumulativeOffsetBefore = 0, isSpecial = false
            ),
            PhonemeGroup(
                phoneme = 101, codepoints = intArrayOf(101),
                ids = intArrayOf(13), alignments = intArrayOf(100),
                sampleCount = 100, cumulativeOffsetBefore = 120, isSpecial = false
            ),
            PhonemeGroup(
                phoneme = 119, codepoints = intArrayOf(119),
                ids = intArrayOf(14), alignments = intArrayOf(120),
                sampleCount = 120, cumulativeOffsetBefore = 220, isSpecial = false
            ),
            PhonemeGroup(
                phoneme = 111, codepoints = intArrayOf(111, 111),
                ids = intArrayOf(15, 0), alignments = intArrayOf(90, 30),
                sampleCount = 120, cumulativeOffsetBefore = 340, isSpecial = false
            )
        )

        val sentence = "Hello world"
        val range = MarkerRange(location = 0, length = sentence.length)

        val markers = SpeechMarker.generateMarkersWithAlignment(
            sentence = sentence,
            sentenceRange = range,
            startByteOffset = 0,
            groups = groups
        )

        assertTrue("Sentence + 2 word markers expected", markers.size >= 3)
        val wordMarkers = markers.filter { it.type == SpeechMarkerType.WORD }
        assertEquals("Two word markers for Hello and world", 2, wordMarkers.size)
        assertEquals("First word at offset 0", 0, wordMarkers[0].byteOffset)
        assertEquals(
            "Second word offset derived from alignment, not char proportion",
            880,
            wordMarkers[1].byteOffset
        )
    }

    @Test
    fun alignmentPathStripsPunctuationForWordRanges() {
        // "‘idealists’." -> core "idealists" still found and ranged correctly.
        val groups = listOf(
            PhonemeGroup(
                phoneme = 97, codepoints = intArrayOf(97),
                ids = intArrayOf(10), alignments = intArrayOf(400),
                sampleCount = 400, cumulativeOffsetBefore = 0, isSpecial = false
            )
        )
        val sentence = "Say ‘idealists’."
        val range = MarkerRange(location = 0, length = sentence.length)

        val markers = SpeechMarker.generateMarkersWithAlignment(
            sentence = sentence,
            sentenceRange = range,
            startByteOffset = 0,
            groups = groups
        )

        val wordMarkers = markers.filter { it.type == SpeechMarkerType.WORD }
        assertEquals(2, wordMarkers.size) // "Say" + "idealists"
        val idealists = wordMarkers[1]
        assertEquals(sentence.indexOf("idealists"), idealists.range.location)
        assertEquals("idealists".length, idealists.range.length)
    }

    @Test
    fun alignmentPathFallsBackWhenNoGroups() {
        val sentence = "Hello world"
        val range = MarkerRange(location = 0, length = sentence.length)

        val markers = SpeechMarker.generateMarkersWithAlignment(
            sentence = sentence,
            sentenceRange = range,
            startByteOffset = 0,
            groups = emptyList()
        )

        // Falls back to the legacy heuristic; must still be sentence + words.
        assertEquals(3, markers.size)
        assertEquals(SpeechMarkerType.SENTENCE, markers[0].type)
    }
}
