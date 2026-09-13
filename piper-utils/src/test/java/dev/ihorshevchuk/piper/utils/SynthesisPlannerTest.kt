package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SynthesisPlanner], the pure sentence-planning half of the
 * Piper.doSynthesize port: split the utterance into sentences, locate each
 * sentence in the source text for markers, and carry the SSML prosody rate.
 */
class SynthesisPlannerTest {

    @Test
    fun `plain text plans sentences with ranges into the text`() {
        val text = "Hello world. How are you?"
        val planned = SynthesisPlanner.plan(text)

        assertEquals(2, planned.size)
        assertEquals("Hello world.", planned[0].text)
        assertEquals(MarkerRange(0, 12), planned[0].range)
        assertEquals("How are you?", planned[1].text)
        assertEquals(MarkerRange(13, 12), planned[1].range)
    }

    @Test
    fun `plain text plan carries rate 1_0`() {
        val planned = SynthesisPlanner.plan("One. Two.")
        assertTrue(planned.all { it.rate == 1.0f })
    }

    @Test
    fun `empty text plans nothing`() {
        assertTrue(SynthesisPlanner.plan("").isEmpty())
        assertTrue(SynthesisPlanner.plan("   ").isEmpty())
    }

    @Test
    fun `ssml plan preserves fragment rates`() {
        val ssml = "<speak><prosody rate=\"0.5\">Slow down.</prosody> Normal.</speak>"
        val planned = SynthesisPlanner.planSsml(ssml)

        assertEquals(2, planned.size)
        assertEquals("Slow down.", planned[0].text)
        assertEquals(0.5f, planned[0].rate, 0.0001f)
        assertEquals("Normal.", planned[1].text)
        // Untagged text uses the SSML default rate 1.0.
        assertEquals(1.0f, planned[1].rate, 0.0001f)
    }

    @Test
    fun `ssml plan ranges are offsets into the concatenated plain text`() {
        val ssml = "<speak><prosody rate=\"0.5\">Slow down.</prosody> Normal.</speak>"
        // Concatenated plain text is "Slow down. Normal."
        val plain = "Slow down. Normal."
        val planned = SynthesisPlanner.planSsml(ssml)

        assertEquals(MarkerRange(0, 10), planned[0].range)
        assertEquals(MarkerRange(11, 7), planned[1].range)
        for (p in planned) {
            assertEquals(p.text, plain.substring(p.range.location, p.range.location + p.range.length))
        }
    }

    @Test
    fun `ssml without prosody plans like plain text`() {
        val planned = SynthesisPlanner.planSsml("<speak>Hello. World.</speak>")
        assertEquals(2, planned.size)
        // SSML default rate is 1.0 (normal speed).
        assertTrue(planned.all { it.rate == 1.0f })
        assertEquals(MarkerRange(0, 6), planned[0].range)
        assertEquals(MarkerRange(7, 6), planned[1].range)
    }

    @Test
    fun `repeated sentences are located sequentially`() {
        val planned = SynthesisPlanner.plan("Hello. Hello.")
        assertEquals(2, planned.size)
        assertEquals(MarkerRange(0, 6), planned[0].range)
        assertEquals(MarkerRange(7, 6), planned[1].range)
    }

    @Test
    fun `sentence with collapsed whitespace is still planned without a range`() {
        // Normalization collapses the inner whitespace, so the sentence text
        // no longer occurs verbatim: Swift still synthesizes it (with an
        // NSNotFound range and no markers) instead of dropping the audio.
        val planned = SynthesisPlanner.plan("Hello     world.")
        assertEquals(1, planned.size)
        assertEquals("Hello world.", planned[0].text)
        assertEquals(SynthesisPlanner.RANGE_NOT_FOUND, planned[0].range)
    }

    @Test
    fun `ssml break plans a silent sentence between text`() {
        val planned = SynthesisPlanner.planSsml("<speak>Wait.<break time=\"500ms\"/> Go.</speak>")

        assertEquals(3, planned.size)
        assertEquals("Wait.", planned[0].text)
        assertEquals(0L, planned[0].pauseMillis)
        assertEquals("", planned[1].text)
        assertEquals(500L, planned[1].pauseMillis)
        assertEquals("Go.", planned[2].text)
        assertEquals(0L, planned[2].pauseMillis)
    }

    @Test
    fun `ssml pause sentence carries the break position`() {
        val planned = SynthesisPlanner.planSsml("Hi.<break time=\"100ms\"/>Bye.")

        assertEquals(3, planned.size)
        assertEquals(MarkerRange(3, 0), planned[1].range)
        assertEquals(100L, planned[1].pauseMillis)
    }
}
