# piper1-gpl patches

`third-party/piper1-gpl` is a pristine upstream checkout (see
`scripts/fetch-native-deps.sh`). Android-port fixes to the vendored C++
live here as patch files and are applied at CMake configure time
(`piper-engine/src/main/cpp/CMakeLists.txt`, idempotent via a marker
check), so a fresh `fetch-native-deps.sh` clone still builds correctly.

## Patches

- `0001-espeak-refcount-no-throw-create.patch`
  - Generated against piper1-gpl `404aefe` (Sep 2026).
  - espeak-ng is process-global, but `piper_free()` called
    `espeak_Terminate()` per synthesizer. With several live synthesizers
    (one per `PiperEngine`) freeing one engine killed espeak for the whole
    process - later syntheses silently produced nothing, and a second
    terminate (or a failed re-init, whose `exit(1)` path fires when
    `DONT_EXIT` is unset) crashed the process. The patch shares espeak
    through a refcount: initialize once, terminate when the last
    synthesizer is freed.
  - Also stops C++ exceptions escaping the C API: `json::parse` and
    `Ort::Session` failures return `nullptr` (Kotlin raises
    `PiperException`) instead of aborting through JNI.

If the pinned piper1-gpl SHA moves, regenerate: apply manually in a
scratch checkout, `git diff` it back out, and replace this file. The CMake
hook fails the build loudly if a patch no longer applies.
