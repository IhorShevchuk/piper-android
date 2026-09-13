package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of SSMLParserTests (piper-objc).
 *
 * SsmlFragment.rate carries the node's rate value: 0.5 is normal (the
 * AVSpeechUtterance legacy normal Swift's parser starts from), 1.0 is the
 * normal multiplier. Downstream, SpeedCurve.lengthScaleForRate maps both to
 * length_scale 1.0, exactly like Swift's getOptions.
 */
class SsmlParserTest {

    private fun parse(ssml: String): List<SsmlFragment> = SsmlParser.parse(ssml)

    @Test
    fun `parses plain text`() {
        val nodes = parse("Hello world!")

        assertEquals(1, nodes.size)
        assertEquals("Hello world!", nodes[0].text)
        assertEquals(1.0f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `parses prosody rate percent correctly`() {
        val nodes = parse("<prosody rate=\"150%\">Fast</prosody>")

        assertEquals(1, nodes.size)
        assertEquals("Fast", nodes[0].text)
        assertEquals(1.5f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `handles nested prosody`() {
        val nodes = parse("<prosody rate=\"200%\">Very <prosody rate=\"50%\">slow</prosody> fast</prosody>")

        assertEquals(3, nodes.size)
        assertEquals("Very", nodes[0].text.trim())
        assertEquals(2.0f, nodes[0].rate, 0.01f)
        assertEquals("slow", nodes[1].text.trim())
        assertEquals(0.5f, nodes[1].rate, 0.01f)
        assertEquals("fast", nodes[2].text.trim())
        assertEquals(2.0f, nodes[2].rate, 0.01f)
    }

    @Test
    fun `handles French prosody inside speak tag`() {
        val nodes = parse("<speak>L’élève <prosody rate=\"100%\">écoute</prosody> bien.</speak>")

        assertEquals(3, nodes.size)
        assertEquals("L’élève", nodes[0].text.trim())
        assertEquals(0.5f, nodes[0].rate, 0.01f)
        assertEquals("écoute", nodes[1].text.trim())
        assertEquals(1.0f, nodes[1].rate, 0.01f)
        assertEquals("bien.", nodes[2].text.trim())
        assertEquals(0.5f, nodes[2].rate, 0.01f)
    }

    @Test
    fun `returns empty for malformed or empty input`() {
        assertTrue(parse("").isEmpty())

        val n1 = parse("<prosody>")
        assertEquals(1, n1.size)
        assertEquals("<prosody>", n1[0].text)

        val n2 = parse("<prosody rate=\"abc\">text</prosody>")
        assertEquals(1, n2.size)
        assertEquals("text", n2[0].text)
        assertEquals(0.5f, n2[0].rate, 0.0001f)
    }

    @Test
    fun `handles XML entities correctly`() {
        val nodes = parse("<speak>Fish &amp; Chips are &lt; \$10 &quot;Special&quot;</speak>")

        assertEquals(1, nodes.size)
        assertEquals("Fish & Chips are < \$10 \"Special\"", nodes[0].text)
    }

    @Test
    fun `ignores XML comments`() {
        val nodes = parse("<speak>Visible <!-- This is a comment --> text</speak>")

        assertEquals(1, nodes.size)
        assertTrue(nodes[0].text.contains("Visible"))
        assertTrue(nodes[0].text.contains("text"))
        assertTrue(!nodes[0].text.contains("comment"))
    }

    @Test
    fun `handles multiple speed changes in sequence`() {
        val nodes = parse("<speak><prosody rate='50%'>Slow</prosody><prosody rate='200%'>Fast</prosody></speak>")

        assertEquals(2, nodes.size)
        assertEquals(0.5f, nodes[0].rate, 0.0001f)
        assertEquals(2.0f, nodes[1].rate, 0.0001f)
    }

    @Test
    fun `ignores unsupported tags but preserves inner text`() {
        val nodes = parse("<speak><voice name='en_US'>Hello <emphasis>world</emphasis></voice></speak>")

        assertTrue(nodes.size >= 2)
        val joined = nodes.joinToString("") { it.text }
        assertTrue(joined.contains("Hello"))
        assertTrue(joined.contains("world"))
    }

    @Test
    fun `handles text outside of speak tags`() {
        val nodes = parse("Before <speak>Inside</speak> After")

        val allText = nodes.joinToString(" ") { it.text }
        assertTrue(allText.contains("Before"))
        assertTrue(allText.contains("Inside"))
        assertTrue(allText.contains("After"))
    }

    @Test
    fun `parses attribute with single quotes and extra spaces`() {
        val nodes = parse("<prosody   rate = ' 50% ' >Slow</prosody>")

        assertEquals(1, nodes.size)
        assertEquals(0.5f, nodes[0].rate, 0.0001f)
        assertEquals("Slow", nodes[0].text)
    }

    @Test
    fun `parses float multiplier rates`() {
        assertEquals(1.0f, parse("<prosody rate=\"1.0\">Normal</prosody>")[0].rate, 0.001f)
        assertEquals(0.5f, parse("<prosody rate=\"0.5\">Half</prosody>")[0].rate, 0.001f)
        assertEquals(2.0f, parse("<prosody rate=\"2.0\">Double</prosody>")[0].rate, 0.001f)
        assertEquals(1.5f, parse("<prosody rate=\"1.5\">Fast</prosody>")[0].rate, 0.001f)
    }

    @Test
    fun `plain text fallback returns 1_0 multiplier normal`() {
        val nodes = parse("Hello world plain")

        assertEquals(1, nodes.size)
        assertEquals(1.0f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `prosody without rate inherits parent rate`() {
        val nodes = parse("<prosody rate=\"50%\">a<prosody>b</prosody>c</prosody>")

        assertEquals(3, nodes.size)
        assertEquals(0.5f, nodes[1].rate, 0.0001f)
    }

    @Test
    fun `named rates match Swift values`() {
        assertEquals(1.5f, parse("<prosody rate=\"fast\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(2.0f, parse("<prosody rate=\"x-fast\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(0.75f, parse("<prosody rate=\"slow\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(1.0f, parse("<prosody rate=\"medium\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(0.5f, parse("<prosody rate=\"x-slow\">a</prosody>")[0].rate, 0.0001f)
    }

    @Test
    fun `fragment ranges cover the concatenated plain text`() {
        val nodes = parse("<speak><prosody rate=\"50%\">Slow</prosody> and <prosody rate=\"200%\">fast</prosody>.</speak>")
        val plain = nodes.joinToString("") { it.text }

        var offset = 0
        for (n in nodes) {
            assertEquals(n.text, plain.substring(n.range.first, n.range.last + 1))
            assertEquals(offset, n.range.first)
            offset = n.range.last + 1
        }
        assertEquals(plain.length, offset)
    }
}
