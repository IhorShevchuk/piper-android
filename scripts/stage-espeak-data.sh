#!/usr/bin/env bash
# Builds espeak-ng for the host and stages its compiled runtime data
# (phondata/phonindex/phontab/intonations + the English dictionary) into
# third-party/espeak-ng/build/espeak-ng-data.
#
# CI uses this to push data onto the emulator before connectedAndroidTest;
# it is also handy on a dev machine instead of copying another repo's assets.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ESPEAK="$ROOT/third-party/espeak-ng"
BUILD="$ESPEAK/build"
DATA="$BUILD/espeak-ng-data"

if [ ! -f "$ESPEAK/CMakeLists.txt" ]; then
  echo "ERROR: $ESPEAK sources not found; run scripts/fetch-native-deps.sh first" >&2
  exit 1
fi

echo "== building host espeak-ng"
cmake -S "$ESPEAK" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release > /dev/null
NPROC="$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
cmake --build "$BUILD" --target espeak-ng-bin -j"$NPROC" > /dev/null

echo "== staging runtime data"
mkdir -p "$DATA"
cp -r "$ESPEAK/espeak-ng-data/lang" "$DATA/"
# voices/, minus the !v MBROLA variants (mirrors cmake/data.cmake)
mkdir -p "$DATA/voices"
for v in "$ESPEAK"/espeak-ng-data/voices/*; do
  [ "$(basename "$v")" = "!v" ] && continue
  cp -r "$v" "$DATA/voices/"
done

BIN="$BUILD/src/espeak-ng"
export ESPEAK_DATA_PATH="$BUILD"

(cd "$ESPEAK/phsource" && "$BIN" --compile-intonations > /dev/null)
(cd "$ESPEAK/phsource" && "$BIN" --compile-phonemes > /dev/null)

# English dictionary only: the device suite is English-only
# (Chinese end-to-end is deliberately out of scope).
DICT_TMP="$BUILD/dict-tmp-en"
rm -rf "$DICT_TMP"; mkdir -p "$DICT_TMP"
cp "$ESPEAK/dictsource/en_rules" "$ESPEAK/dictsource/en_list" \
   "$ESPEAK/dictsource/en_emoji" "$DICT_TMP/"
(cd "$DICT_TMP" && "$BIN" --compile=en > /dev/null)
rm -rf "$DICT_TMP"

echo "== staged at $DATA"
ls "$DATA"
