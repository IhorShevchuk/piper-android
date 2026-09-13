package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.MarkerRange
import dev.ihorshevchuk.piper.utils.SpeedCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level SSML guards (port of Piper.getOptions + synthesizeSSML
 * behavior, piper-objc): fragment order is preserved, each fragment's
 * prosody rate resolves to the right lengthScale, the speakerId override
 * applies to every sentence, and marker ranges tile the concatenated plain
 * text without gaps or overlaps.
 *
 * [resolveSsmlPlan] is the JVM-testable extraction of the resolveOptions
 * closure inside PiperEngine.synthesizeSsml.
 */
class PiperEngineSsmlPlanTest {

    private val base = PiperSynthesizeOptions(speakerId = 0, lengthScale = 1.0f)

    private val ssml =
        "<speak><prosody rate=\"50%\">Slow part here.</prosody>" +
            "<prosody rate=\"200%\">Fast part here.</prosody></speak>"

    @Test
    fun `fragment order is preserved`() {
        val resolved = resolveSsmlPlan(ssml, base, speakerId = 7)

        assertEquals(
            listOf("Slow part here.", "Fast part here."),
            resolved.map { it.sentence.text }
        )
    }

    @Test
    fun `per-fragment rate maps to lengthScale`() {
        val resolved = resolveSsmlPlan(ssml, base, speakerId = 7)

        // 50% is the AV-normal branch: speedRatio(0.5) = 1.0 -> length 1.0.
        assertEquals(
            SpeedCurve.lengthScaleForRate(0.5f),
            resolved[0].options.lengthScale
        )
        // 200% is the multiplier branch: 1/2.0 = 0.5.
        assertEquals(0.5f, resolved[1].options.lengthScale)
    }

    @Test
    fun `speakerId override applies to every sentence`() {
        val resolved = resolveSsmlPlan(ssml, base, speakerId = 7)

        assertTrue(resolved.isNotEmpty())
        assertTrue(resolved.all { it.options.speakerId == 7 })
    }

    @Test
    fun `ranges tile the concatenated plain text`() {
        val resolved = resolveSsmlPlan(ssml, base, speakerId = 7)
        val plain = "Slow part here.Fast part here."

        assertEquals(2, resolved.size)
        assertEquals(MarkerRange(0, 15), resolved[0].sentence.range)
        assertEquals(MarkerRange(15, 15), resolved[1].sentence.range)
        // Ranges must reconstruct the concatenated text exactly.
        val rebuilt = resolved.joinToString("") { plain.substring(it.sentence.range.location, it.sentence.range.location + it.sentence.range.length) }
        assertEquals(plain, rebuilt)
    }

    @Test
    fun `plain-text rate 1_0 keeps lengthScale 1_0`() {
        val resolved = resolveSsmlPlan(
            "<speak><prosody rate=\"100%\">Normal.</prosody></speak>",
            base,
            speakerId = 0
        )

        assertEquals(1, resolved.size)
        assertEquals(1.0f, resolved[0].options.lengthScale)
    }
}
