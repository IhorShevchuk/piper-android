package dev.ihorshevchuk.piper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of PiperSentencesExtractorTests (piper-objc).
 *
 * Two Apple-only behaviors are adapted for Android (no NaturalLanguage):
 * - Japanese/CJK sentences split on 。？！ terminators, emulating what
 *   NLTokenizer does on Apple platforms.
 * - An all-punctuation input stays a single chunk (on Apple, NLTokenizer
 *   finds no sentence tokens and the extractor falls back to the whole text).
 * Laziness is not ported: [SentenceSplitter.split] returns a List, which
 * [SynthesisPlanner] consumes fully anyway.
 */
class SentenceSplitterTest {

    // MARK: - Basic Sentence Extraction

    @Test
    fun `extracts basic sentences`() {
        val result = SentenceSplitter.split("Hello world. This is a test! How are you?")

        assertEquals(3, result.size)
        assertEquals("Hello world.", result[0])
        assertEquals("This is a test!", result[1])
        assertEquals("How are you?", result[2])
    }

    // MARK: - Empty Input

    @Test
    fun `handles empty input`() {
        assertTrue(SentenceSplitter.split("").isEmpty())
    }

    @Test
    fun `handles whitespace input`() {
        assertTrue(SentenceSplitter.split("   \n\t   ").isEmpty())
    }

    // MARK: - Comma Chunking

    @Test
    fun `splits long sentence by comma`() {
        val text = """
            Yesterday, after finishing up an incredibly long day at work, I slowly walked over to the neighborhood grocery store, purchased a fresh bottle of milk, and quickly drove back to my house.
            """.trimIndent()

        val result = SentenceSplitter.split(text)

        assertTrue(result.size > 1)
        // Chunks split at soft bounds without dropping the punctuation character.
        assertTrue(result.any { it.endsWith(",") })
    }

    // MARK: - Word Limit Chunking

    @Test
    fun `splits very long chunk by word limit`() {
        val text = """
            This is a very long sentence that should be automatically split into multiple smaller chunks because it exceeds the configured word limit for speech synthesis processing.
            """.trimIndent()

        val result = SentenceSplitter.split(text)

        assertTrue(result.size >= 2)
        for (chunk in result) {
            assertTrue(chunk.split(Regex("\\s+")).size <= 22)
        }
    }

    // MARK: - Character Limit Chunking

    @Test
    fun `splits very long chunk by character limit`() {
        val text = "superlongword ".repeat(30)

        val result = SentenceSplitter.split(text)

        assertTrue(result.size >= 2)
        for (chunk in result) {
            assertTrue(chunk.length <= 160)
        }
    }

    // MARK: - Unicode / Multilingual

    @Test
    fun `handles Ukrainian text`() {
        val result = SentenceSplitter.split("Привіт світе. Як справи? Сьогодні гарна погода.")

        assertEquals(3, result.size)
        assertEquals("Привіт світе.", result[0])
        assertEquals("Як справи?", result[1])
        assertEquals("Сьогодні гарна погода.", result[2])
    }

    @Test
    fun `handles Japanese text`() {
        val result = SentenceSplitter.split("こんにちは世界。今日は元気ですか？これはテストです。")

        assertTrue(result.size >= 2)
    }

    @Test
    fun `handles mixed language text`() {
        val result = SentenceSplitter.split("Hello world. Привіт світе. こんにちは。")

        assertEquals(3, result.size)
        assertEquals("Hello world.", result[0])
        assertEquals("Привіт світе.", result[1])
        assertEquals("こんにちは。", result[2])
    }

    // MARK: - Whitespace Normalization

    @Test
    fun `normalizes whitespace`() {
        val text = "Hello     world.\n\n\nThis     is     a     test."

        val result = SentenceSplitter.split(text)

        assertEquals(2, result.size)
        assertEquals("Hello world.", result[0])
        assertEquals("This is a test.", result[1])
    }

    // MARK: - No Punctuation

    @Test
    fun `handles text without punctuation`() {
        val result = SentenceSplitter.split(
            "this is text without punctuation and it should still produce output"
        )

        assertTrue(result.isNotEmpty())
    }

    // MARK: - Emojis

    @Test
    fun `handles emoji text`() {
        val result = SentenceSplitter.split("Hello 👋 world 🌍. This is awesome 🚀!")

        assertEquals(2, result.size)
        assertEquals("Hello 👋 world 🌍.", result[0])
        assertEquals("This is awesome 🚀!", result[1])
    }

    // MARK: - Newlines

    @Test
    fun `handles newlines`() {
        val result = SentenceSplitter.split("First line.\n\nSecond line.\n\nThird line.")

        assertEquals(3, result.size)
        assertEquals("First line.", result[0])
        assertEquals("Second line.", result[1])
        assertEquals("Third line.", result[2])
    }

    // MARK: - Word Split Fallback

    @Test
    fun `forces word split when no punctuation found`() {
        val text = "This is an extremely massive run on block of words without any punctuation whatsoever " +
            "that will definitely trigger the fallback middle word division routine because it passes limits"

        val result = SentenceSplitter.split(text)

        assertTrue(result.size >= 2)
        for (chunk in result) {
            assertTrue(chunk.length <= 160)
        }
    }

    // MARK: - Sentence Order

    @Test
    fun `preserves order with multiple punctuations and splits`() {
        val text = "First sentence, with a comma, and more; second sentence: with colons, then — a dash! Finally? An end."
        val result = SentenceSplitter.split(text)

        assertTrue(result.first().startsWith("First sentence"))
        assertTrue(result.last().startsWith("An end"))
        val joined = result.joinToString(" ")
        assertTrue(joined.contains("First sentence"))
        assertTrue(joined.contains("second sentence"))
        assertTrue(joined.contains("Finally"))
        assertTrue(joined.indexOf("First sentence") < joined.indexOf("second sentence"))
        assertTrue(joined.indexOf("second sentence") < joined.indexOf("Finally"))
    }

    @Test
    fun `preserves order with soft split at midpoint`() {
        val text = "Alpha, Beta, Gamma, Delta, Epsilon, Zeta, Eta, Theta, Iota, Kappa, Lambda, Mu, Nu, Xi, " +
            "Omicron, Pi, Rho, Sigma, Tau, Upsilon, Phi, Chi, Psi, Omega."
        val result = SentenceSplitter.split(text)

        assertTrue(result.first().contains("Alpha"))
        assertTrue(result.last().endsWith("Omega."))
        val allJoined = result.joinToString(" ")
        assertTrue(allJoined.indexOf("Alpha") < allJoined.indexOf("Omega"))
    }

    @Test
    fun `preserves order with emojis and unicode`() {
        val text = "😀 Alpha βeta, 🚀 Gamma — Delta 🎉. 🐍 Python is fun! 🌍"
        val result = SentenceSplitter.split(text)

        assertTrue(result.size >= 2)
        val resultString = result.joinToString(" ")
        assertTrue(resultString.contains("😀 Alpha βeta"))
        assertTrue(resultString.contains("🚀 Gamma"))
        assertTrue(resultString.contains("Delta 🎉."))
        assertTrue(resultString.indexOf("😀") < resultString.indexOf("🚀"))
        assertTrue(resultString.indexOf("🚀") < resultString.indexOf("🎉"))
        assertTrue(resultString.indexOf("🐍") < resultString.indexOf("🌍"))
    }

    @Test
    fun `order preserved with word limit chunking`() {
        val text = (1..50).joinToString(" ") { "word$it" }
        val result = SentenceSplitter.split(text)

        val reconstructed = result.joinToString(" ")
        assertEquals(text, reconstructed)
    }

    // MARK: - Right-to-Left (RTL) and Mixed Directionality

    @Test
    fun `handles basic Arabic sentences`() {
        val result = SentenceSplitter.split("مرحبا بالعالم. كيف حالك؟ هذا اختبار!")

        assertEquals(3, result.size)
        assertEquals("مرحبا بالعالم.", result[0])
        assertEquals("كيف حالك؟", result[1])
        assertEquals("هذا اختبار!", result[2])
    }

    @Test
    fun `handles basic Hebrew sentences`() {
        val result = SentenceSplitter.split("שלום עולם. מה שלומך? זה מבחן.")

        assertEquals(3, result.size)
        assertEquals("שלום עולם.", result[0])
        assertEquals("מה שלומך?", result[1])
        assertEquals("זה מבחן.", result[2])
    }

    @Test
    fun `handles mixed Arabic and English`() {
        val result = SentenceSplitter.split("مرحبا. Hello. كيف الحال؟ How are you?")

        assertEquals(4, result.size)
        assertEquals("مرحبا.", result[0])
        assertEquals("Hello.", result[1])
        assertEquals("كيف الحال؟", result[2])
        assertEquals("How are you?", result[3])
    }

    @Test
    fun `handles RTL emojis and LTR`() {
        val result = SentenceSplitter.split("😊 שלום עולם! Hello world! 🌍")

        assertEquals(3, result.size)
        assertTrue(result[0].contains("שלום עולם"))
        assertTrue(result[1].contains("Hello world"))
        assertTrue(result[0].contains("😊") || result[1].contains("🌍"))
    }

    @Test
    fun `prevents stack overflow on pathological input`() {
        val text = Array(10000) { "word" }.joinToString(" ")
        val result = SentenceSplitter.split(text)

        assertTrue(result.isNotEmpty())
        var totalWordCount = 0
        for (chunk in result) {
            val words = chunk.split(Regex("\\s+"))
            totalWordCount += words.size
            assertTrue(words.size <= 22)
            assertTrue(chunk.length <= 160)
        }
        assertEquals(10000, totalWordCount)
    }

    // MARK: - Iteration Behavior

    @Test
    fun `verify manual iteration`() {
        val iterator = SentenceSplitter.split("Sentence one. Sentence two. Sentence three.").iterator()

        assertEquals("Sentence one.", iterator.next())
        assertEquals("Sentence two.", iterator.next())
        assertEquals("Sentence three.", iterator.next())
        assertEquals(false, iterator.hasNext())
    }

    @Test
    fun `handles large input`() {
        val text = "This is a sentence. ".repeat(1000)
        val result = SentenceSplitter.split(text)

        assertEquals(1000, result.size)
        assertEquals("This is a sentence.", result[0])
        assertEquals("This is a sentence.", result[1])
    }

    @Test
    fun `handles only punctuation and whitespace`() {
        val text = "... !!! ??? ,,,   "
        val result = SentenceSplitter.split(text)

        // Must not hang; on Apple the whole input stays a single chunk.
        assertEquals(text.trim(), result.first())
    }
}
