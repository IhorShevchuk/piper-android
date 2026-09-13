package dev.ihorshevchuk.piper.utils

/**
 * A run of plain text with a uniform speaking rate, plus its range in the
 * concatenated plain-text output.
 *
 * @param rate speaking rate from the SSML: 0.5 is normal (the
 * AVSpeechUtterance-legacy normal Swift's parser starts from), 1.0 is the
 * normal speed multiplier. Convert to a Piper lengthScale downstream via
 * SpeedCurve.lengthScaleForRate, which maps both to 1.0 exactly like Swift's
 * getOptions.
 */
data class SsmlFragment(
    val text: String,
    val rate: Float,
    val range: IntRange
)

/**
 * Faithful port of SSMLParser / SSMLNode (piper-objc).
 *
 * A hand-rolled scanner (no XML dependency, JVM-pure) mirrors NSXMLParser's
 * observable behavior:
 * - <speak> and any other tag is stripped; text before/inside/after is kept.
 * - <prosody rate="..."> pushes a rate context: percent ("80%"), plain float
 *   ("1.25"), or the SSML named rates (x-slow 0.5, slow 0.75, medium 1.0,
 *   fast 1.5, x-fast 2.0). A <prosody> without a rate inherits the parent
 *   rate; an unparseable rate falls back to 0.5 (Swift parseRate default).
 * - Text outside any tag, and text with no tags at all, carries the root
 *   rate 0.5 - except when the input has no tags whatsoever, which
 *   NSXMLParser rejects: then the whole input is one fragment at 1.0.
 * - Flushing on every tag boundary: nested/unsupported tags split fragments,
 *   whitespace-only runs are dropped.
 * - XML entities (&amp; &lt; &gt; &quot; &apos;, numeric) are decoded.
 * - Comments are skipped; CDATA content is kept as text.
 * - Malformed input (unclosed/mismatched tags, unknown entities, bare &)
 *   falls back to the whole input as a single 1.0 fragment, like Swift's
 *   parser.parse() == false path.
 */
object SsmlParser {
    private val rateAttrRegex = Regex("""rate\s*=\s*["']([^"']+)["']""")
    private val entityRegex = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);")
    private val namedRates = mapOf(
        "x-slow" to 0.5f,
        "slow" to 0.75f,
        "medium" to 1.0f,
        "fast" to 1.5f,
        "x-fast" to 2.0f
    )

    /** Root rate: AVSpeechUtterance normal, mirroring Swift's SSMLContext. */
    private const val ROOT_RATE = 0.5f

    private class Ctx(val name: String, val rate: Float) {
        val text = StringBuilder()
    }

    fun parse(xml: String): List<SsmlFragment> {
        if (xml.isBlank()) return emptyList()

        val fragments = mutableListOf<SsmlFragment>()
        val plain = StringBuilder()
        val stack = ArrayDeque<Ctx>()
        stack.addLast(Ctx("", ROOT_RATE))
        var sawTag = false
        var malformed = false

        fun flush() {
            val ctx = stack.last()
            if (ctx.text.isBlank()) {
                ctx.text.clear()
                return
            }
            val text = decodeEntities(ctx.text.toString())
            ctx.text.clear()
            val start = plain.length
            plain.append(text)
            fragments.add(SsmlFragment(text = text, rate = ctx.rate, range = start until plain.length))
        }

        fun handleTag(raw: String) {
            val t = raw.trim()
            if (t.startsWith("?") || t.startsWith("!")) return // PI / DOCTYPE: strip
            val selfClosing = t.endsWith("/")
            val body = if (selfClosing) t.dropLast(1).trim() else t
            if (body.startsWith("/")) {
                flush()
                val name = body.drop(1).substringBefore(' ').trim().lowercase()
                if (stack.size <= 1 || stack.last().name != name) {
                    malformed = true
                    return
                }
                stack.removeLast()
            } else {
                val name = body.substringBefore(' ').trim().lowercase()
                flush()
                if (selfClosing) return // e.g. <break/>: boundary only
                if (name.isEmpty()) {
                    malformed = true
                    return
                }
                val rate = if (name == "prosody") {
                    val attr = rateAttrRegex.find(raw)?.groupValues?.get(1)
                    if (attr == null) stack.last().rate else parseRate(attr)
                } else {
                    stack.last().rate
                }
                stack.addLast(Ctx(name, rate))
            }
        }

        var i = 0
        while (i < xml.length && !malformed) {
            when {
                xml.startsWith("<!--", i) -> {
                    sawTag = true
                    val end = xml.indexOf("-->", i + 4)
                    if (end < 0) malformed = true else i = end + 3
                }
                xml.startsWith("<![CDATA[", i) -> {
                    sawTag = true
                    val end = xml.indexOf("]]>", i + 9)
                    if (end < 0) {
                        malformed = true
                    } else {
                        stack.last().text.append(xml.substring(i + 9, end))
                        i = end + 3
                    }
                }
                xml[i] == '<' -> {
                    sawTag = true
                    val end = xml.indexOf('>', i + 1)
                    if (end < 0) {
                        malformed = true
                    } else {
                        handleTag(xml.substring(i + 1, end))
                        i = end + 1
                    }
                }
                else -> {
                    val next = xml.indexOf('<', i)
                    val run = if (next < 0) xml.substring(i) else xml.substring(i, next)
                    if (hasBadEntity(run)) {
                        malformed = true
                    } else {
                        stack.last().text.append(run)
                        i = if (next < 0) xml.length else next
                    }
                }
            }
        }

        if (!malformed) flush()
        // Swift: parser.parse() == false, or no tags at all (NSXMLParser
        // rejects tag-free input) -> whole input as one 1.0 node.
        if (malformed || stack.size != 1 || !sawTag) {
            return listOf(SsmlFragment(text = xml, rate = 1.0f, range = 0 until xml.length))
        }
        return fragments
    }

    /**
     * Port of Swift's parseRate: percent, float multiplier, named rates;
     * anything unparseable -> 0.5 (Swift's default, the AV normal).
     */
    private fun parseRate(raw: String): Float {
        val s = raw.trim().lowercase()
        namedRates[s]?.let { return it }
        if (s.endsWith("%")) {
            return s.dropLast(1).trim().toFloatOrNull()?.div(100f) ?: 0.5f
        }
        return s.toFloatOrNull() ?: 0.5f
    }

    private fun decodeEntities(text: String): String {
        // Unknown entities were already rejected by hasBadEntity; this only decodes.
        return entityRegex.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> { // numeric: #123 or #x1F
                    val digits = e.drop(1)
                    val code = if (digits.startsWith("x", ignoreCase = true)) {
                        digits.drop(1).toIntOrNull(16)
                    } else {
                        digits.toIntOrNull()
                    }
                    if (code != null && code > 0) String(Character.toChars(code)) else m.value
                }
            }
        }
    }

    /** Any & that does not start a known entity (NSXMLParser would fail). */
    private fun hasBadEntity(run: String): Boolean {
        var idx = run.indexOf('&')
        while (idx >= 0) {
            val m = entityRegex.find(run, idx)
            if (m == null || m.range.first != idx) return true
            idx = run.indexOf('&', idx + 1)
        }
        return false
    }
}
