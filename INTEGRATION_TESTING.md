# piper-kotlin integration testing (M4 + physical device)

Unit tests (`:piper-utils:test`, `:engine-jvm:test` via the sandbox scratch
settings) cover the pure-Kotlin core. The tests in
`piper-engine/src/androidTest` need the real native stack and run on hardware.

## Prerequisites (Mac mini M4)

1. Android SDK + NDK 27 (see `scripts/setup-android-sdk.sh`).
2. Native deps ready: submodules checked out at their pinned SHAs
   (`git submodule update --init --recursive`), then
   `scripts/fetch-native-deps.sh` (stages libonnxruntime.so per ABI).
3. A physical Android device with USB debugging, visible to `adb devices`.
4. Compiled espeak-ng data. The app stages it via
   `../piper-app-android/scripts/stage-espeak-data.sh`; the integration tests
   reuse that same directory.

## Push the espeak-ng data to the device

```bash
adb push ../piper-app-android/app/src/main/assets/espeak-ng-data /data/local/tmp/espeak-ng-data
```

## Run the integration tests

```bash
./gradlew :piper-engine:connectedAndroidTest
# -PespeakDataPath=/other/path overrides the default /data/local/tmp/espeak-ng-data
```

The suite downloads the `en_US-lessac-medium` fp16 voice (~32 MB) from
`IhorShevchuk/piper1-voices-fp16-quantized` once, into the test app's cache.

## What the suite validates on device

- Engine initializes against the real `libpiper_jni.so` and reports a version.
- Plain synthesis streams float32 samples at 16/22.05 kHz.
- `synthesizeToFile` writes a valid RIFF/WAVE file.
- SSML synthesis honors per-fragment prosody rates and emits one cumulative
  sentence marker per fragment (second offset > first, first == 0).
- `getMemoryUsage()` reads `/proc/self/status` on device.
- Memory-pressure handling (port of `Piper.memoryThresholdBytes` +
  `DispatchSourceMemoryPressure` in piper-objc):
  `PiperEngineMemoryPressureTest` sets `memoryThresholdBytes = 1` and
  asserts the synthesizer is recreated transparently mid-synthesis;
  `onTrimMemory(15)` (critical) releases the handle and the next synthesis
  rebuilds it lazily; `onTrimMemory(10)` (low) recreates immediately;
  unknown levels are ignored.
- `PiperPlayerTest`: stop-when-idle safety, per-sentence marker emission
  (first == 0, monotonic), stop-during-startup recovery (regression test
  for the 2adec86 crash), and `synthesizeToFile` WAV validity.
- `PiperEngineBehaviorTest`: `cancel()` stops a 30-sentence synthesis
  between sentences; single-output models emit no alignment groups
  (verified against the lessac graph: outputs == `["output"]`), so
  `onAlignment` stays silent and markers use the character-proportion
  fallback; a missing model file throws `PiperException`; `close()` is
  idempotent; the public `recreateSynthesizer()` leaves the engine usable.

> **Device-test lesson (2026-09-13):** espeak-ng is process-global, but
> `piper_free()` used to call `espeak_Terminate()` per synthesizer. Closing
> one `PiperEngine` while another was alive killed espeak for the whole
> process (later syntheses silently produced nothing) and a second
> terminate/re-init crashed the run. `third-party/piper1-gpl` stays a
> pristine upstream checkout; the fix lives in
> `piper-engine/src/main/cpp/patches/piper1-gpl/` and is applied at CMake
> configure time: espeak is shared through a refcount (init once, terminate
> on last free), and C++ exceptions (`json::parse`, `Ort::Session`) no
> longer escape the C API - they return `nullptr` so Kotlin raises
> `PiperException` instead of aborting.

## App-side wiring (piper-app-android)

The library exposes the pressure entry point; the app forwards the OS
signal. In the TTS service:

```kotlin
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    engine.onTrimMemory(level)
}
```

`onTrimMemory` never blocks the caller: the release/recreate is queued on
the engine thread behind any in-flight synthesis.

## Ear validation checklist (do after green)

Automated tests cannot hear. `EarValidationTest` (in
`piper-engine/src/androidTest`) writes one WAV per checklist item into the
test app's external files dir and stress-tests rapid play/stop/play through
`PiperPlayer` + `AudioTrack`. It runs as part of `connectedAndroidTest`:

```bash
./gradlew :piper-engine:connectedAndroidTest
# -PespeakDataPath=/other/path overrides the default /data/local/tmp/espeak-ng-data
adb pull /sdcard/Android/data/dev.ihorshevchuk.piper.engine.test/files/ear-validation .
```

The exact on-device path is logged by the test (`EarValidation` tag in
logcat). Listen in order:

- [ ] `ear-01-plain-en.wav` - plain English paragraph, natural, no dropouts.
- [ ] `ear-02-ssml-slow.wav` - SSML `rate="50%"`, audibly slower.
- [ ] `ear-03-ssml-fast.wav` - SSML `rate="200%"`, audibly faster.
- [ ] `ear-04-long-text.wav` - 10+ sentences, no mid-utterance dropout.
- [ ] `ear-05-ptbr-cadu.wav` - PT-BR Cadu at 100%, sibilants intact (the test
      also asserts `length_scale >= 0.45` for the 100% rate).
