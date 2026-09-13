#!/usr/bin/env bash
# Vendors the native dependencies into third-party/:
#   third-party/piper1-gpl    git submodule, OHF-Voice/piper1-gpl (libpiper C++ core)
#   third-party/espeak-ng     git submodule, espeak-ng/espeak-ng (phonemization)
#   third-party/sonic         git submodule, waywardgeek/sonic (used by espeak-ng speech.c)
#   third-party/onnxruntime   onnxruntime C/C++ headers + per-ABI .so files
#                             (extracted from the onnxruntime-android AAR)
#
# The submodule SHAs are pinned as gitlinks - bump with e.g.
#   git -C third-party/piper1-gpl checkout <new-sha> && git add third-party/piper1-gpl
# Other versions live in .github/deps.env (ORT_VERSION, NDK_VERSION).
# The Gradle build passes these roots to CMake as PIPER1_GPL_DIR,
# ESPEAK_NG_DIR, SONIC_DIR and ONNXRUNTIME_DIR - see piper-engine/build.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third-party"
mkdir -p "$TP"

# shellcheck disable=SC1091
[ -f "$ROOT/.github/deps.env" ] && . "$ROOT/.github/deps.env"
ORT_VERSION="${ORT_VERSION:-1.22.0}"

echo "== initializing submodules at their pinned SHAs"
git -C "$ROOT" submodule update --init --recursive

# --- onnxruntime -----------------------------------------------------------
ORT_DIR="$TP/onnxruntime"
mkdir -p "$ORT_DIR/include" "$ORT_DIR/lib"

AAR_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ORT_VERSION}/onnxruntime-android-${ORT_VERSION}.aar"
echo "== downloading onnxruntime-android AAR $ORT_VERSION"
curl -fL -o /tmp/onnxruntime-android.aar "$AAR_URL"
rm -rf /tmp/ort-aar && mkdir -p /tmp/ort-aar
unzip -q -o /tmp/onnxruntime-android.aar -d /tmp/ort-aar

for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$ORT_DIR/lib/$abi"
  so="$(find /tmp/ort-aar/jni/$abi -name 'libonnxruntime.so' | head -1 || true)"
  if [ -z "$so" ]; then
    echo "ERROR: libonnxruntime.so not found for $abi; AAR contains:"
    find /tmp/ort-aar/jni/$abi -type f | head -20
    exit 1
  fi
  cp "$so" "$ORT_DIR/lib/$abi/"
  echo "== staged $ORT_DIR/lib/$abi/libonnxruntime.so"
done

echo "== downloading onnxruntime C/C++ headers (v$ORT_VERSION)"
for h in onnxruntime_c_api.h onnxruntime_cxx_api.h onnxruntime_cxx_inline.h onnxruntime_float16.h; do
  curl -fL -o "$ORT_DIR/include/$h" \
    "https://raw.githubusercontent.com/microsoft/onnxruntime/v${ORT_VERSION}/include/onnxruntime/core/session/$h"
done

echo
echo "Native deps ready. Gradle passes these to CMake automatically:"
echo "  -DPIPER1_GPL_DIR=$TP/piper1-gpl"
echo "  -DESPEAK_NG_DIR=$TP/espeak-ng"
echo "  -DSONIC_DIR=$TP/sonic"
echo "  -DONNXRUNTIME_DIR=$TP/onnxruntime"
echo
echo "Next: stage espeak-ng-data for the app (see README 'espeak-ng-data packaging'),"
echo "then run ./gradlew assembleDebug"
