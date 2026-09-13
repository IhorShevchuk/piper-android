package dev.ihorshevchuk.piper.utils

/**
 * Port of PiperSentencesExtractor (piper-objc).
 *
 * Splits text into sentences, then recursively subdivides long sentences so
 * every chunk stays within 22 words / 160 characters (the limits piper-objc
 * enforces before handing a sentence to the synthesizer).
 *
 * Sentence boundaries: '.', '!' and '?' plus the CJK equivalents '。' '？'
 * '！' (on Apple platforms NLTokenizer finds those; Android has no
 * NLTokenizer, so the naive path covers them explicitly). Runs of
 * terminators ("...", "?!", "!!") stay attached to the sentence they end.
 * A trailing fragment without a terminator is kept as its own sentence.
 * Each sentence is normalized (trimmed, internal whitespace collapsed).
 *
 * Apple parity note: when the input holds no words at all (only punctuation
 * and whitespace), NLTokenizer yields no sentence tokens and the extractor
 * falls back to the whole trimmed text as a single chunk; that behavior is
 * reproduced here.
 */
object SentenceSplitter {
    private val terminators = setOf(
        '.', '!', '?',           // Latin
        '。', '？', '！',          // CJK (U+3002, U+FF1F, U+FF01)
        '؟',                     // Arabic question mark (U+061F)
        '۔',                     // Urdu full stop (U+06D4)
        '।'                      // Devanagari danda (U+0964)
    )
    private val softPunctuation = setOf(',', ';', ':', '—', '–', '(', ')', '[', ']', '{', '}')
    private val whitespace = Regex("\\s+")

    private const val maxWordsPerChunk = 22
    private const val maxCharactersPerChunk = 160
    private const val maxRecursionDepth = 20

    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val sentences = naiveSplit(text)
        if (sentences.isEmpty()) return emptyList()
        if (sentences.all { s -> s.all { c -> !c.isLetterOrDigit() } }) {
            // No words anywhere: keep the whole input as one chunk (Apple fallback).
            return listOf(normalize(text))
        }
        val out = mutableListOf<String>()
        for (sentence in sentences) {
            val normalized = normalize(sentence)
            if (normalized.isNotEmpty()) out += processSentence(normalized, 0)
        }
        return out
    }

    /**
     * Splits on terminator runs, keeping each run attached to the sentence
     * it ends (Swift's Linux-fallback naiveSentenceSplit, with runs grouped).
     */
    private fun naiveSplit(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            if (c in terminators) {
                while (i + 1 < text.length && text[i + 1] in terminators) {
                    i++
                    current.append(text[i])
                }
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) out.add(sentence)
                current.clear()
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Recursive subdivision of over-long sentences (Swift processSentence). */
    private fun processSentence(sentence: String, recursionDepth: Int): List<String> {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (recursionDepth > maxRecursionDepth) return listOf(trimmed)
        if (isValidChunk(trimmed)) return listOf(trimmed)

        val splitIndex = findBestSoftSplitIndex(trimmed)
        if (splitIndex != null) {
            val left = trimmed.substring(0, splitIndex)
            val right = trimmed.substring(splitIndex)
            return processSentence(left, recursionDepth + 1) +
                processSentence(right, recursionDepth + 1)
        }
        return forceSplitByMiddleWord(trimmed, recursionDepth + 1)
    }

    /**
     * Index just past the soft-punctuation mark nearest the center, so the
     * mark stays attached to the left chunk (Swift findBestSoftSplitIndex).
     */
    private fun findBestSoftSplitIndex(text: String): Int? {
        val midpoint = text.length / 2
        var best: Int? = null
        var smallestDistance = Int.MAX_VALUE
        for (i in text.indices) {
            if (text[i] in softPunctuation) {
                val distance = kotlin.math.abs(i - midpoint)
                if (distance < smallestDistance) {
                    smallestDistance = distance
                    best = i + 1
                }
            }
        }
        return best
    }

    /** Splits between the middle two words (Swift forceSplitByMiddleWord). */
    private fun forceSplitByMiddleWord(text: String, recursionDepth: Int): List<String> {
        val words = text.split(whitespace).filter { it.isNotEmpty() }
        if (words.size <= 1) return listOf(text)
        val mid = words.size / 2
        val left = words.subList(0, mid).joinToString(" ")
        val right = words.subList(mid, words.size).joinToString(" ")
        return processSentence(left, recursionDepth) + processSentence(right, recursionDepth)
    }

    private fun isValidChunk(text: String): Boolean {
        if (text.length > maxCharactersPerChunk) return false
        return text.split(whitespace).count { it.isNotEmpty() } <= maxWordsPerChunk
    }

    private fun normalize(text: String): String =
        text.trim().replace(whitespace, " ")
}
