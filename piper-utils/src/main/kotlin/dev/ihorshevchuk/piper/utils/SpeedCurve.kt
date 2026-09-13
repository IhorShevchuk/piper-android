package dev.ihorshevchuk.piper.utils

/**
 * Port of Piper.speedRatio(for:) and the getOptions rate mapping (piper-objc).
 *
 * The table is measured from AVSpeechUtterance-style rates to the multiplier
 * the Piper voice actually speaks at. Values copied exactly from
 * PiperSpeedCurveTests.swift.
 *
 * Sibilant clamp (PT-BR, Ricksparta / Ricardo, Sep 2026): Piper drops the S
 * phoneme when length_scale goes below 0.45. The curve is therefore capped at
 * 2.2x, so the fastest length_scale is 1/2.2 ~= 0.4545, which stays above the
 * 0.45 floor. Never extrapolate above the last point.
 */
object SpeedCurve {
    /**
     * Fastest physical speaking speed, shared with iOS. Piper drops the S
     * phoneme when length_scale goes below 0.45, so 2.2x (1/2.2 ~= 0.4545)
     * is the fastest safe speed. Never exceed this.
     */
    const val SIBILANT_SAFE_MAX_SPEED = 2.2f

    private val points: List<Pair<Float, Float>> = listOf(
        0.20f to 0.5001928457f,
        0.25f to 0.5550218062f,
        0.30f to 0.6285364609f,
        0.35f to 0.7189278745f,
        0.40f to 0.8310849027f,
        0.45f to 0.9119920277f,
        0.50f to 1.0f,
        0.55f to 1.2926956961f,
        0.60f to 1.5843505525f,
        0.65f to 1.8302883372f,
        0.70f to 2.0f,
        0.75f to 2.1f,
        0.80f to 2.15f,
        0.85f to 2.18f,
        0.90f to 2.2f,
        0.95f to 2.2f,
        1.00f to 2.2f
    )

    /**
     * Interpolated speed ratio for an AV-style rate in [0.2, 1.0].
     * Clamps below the first point and above the last point; no extrapolation.
     */
    fun speedRatio(rate: Float): Float {
        if (rate <= points.first().first) return points.first().second
        if (rate >= points.last().first) return points.last().second
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].first <= rate) lo = mid else hi = mid
        }
        val (r0, s0) = points[lo]
        val (r1, s1) = points[hi]
        val t = (rate - r0) / (r1 - r0)
        return s0 + t * (s1 - s0)
    }

    /**
     * Port of the Swift getOptions AV-vs-multiplier distinction:
     * - rate <= 0 -> 1.0 (normal)
     * - rate == 1.0 -> 1.0. A plain 1.0x multiplier means "normal speed"; it
     *   must NOT take the AV path, where 1.0 maps to the 2.2x fastest curve
     *   point (the 1.0.10 bug that read 1.0x as super-fast).
     * - 0.2 <= rate < 1.0 -> AV path: 1 / speedRatio(rate), clamped 0.1..10.
     * - otherwise -> plain multiplier: 1 / rate, clamped 0.1..10.
     */
    fun lengthScaleForRate(rate: Float): Float {
        if (rate <= 0f) return 1.0f
        if (rate == 1.0f) return 1.0f
        return if (rate >= 0.2f && rate < 1.0f) {
            (1.0f / speedRatio(rate)).coerceIn(0.1f, 10.0f)
        } else {
            (1.0f / rate).coerceIn(0.1f, 10.0f)
        }
    }

    /**
     * Maps an Android TextToSpeech speechRate (100 = normal speed) to a Piper
     * length_scale.
     *
     * Android rates are physical-speed multipliers, unlike the AV-style rates
     * [lengthScaleForRate] handles: 50 means half speed, 200 means double.
     * Physical speed is therefore 1/speed through the clamped range rather
     * than the AV branch of the curve - 50 -> 2.0, 200 -> 0.5 - which is what
     * Android clients (TalkBack, system TTS settings) expect.
     *
     * iOS parity is preserved where it matters: exactly 100 (or a
     * non-positive rate) maps to 1.0 (normal, never the 2.2x curve top), and
     * physical speed never exceeds [SIBILANT_SAFE_MAX_SPEED], the PT-BR
     * sibilant-protection ceiling.
     */
    fun lengthScaleForAndroidSpeechRate(speechRate: Int): Float {
        val speed = speechRate / 100f
        if (speed <= 0f) return 1.0f
        if (speed == 1f) return 1.0f
        return (1f / speed.coerceIn(0.25f, SIBILANT_SAFE_MAX_SPEED)).coerceIn(0.1f, 10.0f)
    }
}
