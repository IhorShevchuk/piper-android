package dev.ihorshevchuk.piper.engine

/**
 * Options for creating a Piper synthesizer.
 *
 * Direct port of PiperCreateOptions (piper-objc, Swift). Field-for-field mirror;
 * the JNI layer forwards these to piper_create_with_options.
 */
data class PiperCreateOptions(
    /** Path to the ONNX voice model file. Required. */
    val modelPath: String,
    /** Path to the JSON voice config file, or null to use modelPath + ".json". */
    val configPath: String? = null,
    /** Path to the espeak-ng data directory, or null for auto-discovery / bundled data. */
    val espeakDataPath: String? = null,
    /**
     * Optional root data directory that may contain espeak-ng-data/ and the
     * pinyin dictionary. If espeakDataPath or g2pwModelDir is null, the native
     * side searches inside dataDir.
     */
    val dataDir: String? = null,
    /**
     * Path to the g2pw / pinyin dictionary directory for Chinese, or null for
     * auto-discovery. Should contain MONOPHONIC_CHARS.txt (Phase 1) and/or
     * char_bopomofo_dict.json etc.
     */
    val g2pwModelDir: String? = null
)
