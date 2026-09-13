# piper-android integration testing (M4 + physical device)

Unit tests (`:piper-utils:test`, `:engine-jvm:test` via the sandbox scratch
settings) cover the pure-Kotlin core. The tests in
`piper-engine/src/androidTest` need the real native stack and run on hardware.

## Prerequisites (Mac mini M4)

1. Android SDK + NDK 27 (see `scripts/setup-android-sdk.sh`).
2. Native deps fetched: `scripts/fetch-native-deps.sh`
   (libonnxruntime.so per ABI, third-party sources).
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
./gradlew :piper-engine:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data
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

## Ear validation checklist (do after green)

Automated tests cannot hear. `EarValidationTest` (in
`piper-engine/src/androidTest`) writes one WAV per checklist item into the
test app's external files dir and stress-tests rapid play/stop/play through
`PiperPlayer` + `AudioTrack`. It runs as part of `connectedAndroidTest`:

```bash
./gradlew :piper-engine:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.espeakDataPath=/data/local/tmp/espeak-ng-data
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
