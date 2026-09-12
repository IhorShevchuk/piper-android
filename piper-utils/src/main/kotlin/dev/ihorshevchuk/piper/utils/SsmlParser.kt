package dev.ihorshevchuk.piper.utils

/**
 * A run of plain text with a uniform speaking rate, plus its range in the
 * concatenated plain-text output.
 *
 * @param rate speaking-rate multiplier; 1.0 is normal. Convert to a Piper
 * lengthScale downstream (see SpeedCurve.lengthScaleForRate).
 */
data class SsmlFragment(
    val text: String,
    val rate: Float,
    val range: IntRange
)

/**
 * Faithful simple port of SSMLParser / SSMLNode (piper-objc).
 *
 * Understands <speak>, <prosody rate="..."> (percent like "80%", plain float
 * like "1.25", or the SSML named rates x-slow/slow/medium/fast/x-fast),
 * self-closing <break .../> (fragment boundary), and strips every other tag.
 * A hand-rolled scanner keeps this module dependency-free and JVM-pure.
 */
object SsmlParser {
    private val tagRegex = Regex("<[^>]+>")
    private val rateAttrRegex = Regex("""rate\s*=\s*["']([^"']+)["']""")
    private val namedRates = mapOf(
        "x-slow" to 0.5f,
        "slow" to 0.75f,
        "medium" to 1.0f,
        "fast" to 1.25f,
        "x-fast" to 1.5f
    )

    fun parse(xml: String): List<SsmlFragment> {
        val fragments = mutableListOf<SsmlFragment>()
        val plain = StringBuilder()
        val rateStack = ArrayDeque(listOf(1.0f))
        val current = StringBuilder()

        fun flush() {
            if (current.isEmpty()) return
            val text = current.toString()
            val start = plain.length
            plain.append(text)
            fragments.add(
                SsmlFragment(
                    text = text,
                    rate = rateStack.last(),
                    range = start until plain.length
                )
            )
            current.clear()
        }

        var pos = 0
        for (match in tagRegex.findAll(xml)) {
            current.append(xml.substring(pos, match.range.first))
            pos = match.range.last + 1
            val tag = match.value.trim('<', '>', ' ', '/').lowercase()
            val name = tag.substringBefore(' ')
            when {
                name == "prosody" && !match.value.endsWith("/>") -> {
                    flush()
                    rateStack.addLast(parseRate(rateAttrRegex.find(match.value)?.groupValues?.get(1)))
                }
                name == "/prosody" -> {
                    flush()
                    if (rateStack.size > 1) rateStack.removeLast()
                }
                name == "break" -> flush() // boundary, like a sentence split
                // <speak>, </speak> and everything else: strip, keep text flowing
            }
        }
        current.append(xml.substring(pos))
        flush()
        return fragments
    }

    private fun parseRate(raw: String?): Float {
        if (raw.isNullOrBlank()) return 1.0f
        val s = raw.trim().lowercase()
        namedRates[s]?.let { return it }
        if (s.endsWith("%")) {
            return s.dropLast(1).toFloatOrNull()?.div(100f) ?: 1.0f
        }
        return s.toFloatOrNull() ?: 1.0f
    }
}
