# piper-kotlin

[![CI](https://github.com/IhorShevchuk/piper-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/IhorShevchuk/piper-kotlin/actions/workflows/ci.yml)

Android port of piper-objc / piper-app. Same voices, same engine behavior,
same speed curve - Kotlin and JNI instead of Swift.

The Swift package itself cannot be reused on Android (no Swift toolchain), so
this project re-hosts the portable layers: the libpiper C++ core and the
espeak-ng phonemizer compile for Android via the NDK, and the Swift API
surface is mirrored 1:1 in Kotlin. Voice model files (`.onnx` + `.onnx.json`)
are byte-identical across platforms.

## Modules

| Android module | iOS twin | Contents |
|---|---|---|
| `:piper-engine` | piper-objc (Piper.swift, PiperCreateOptions.swift) | `PiperEngine`: JNI bridge over libpiper; serialized synthesis, sentence splitting, skip-failed-sentence resilience, WAV file output |
| `:piper-utils` | piper-utils (Swift) | Pure-JVM Kotlin: `SentenceSplitter`, `SsmlParser`, `AlignmentParser` |
| `:piper-player` | piper-player (Swift) | `SpeedCurve` (exact 17-point table + sibilant clamp), `PiperPlayer` (AudioTrack streaming) |

The sample app lives in the sibling repo **piper-app-android**
(iOS twin: piper-app) and consumes this library via a Gradle composite build.

## Prerequisites

- JDK 17
- Android SDK with platform 35, build-tools 35.0.0, NDK 27.0.12077973, CMake 3.22.1

```bash
scripts/setup-android-sdk.sh   # installs the SDK into ~/workspace/android-sdk
```

## Build

```bash
scripts/fetch-native-deps.sh   # vendors piper1-gpl, espeak-ng, onnxruntime into third-party/
./gradlew :piper-engine:assembleDebug   # builds the native + Kotlin library
```

Then in the sibling piper-app-android repo:

```bash
scripts/download-voice.sh en_US-lessac-medium   # voice -> app/src/main/assets/voices/
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
```

(The Gradle wrapper jar is not vendored; run `gradle wrapper` once with a
local Gradle 8.10.2, or let your IDE generate it.)

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
ownership rules. `PIPER_ERR_GENERIC` from `nativeSynthesizeStart` skips that
sentence and continues (the Sawyer long-utterance fix, ported from Swift).

## Threading model

libpiper is not thread-safe. All native calls are serialized through a
dedicated single-thread executor inside `PiperEngine` (the equivalent of the
iOS serial OperationQueue). `synthesize()` blocks the calling thread;
`PiperPlayer` calls it from its own worker thread. `cancel()` stops synthesis
between sentences; `close()` queues `nativeDestroy` behind any in-flight call.

## Speed curve

`SpeedCurve` ports the exact 17-point table and the AV-vs-multiplier
`getOptions` distinction from Piper.swift. Two behaviors are load-bearing:

- Rates above 1.0 are **clamped, never extrapolated** (the 1.0.10 bug read
  2.0x as 7.88x).
- The curve tops out at **2.2x**, so the fastest `length_scale` is ~0.4545,
  which stays above the 0.45 floor where PT-BR voices start dropping
  sibilants (Ricksparta / Ricardo, Sep 2026). A plain 1.0x multiplier maps to
  length 1.0 (normal), not to the 2.2x AV-fastest point.

`SpeedCurveTest` mirrors the Swift test suite on the JVM.

## espeak-ng-data packaging (~25 MB)

The native layer needs the compiled espeak-ng data directory at runtime.
`PiperEngine` resolves it as: explicit `espeakDataPath` -> `<files>/espeak-ng-data`
-> `<dataDir>/espeak-ng-data` -> native auto-discovery. The sample app (in
piper-app-android) copies `app/src/main/assets/espeak-ng-data/` to filesDir on
first launch. Stage it there by compiling the vendored espeak-ng
(`third-party/espeak-ng`) once, or reuse the data directory already bundled
with the iOS app.

## Voice files

Same `.onnx` / `.onnx.json` files as iOS, no conversion. `configPath = null`
means "modelPath + .json", matching the Swift behavior.

## First-build iteration

The scaffold is complete and compiling-intent, but three foreign-source
details must be reconciled against the vendored trees on the first real
build:

1. **espeak-ng CMake source list** (`piper-engine/src/main/cpp/CMakeLists.txt`):
   the exclusion regex keeps CLI/test mains out of `libpiper_jni.so`. File
   names drift between espeak-ng releases - duplicate `main()` at link time
   means a missed exclusion; missing symbols mean over-exclusion.
2. **`config.h`** (`piper-engine/src/main/cpp/cmake-config/config.h.in`):
   minimal hand-written set of `HAVE_*` defines. If the compiler asks for
   more, add them here.
3. **piper.h field names** (`piper_jni.cpp`, top-of-file comment): the assumed
   `piper_create_options` / `piper_synthesize_options` / `piper_audio_chunk`
   layouts must match `third-party/piper1-gpl/libpiper/include/piper.h`.
4. **onnxruntime AAR layout** (`scripts/fetch-native-deps.sh`): the script
   fails loudly if `libonnxruntime.so` is not where expected under `jni/<abi>/`.

## License

GPL-3.0-or-later, to match piper1-gpl - see [LICENSE](LICENSE). Keep this
port open source like the rest of the Piper work. Third-party native
components under `third-party/` keep their own licenses (piper1-gpl and
espeak-ng are GPL-3.0; see their sources).
