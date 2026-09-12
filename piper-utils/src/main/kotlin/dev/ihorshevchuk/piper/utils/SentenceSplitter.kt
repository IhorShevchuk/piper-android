package dev.ihorshevchuk.piper.utils

/**
 * Port of PiperSentencesExtractor (piper-objc).
 *
 * Splits text into sentences on '.', '!' and '?' boundaries. Runs of
 * terminators ("...", "?!", "!!") stay attached to the sentence they end.
 * A trailing fragment without a terminator is kept as its own sentence.
 */
object SentenceSplitter {
    private val terminators = setOf('.', '!', '?')

    fun split(text: String): List<String> {
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
}
