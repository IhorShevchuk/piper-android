package dev.ihorshevchuk.piper.utils

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * One SSML fragment: a run of plain text spoken at a uniform rate, or a
 * pause from `<break>`.
 *
 * @param text the fragment's plain text; empty for `<break>` pauses.
 * @param rate the SSML prosody rate multiplier for this fragment; 1.0 is
 * normal speed.
 * @param range the fragment's range in the concatenated plain-text output.
 * Break fragments carry an empty range at the break's position.
 * @param pauseMillis silence to render for this fragment, from `<break>`;
 * 0 for text fragments.
 */
data class SsmlFragment(
    val text: String,
    val rate: Float,
    val range: IntRange,
    val pauseMillis: Long = 0L
)

/**
 * SSML parsing on top of [XmlPullParser] (xpp3), the community-standard
 * streaming XML parser available on both JVM and Android with no Android
 * framework dependency.
 *
 * Supported subset, per the SSML spec:
 * - `<speak>` root and any unknown element (`voice`, `say-as`, `sub`,
 *   `emphasis`, `phoneme`, ...) are transparent containers: inner text is
 *   kept, the tag itself ignored.
 * - `<prosody rate="...">` pushes a rate context: percent ("80%"), float
 *   multiplier ("1.25"), the named rates (x-slow 0.5, slow 0.75, medium 1.0,
 *   fast 1.5, x-fast 2.0), and relative percent ("+50%"/"-50%") adjusting the
 *   parent rate. A missing or unparseable rate inherits the parent rate;
 *   the root default is 1.0.
 * - `<break>`: `time` ("500ms", "2s", bare millis) wins over `strength`
 *   (none, x-weak 150, weak 300, medium 500, strong 1000, x-strong 1500 ms -
 *   our mapping, since the spec leaves durations to the processor). A bare
 *   `<break/>` pauses a medium 500 ms; `strength="none"` is a no-op.
 * - Comments and processing instructions are skipped; CDATA sections and
 *   XML entities are decoded by the parser.
 *
 * Leniency (this is a TTS engine, not a validator):
 * - Input without any markup is one 1.0-rate fragment.
 * - Input is wrapped in a synthetic `<speak>` root, so text outside tags
 *   and missing root elements still parse.
 * - Malformed XML never drops user text: the raw input is returned as one
 *   1.0-rate fragment.
 */
object SsmlParser {
    private val namedRates = mapOf(
        "x-slow" to 0.5f,
        "slow" to 0.75f,
        "medium" to 1.0f,
        "fast" to 1.5f,
        "x-fast" to 2.0f
    )

    /**
     * SSML `break` strength to pause. The spec defines the strengths but
     * leaves durations to the processor; these are ours.
     */
    private val breakStrengthMillis = mapOf(
        "none" to 0L,
        "x-weak" to 150L,
        "weak" to 300L,
        "medium" to 500L,
        "strong" to 1000L,
        "x-strong" to 1500L
    )

    private const val DEFAULT_BREAK_MILLIS = 500L

    fun parse(ssml: String): List<SsmlFragment> {
        if (ssml.isBlank()) return emptyList()
        if (!ssml.contains('<')) {
            return listOf(SsmlFragment(text = ssml, rate = 1.0f, range = 0 until ssml.length))
        }
        return try {
            parseDocument("<speak>$ssml</speak>")
        } catch (e: Exception) {
            // Malformed markup: speak the raw input rather than dropping text.
            listOf(SsmlFragment(text = ssml, rate = 1.0f, range = 0 until ssml.length))
        }
    }

    private fun parseDocument(xml: String): List<SsmlFragment> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        val fragments = mutableListOf<SsmlFragment>()
        val plain = StringBuilder()
        val rates = ArrayDeque<Float>()
        rates.addLast(1.0f)
        val text = StringBuilder()

        fun flushText() {
            if (text.isBlank()) {
                text.clear()
                return
            }
            val t = text.toString()
            text.clear()
            val start = plain.length
            plain.append(t)
            fragments.add(SsmlFragment(text = t, rate = rates.last(), range = start until plain.length))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    if (t.isNotBlank()) text.append(t)
                }
                XmlPullParser.START_TAG -> {
                    flushText()
                    val name = parser.name.lowercase()
                    if (name == "break") {
                        val millis = breakMillis(parser)
                        if (millis > 0) {
                            val pos = plain.length
                            fragments.add(
                                SsmlFragment(
                                    text = "",
                                    rate = rates.last(),
                                    range = pos until pos,
                                    pauseMillis = millis
                                )
                            )
                        }
                    }
                    rates.addLast(
                        if (name == "prosody") {
                            parseRate(parser.getAttributeValue(null, "rate"), rates.last())
                        } else {
                            rates.last()
                        }
                    )
                }
                XmlPullParser.END_TAG -> {
                    flushText()
                    if (rates.size > 1) rates.removeLast()
                }
            }
            event = parser.next()
        }
        flushText()
        return fragments
    }

    /**
     * Parses an SSML prosody rate: named rate, percent, float multiplier, or
     * relative percent ("+50%"/"-50%") adjusting [parentRate]. Anything
     * missing or unparseable inherits [parentRate] (the spec: invalid values
     * are ignored).
     */
    private fun parseRate(raw: String?, parentRate: Float): Float {
        if (raw == null) return parentRate
        val s = raw.trim().lowercase()
        namedRates[s]?.let { return it }
        val relative = s.startsWith("+") || s.startsWith("-")
        if (s.endsWith("%")) {
            val pct = s.dropLast(1).trim().toFloatOrNull() ?: return parentRate
            return if (relative) {
                (parentRate * (1f + pct / 100f)).coerceAtLeast(0f)
            } else {
                (pct / 100f).coerceAtLeast(0f)
            }
        }
        return s.toFloatOrNull()?.coerceAtLeast(0f) ?: parentRate
    }

    /** `time` wins over `strength`; a bare `<break/>` is a medium pause. */
    private fun breakMillis(parser: XmlPullParser): Long {
        parser.getAttributeValue(null, "time")?.let { return parseTimeToMillis(it) }
        parser.getAttributeValue(null, "strength")?.let {
            return breakStrengthMillis[it.trim().lowercase()] ?: DEFAULT_BREAK_MILLIS
        }
        return DEFAULT_BREAK_MILLIS
    }

    private fun parseTimeToMillis(raw: String): Long {
        val s = raw.trim().lowercase()
        return when {
            s.endsWith("ms") -> s.dropLast(2).trim().toLongOrNull() ?: 0L
            s.endsWith("s") -> s.dropLast(1).trim().toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
            else -> s.toLongOrNull() ?: 0L
        }.coerceAtLeast(0L)
    }
}
