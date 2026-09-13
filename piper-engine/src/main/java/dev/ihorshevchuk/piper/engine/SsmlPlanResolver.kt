package dev.ihorshevchuk.piper.engine

import dev.ihorshevchuk.piper.utils.PlannedSentence
import dev.ihorshevchuk.piper.utils.SpeedCurve
import dev.ihorshevchuk.piper.utils.SynthesisPlanner

/** One SSML sentence with its fully resolved synthesis options. */
internal data class ResolvedSsmlSentence(
    val sentence: PlannedSentence,
    val options: PiperSynthesizeOptions
)

/**
 * JVM-testable extraction of the resolveOptions closure inside
 * [PiperEngine.synthesizeSsml] (port of Piper.getOptions): fragment order is
 * preserved, each sentence's prosody rate is mapped through
 * [SpeedCurve.lengthScaleForRate], and [speakerId] overrides every sentence.
 */
internal fun resolveSsmlPlan(
    ssml: String,
    base: PiperSynthesizeOptions,
    speakerId: Int
): List<ResolvedSsmlSentence> =
    SynthesisPlanner.planSsml(ssml).map { planned ->
        ResolvedSsmlSentence(
            sentence = planned,
            options = base.copy(
                speakerId = speakerId,
                lengthScale = SpeedCurve.lengthScaleForRate(planned.rate)
            )
        )
    }
