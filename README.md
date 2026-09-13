# piper-kotlin

[![CI](https://github.com/IhorShevchuk/piper-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/IhorShevchuk/piper-kotlin/actions/workflows/ci.yml)

Offline neural text-to-speech for Android. `piper-kotlin` bundles the Piper
TTS engine (the `piper1-gpl` C++ core), the espeak-ng phonemizer, and ONNX
Runtime behind a Kotlin API: create an engine for a voice model, synthesize
text to PCM audio, and play it back with `PiperPlayer`. Voice files are the
standard Piper `.onnx` / `.onnx.json` pairs, used unchanged.

## Modules

| Module | Contents |
|---|---|
| `:piper-engine` | `PiperEngine`: JNI bridge over libpiper; serialized synthesis, sentence splitting, skip-failed-sentence resilience, WAV file output |
| `:piper-utils` | Pure-JVM Kotlin: `SentenceSplitter`, `SsmlParser`, `AlignmentParser`, `SpeedCurve` (17-point rate table with sibilant-safe clamp) |
| `:piper-player` | `PiperPlayer`: `AudioTrack` streaming playback over `PiperEngine` |

## Usage

```kotlin
val engine = PiperEngine(
    PiperCreateOptions(modelPath = "/path/to/en_US-lessac-medium.onnx"),
    appFilesDir = context.filesDir,
)
engine.use {
    // Streams FloatArray PCM chunks (22050 Hz) as they are synthesized.
    it.synthesize("Hello, world.", onSamples = { pcm -> player.write(pcm) })
}
```

`configPath = null` means "modelPath + .json". `synthesizeToFile()` writes a
WAV directly; `synthesizeSsml()` applies per-fragment prosody rates.

## Prerequisites

- JDK 17
- Android SDK with platform 35, build-tools 35.0.0, NDK (version pinned in
  `gradle.properties` as `piper.ndkVersion`), CMake 3.22.1

```bash
scripts/setup-android-sdk.sh   # installs the SDK into ~/workspace/android-sdk
```

## Build

piper1-gpl, espeak-ng and sonic are git submodules pinned at tested SHAs
(see `.gitmodules`); onnxruntime is downloaded as a binary (no upstream
submodule exists for the Android AAR + headers).

```bash
git clone --recurse-submodules https://github.com/IhorShevchuk/piper-kotlin.git
# ...or, in an existing clone:
git submodule update --init --recursive
scripts/fetch-native-deps.sh   # inits submodules + stages onnxruntime into third-party/
./gradlew :piper-engine:assembleDebug   # builds the native + Kotlin library
```

(The Gradle wrapper jar is not vendored; run `gradle wrapper` once with a
local Gradle 8.10.2, or let your IDE generate it.)

## Testing

```bash
./gradlew test                                        # JVM unit tests
./gradlew :piper-engine:connectedAndroidTest          # on-device tests (needs a connected device)
```

## How the JNI maps to the C API

`PiperEngine` declares private instance `external fun`s so the bindings use
the plain `Java_dev_ihorshevchuk_piper_engine_PiperEngine_nativeXxx` names:

| Kotlin | C++ (`piper_jni.cpp`) | piper.h |
|---|---|---|
| `nativeCreate(...)` | holds `piper_synthesizer*` in an `EngineContext`, returned as `jlong` | `piper_create_with_options` |
| `nativeDestroy(handle)` | `piper_free` + deletes the context | `piper_free` |
| `nativeDefaultOptions(handle)` | `[speakerId, lengthScale, noiseScale, noiseWScale]` | `piper_default_synthesize_options` |
| `nativeSynthesizeStart(...)` | returns the raw rc | `piper_synthesize_start` |
| `nativeSynthesizeNext(handle)` | `FloatArray?`; null at `PIPER_DONE` (or on error) | `piper_synthesize_next` |
| `nativeLastChunk{SampleRate,IsLast,Phonemes,Alignments}` | copied from the cached `piper_audio_chunk` | chunk fields |
| `nativeVersion()` | `piper_version()` | `piper_version()` |

Every chunk's samples, phonemes and alignments are copied out of the
`piper_audio_chunk` immediately, so the JNI never depends on piper's buffer
ownership rules. A failed sentence (`PIPER_ERR_GENERIC` from
`nativeSynthesizeStart`) is skipped and synthesis continues with the next one.

## Threading model

libpiper is not thread-safe. All native calls are serialized through a
dedicated single-thread executor inside `PiperEngine`. `synthesize()` blocks
the calling thread; `PiperPlayer` calls it from its own worker thread.
`cancel()` stops synthesis between sentences; `close()` queues
`nativeDestroy` behind any in-flight call.

## Speed curve

`SpeedCurve` maps UI speed percentages to engine `length_scale` through a
fixed 17-point table. Two behaviors are load-bearing:

- Rates above 1.0 are **clamped, never extrapolated**.
- The curve tops out at **2.2x**, so the fastest `length_scale` is ~0.4545,
  which stays above the 0.45 floor where voices start dropping sibilants.
  1.0x maps to length 1.0 (normal speech).

## espeak-ng-data packaging (~25 MB)

The native layer needs the compiled espeak-ng data directory at runtime.
`PiperEngine` resolves it as: explicit `espeakDataPath` ->
`<files>/espeak-ng-data` -> `<dataDir>/espeak-ng-data` -> native
auto-discovery. `scripts/stage-espeak-data.sh` builds the vendored espeak-ng
for the host and stages the data directory under
`third-party/espeak-ng/build/espeak-ng-data`.

## Voice files

Standard Piper `.onnx` / `.onnx.json` pairs, no conversion.
`configPath = null` means "modelPath + .json".

## License

GPL-3.0-or-later - see [LICENSE](LICENSE). Third-party native components
under `third-party/` keep their own licenses (piper1-gpl and espeak-ng are
GPL-3.0; see their sources).
