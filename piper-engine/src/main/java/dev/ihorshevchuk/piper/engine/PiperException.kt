package dev.ihorshevchuk.piper.engine

/** Thrown for every Piper failure: missing files, native errors, misuse. */
class PiperException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
