package dev.ihorshevchuk.piper.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM port of PiperSpeedCurveTests (piper-objc). Guards the exact curve table,
 * the clamp-no-extrapolation behavior, and the AV-vs-multiplier distinction.
 */
class SpeedCurveTest {

    @Test
    fun rateBelowMinimumClampsToFirstSpeed() {
        assertEquals(0.5001928457f, SpeedCurve.speedRatio(0.1f), 0f)
        assertEquals(0.5001928457f, SpeedCurve.speedRatio(0.0f), 0f)
    }

    @Test
    fun rateAboveMaximumClampsToMaxSpeedNoExtrapolation() {
        assertEquals(2.2f, SpeedCurve.speedRatio(1.1f), 0f)
        assertEquals(2.2f, SpeedCurve.speedRatio(2.0f), 0f)
    }

    @Test
    fun exactDataPoints() {
        assertEquals(0.5001928457f, SpeedCurve.speedRatio(0.20f), 0f)
        assertEquals(1.0f, SpeedCurve.speedRatio(0.50f), 0f)
        assertEquals(2.1f, SpeedCurve.speedRatio(0.75f), 0f)
        assertEquals(2.2f, SpeedCurve.speedRatio(1.00f), 0f)
    }

    @Test
    fun interpolationBetweenPoints() {
        // 0.525 is halfway between 0.50 (1.0) and 0.55 (1.2926956961)
        val expected = 1.0f + 0.5f * (1.2926956961f - 1.0f)
        assertTrue(abs(SpeedCurve.speedRatio(0.525f) - expected) < 1e-5f)
        // 0.87 is 40% of the way between 0.85 (2.18) and 0.90 (2.2)
        val expected2 = 2.18f + 0.4f * (2.2f - 2.18f)
        assertTrue(abs(SpeedCurve.speedRatio(0.87f) - expected2) < 1e-5f)
    }

    @Test
    fun base1xMultiplierIsNormalNotFast() {
        // Plain 1.0x must map to length 1.0, not the 2.2x AV fastest point.
        assertEquals(1.0f, SpeedCurve.lengthScaleForRate(1.0f), 0f)
        // AV 0.5 (normal) -> speed 1.0 -> length 1.0
        assertTrue(abs(SpeedCurve.lengthScaleForRate(0.5f) - 1.0f) < 1e-3f)
    }

    @Test
    fun eightyPercentNotTooFast() {
        val length = SpeedCurve.lengthScaleForRate(0.80f)
        assertTrue("80% length $length should be ~0.465", abs(length - 0.465f) < 0.05f)
        assertTrue("80% must not be as fast as the old 0.357 that dropped S", length > 0.35f)
    }

    @Test
    fun highRatesPreserveSibilants() {
        // PT-BR needs length >= 0.45 or the S phoneme is dropped.
        for (rate in listOf(0.80f, 0.85f, 0.90f, 0.95f, 1.00f)) {
            val length = SpeedCurve.lengthScaleForRate(rate)
            assertTrue("rate $rate length $length must be >= 0.45", length >= 0.45f)
        }
    }

    @Test
    fun multiplierPathAboveOne() {
        assertTrue(abs(SpeedCurve.lengthScaleForRate(2.0f) - 0.5f) < 1e-3f)
        assertTrue(abs(SpeedCurve.lengthScaleForRate(1.5f) - 0.6666f) < 0.01f)
    }

    @Test
    fun nonPositiveRateIsNormal() {
        assertEquals(1.0f, SpeedCurve.lengthScaleForRate(0.0f), 0f)
        assertEquals(1.0f, SpeedCurve.lengthScaleForRate(-1.0f), 0f)
    }
}
