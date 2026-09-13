package dev.ihorshevchuk.piper.player

/**
 * Backward-compatibility alias: [SpeedCurve] now lives in piper-utils
 * (dev.ihorshevchuk.piper.utils) so the engine can use it for SSML
 * fragment rates without depending on piper-player.
 */
@Deprecated(
    "Use dev.ihorshevchuk.piper.utils.SpeedCurve",
    ReplaceWith(
        "SpeedCurve",
        "dev.ihorshevchuk.piper.utils.SpeedCurve"
    )
)
typealias SpeedCurve = dev.ihorshevchuk.piper.utils.SpeedCurve
