package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SsmlParser], the XmlPullParser-based SSML parser.
 *
 * Behavior follows the SSML spec, not any XML parser's quirks:
 * - 1.0 is the default rate everywhere (no AVSpeechUtterance-legacy 0.5 root).
 * - `<prosody rate>`: percent ("80%"), float multiplier ("1.25"), named
 *   rates (x-slow 0.5, slow 0.75, medium 1.0, fast 1.5, x-fast 2.0), and
 *   relative percent ("+50%"/"-50%") adjusting the parent rate. Missing or
 *   unparseable rates inherit the parent rate.
 * - `<break>`: `time` ("500ms", "2s") wins over `strength`
 *   (none/x-weak/weak/medium/strong/x-strong); a bare `<break/>` pauses a
 *   medium 500 ms. Breaks become [SsmlFragment]s with empty text and
 *   [SsmlFragment.pauseMillis] > 0 so the engine renders real silence.
 * - Unknown elements (`voice`, `say-as`, `sub`, `emphasis`, ...) are
 *   transparent containers: their text is kept, the tag ignored.
 * - Malformed XML never drops user text: the raw input is spoken as one
 *   1.0-rate fragment.
 */
class SsmlParserTest {

    private fun parse(ssml: String): List<SsmlFragment> = SsmlParser.parse(ssml)

    @Test
    fun `plain text without tags is a single 1_0 fragment`() {
        val nodes = parse("Hello world!")

        assertEquals(1, nodes.size)
        assertEquals("Hello world!", nodes[0].text)
        assertEquals(1.0f, nodes[0].rate, 0.0001f)
        assertEquals(0, nodes[0].range.first)
        assertEquals(12, nodes[0].range.last + 1)
        assertEquals(0L, nodes[0].pauseMillis)
    }

    @Test
    fun `empty and blank input parse to empty list`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   ").isEmpty())
    }

    @Test
    fun `speak root is transparent`() {
        val nodes = parse("<speak>Hello</speak>")

        assertEquals(1, nodes.size)
        assertEquals("Hello", nodes[0].text)
        assertEquals(1.0f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `prosody percent rate`() {
        val nodes = parse("<prosody rate=\"150%\">Fast</prosody>")

        assertEquals(1, nodes.size)
        assertEquals("Fast", nodes[0].text)
        assertEquals(1.5f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `prosody float multiplier rates`() {
        assertEquals(1.0f, parse("<prosody rate=\"1.0\">Normal</prosody>")[0].rate, 0.001f)
        assertEquals(0.5f, parse("<prosody rate=\"0.5\">Half</prosody>")[0].rate, 0.001f)
        assertEquals(2.0f, parse("<prosody rate=\"2.0\">Double</prosody>")[0].rate, 0.001f)
        assertEquals(1.5f, parse("<prosody rate=\"1.5\">Fast</prosody>")[0].rate, 0.001f)
    }

    @Test
    fun `prosody named rates`() {
        assertEquals(0.5f, parse("<prosody rate=\"x-slow\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(0.75f, parse("<prosody rate=\"slow\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(1.0f, parse("<prosody rate=\"medium\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(1.5f, parse("<prosody rate=\"fast\">a</prosody>")[0].rate, 0.0001f)
        assertEquals(2.0f, parse("<prosody rate=\"x-fast\">a</prosody>")[0].rate, 0.0001f)
    }

    @Test
    fun `nested prosody innermost wins`() {
        val nodes = parse("<prosody rate=\"200%\">Very <prosody rate=\"50%\">slow</prosody> fast</prosody>")

        assertEquals(3, nodes.size)
        assertEquals("Very ", nodes[0].text)
        assertEquals(2.0f, nodes[0].rate, 0.01f)
        assertEquals("slow", nodes[1].text)
        assertEquals(0.5f, nodes[1].rate, 0.01f)
        assertEquals(" fast", nodes[2].text)
        assertEquals(2.0f, nodes[2].rate, 0.01f)
    }

    @Test
    fun `untagged text defaults to rate 1_0`() {
        val nodes = parse("<speak>L’élève <prosody rate=\"100%\">écoute</prosody> bien.</speak>")

        assertEquals(3, nodes.size)
        assertEquals("L’élève ", nodes[0].text)
        assertEquals(1.0f, nodes[0].rate, 0.01f)
        assertEquals("écoute", nodes[1].text)
        assertEquals(1.0f, nodes[1].rate, 0.01f)
        assertEquals(" bien.", nodes[2].text)
        assertEquals(1.0f, nodes[2].rate, 0.01f)
    }

    @Test
    fun `prosody without rate inherits parent`() {
        val nodes = parse("<prosody rate=\"50%\">a<prosody>b</prosody>c</prosody>")

        assertEquals(3, nodes.size)
        assertEquals(0.5f, nodes[1].rate, 0.0001f)
    }

    @Test
    fun `unparseable rate inherits parent`() {
        val nodes = parse("<prosody rate=\"abc\">text</prosody>")

        assertEquals(1, nodes.size)
        assertEquals("text", nodes[0].text)
        assertEquals(1.0f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `relative percent rate adjusts parent`() {
        val nodes = parse("<prosody rate=\"200%\"><prosody rate=\"+50%\">x</prosody></prosody>")

        assertEquals(1, nodes.size)
        assertEquals(3.0f, nodes[0].rate, 0.0001f)
    }

    @Test
    fun `break with time in milliseconds`() {
        val nodes = parse("A<break time=\"500ms\"/>B")

        assertEquals(3, nodes.size)
        assertEquals("A", nodes[0].text)
        assertEquals("", nodes[1].text)
        assertEquals(500L, nodes[1].pauseMillis)
        assertEquals("B", nodes[2].text)
    }

    @Test
    fun `break with time in seconds`() {
        val nodes = parse("A<break time=\"2s\"/>B")

        assertEquals(2000L, nodes[1].pauseMillis)
    }

    @Test
    fun `break strength maps to a pause`() {
        assertEquals(150L, parse("A<break strength=\"x-weak\"/>B")[1].pauseMillis)
        assertEquals(300L, parse("A<break strength=\"weak\"/>B")[1].pauseMillis)
        assertEquals(500L, parse("A<break strength=\"medium\"/>B")[1].pauseMillis)
        assertEquals(1000L, parse("A<break strength=\"strong\"/>B")[1].pauseMillis)
        assertEquals(1500L, parse("A<break strength=\"x-strong\"/>B")[1].pauseMillis)
    }

    @Test
    fun `break time wins over strength`() {
        val nodes = parse("A<break time=\"250ms\" strength=\"x-strong\"/>B")

        assertEquals(250L, nodes[1].pauseMillis)
    }

    @Test
    fun `bare break defaults to a medium pause`() {
        val nodes = parse("A<break/>B")

        assertEquals(500L, nodes[1].pauseMillis)
    }

    @Test
    fun `break with strength none produces no pause fragment`() {
        val nodes = parse("A<break strength=\"none\"/>B")

        assertEquals(2, nodes.size)
        assertTrue(nodes.none { it.pauseMillis > 0 })
    }

    @Test
    fun `break keeps the surrounding prosody rate on neighbors`() {
        val nodes = parse("<prosody rate=\"2\">A<break time=\"100ms\"/>B</prosody>")

        assertEquals(3, nodes.size)
        assertEquals(2.0f, nodes[0].rate, 0.0001f)
        assertEquals(100L, nodes[1].pauseMillis)
        assertEquals(2.0f, nodes[2].rate, 0.0001f)
    }

    @Test
    fun `break fragment has an empty range at the break position`() {
        val nodes = parse("A<break time=\"100ms\"/>B")

        assertEquals(1, nodes[1].range.first)
        assertTrue(nodes[1].range.isEmpty())
    }

    @Test
    fun `unsupported elements are transparent containers`() {
        val nodes = parse("<speak><voice name=\"en_US\">Hello <emphasis>world</emphasis></voice></speak>")

        val joined = nodes.joinToString("") { it.text }
        assertTrue(joined.contains("Hello"))
        assertTrue(joined.contains("world"))
    }

    @Test
    fun `say-as and sub keep inner text`() {
        val nodes = parse("<speak><say-as interpret-as=\"digits\">123</say-as> <sub alias=\"World Wide Web\">WWW</sub></speak>")

        val joined = nodes.joinToString("") { it.text }
        assertTrue(joined.contains("123"))
        assertTrue(joined.contains("WWW"))
    }

    @Test
    fun `entities are decoded`() {
        val nodes = parse("<speak>Fish &amp; Chips are &lt; \$10 &quot;Special&quot;</speak>")

        assertEquals(1, nodes.size)
        assertEquals("Fish & Chips are < \$10 \"Special\"", nodes[0].text)
    }

    @Test
    fun `comments are skipped`() {
        val nodes = parse("<speak>Visible <!-- This is a comment --> text</speak>")

        assertEquals(1, nodes.size)
        assertTrue(nodes[0].text.contains("Visible"))
        assertTrue(nodes[0].text.contains("text"))
        assertTrue(!nodes[0].text.contains("comment"))
    }

    @Test
    fun `cdata is kept as text`() {
        val nodes = parse("<speak><![CDATA[<not a tag>]]></speak>")

        assertEquals(1, nodes.size)
        assertEquals("<not a tag>", nodes[0].text)
    }

    @Test
    fun `malformed xml falls back to the raw input`() {
        val n1 = parse("<prosody>")
        assertEquals(1, n1.size)
        assertEquals("<prosody>", n1[0].text)
        assertEquals(1.0f, n1[0].rate, 0.0001f)

        val raw = "<speak>unclosed"
        val n2 = parse(raw)
        assertEquals(1, n2.size)
        assertEquals(raw, n2[0].text)
    }

    @Test
    fun `mismatched tags fall back to the raw input`() {
        val raw = "<prosody>text</speak>"
        val nodes = parse(raw)

        assertEquals(1, nodes.size)
        assertEquals(raw, nodes[0].text)
    }

    @Test
    fun `text outside speak tags is kept`() {
        val nodes = parse("Before <speak>Inside</speak> After")

        val allText = nodes.joinToString("") { it.text }
        assertTrue(allText.contains("Before"))
        assertTrue(allText.contains("Inside"))
        assertTrue(allText.contains("After"))
    }

    @Test
    fun `single quotes and spacing in attributes`() {
        val nodes = parse("<prosody   rate = ' 50% ' >Slow</prosody>")

        assertEquals(1, nodes.size)
        assertEquals(0.5f, nodes[0].rate, 0.0001f)
        assertEquals("Slow", nodes[0].text)
    }

    @Test
    fun `fragment ranges cover the concatenated plain text`() {
        val nodes = parse("<speak><prosody rate=\"50%\">Slow</prosody> and <prosody rate=\"200%\">fast</prosody>.</speak>")
        val texts = nodes.filter { it.pauseMillis == 0L }
        val plain = texts.joinToString("") { it.text }

        var offset = 0
        for (n in texts) {
            assertEquals(n.text, plain.substring(n.range.first, n.range.last + 1))
            assertEquals(offset, n.range.first)
            offset = n.range.last + 1
        }
        assertEquals(plain.length, offset)
    }

    @Test
    fun `multiple speed changes in sequence`() {
        val nodes = parse("<speak><prosody rate='50%'>Slow</prosody><prosody rate='200%'>Fast</prosody></speak>")

        assertEquals(2, nodes.size)
        assertEquals(0.5f, nodes[0].rate, 0.0001f)
        assertEquals(2.0f, nodes[1].rate, 0.0001f)
    }
}
