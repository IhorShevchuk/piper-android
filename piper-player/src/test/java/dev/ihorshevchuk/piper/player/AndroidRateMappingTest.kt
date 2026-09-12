package dev.ihorshevchuk.piper.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android TTS speechRate (100 = normal) -> Piper length_scale.
 *
 * Android rates are physical-speed multipliers, unlike the AV-style rates
 * SpeedCurve.lengthScaleForRate handles: 50 means half speed, 200 double.
 * The PT-BR sibilant floor is the point of the exercise: no request may push
 * length_scale below 0.45, so the fastest physical speed is 2.2x.
 */
class AndroidRateMappingTest {

    @Test
    fun normalRateIsExactlyOne() {
        assertEquals(1.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(100), 0.0001f)
    }

    @Test
    fun nonPositiveRateFallsBackToNormal() {
        assertEquals(1.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(0), 0.0001f)
        assertEquals(1.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(-50), 0.0001f)
    }

    @Test
    fun halfSpeedDoublesLengthScale() {
        assertEquals(2.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(50), 0.0001f)
    }

    @Test
    fun doubleSpeedHalvesLengthScale() {
        assertEquals(0.5f, SpeedCurve.lengthScaleForAndroidSpeechRate(200), 0.0001f)
    }

    @Test
    fun oneAndAHalfSpeed() {
        assertEquals(1f / 1.5f, SpeedCurve.lengthScaleForAndroidSpeechRate(150), 0.0001f)
    }

    @Test
    fun maxAndroidRateIsClampedToSibilantSafeSpeed() {
        // 400 would naively give 0.25 - below the 0.45 sibilant floor.
        // iOS parity: physical speed never exceeds 2.2x.
        val scale = SpeedCurve.lengthScaleForAndroidSpeechRate(400)
        assertEquals(1f / 2.2f, scale, 0.0001f)
        assertTrue("length_scale $scale below sibilant floor", scale >= 0.45f)
    }

    @Test
    fun aboveMaxClampAlsoClamped() {
        assertEquals(
            SpeedCurve.lengthScaleForAndroidSpeechRate(400),
            SpeedCurve.lengthScaleForAndroidSpeechRate(300),
            0.0001f
        )
    }

    @Test
    fun verySlowRateFloorsAtQuarterSpeed() {
        assertEquals(4.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(25), 0.0001f)
        assertEquals(4.0f, SpeedCurve.lengthScaleForAndroidSpeechRate(10), 0.0001f)
    }
}
