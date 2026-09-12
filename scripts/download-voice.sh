#!/usr/bin/env bash
# Downloads a Piper voice (.onnx + .onnx.json) from HuggingFace rhasspy/piper-voices
# into app/src/main/assets/voices/.
#
# Usage: scripts/download-voice.sh [voice-id]
#   voice-id looks like en_US-lessac-medium (default)
set -euo pipefail

VOICE="${1:-en_US-lessac-medium}"

# Split en_US-lessac-medium -> lang part "en_US", name "lessac", quality "medium".
LANG_PART="${VOICE%%-*}"          # en_US
REST="${VOICE#*-}"                # lessac-medium
NAME="${REST%-*}"                 # lessac
QUALITY="${REST##*-}"             # medium
LANG2="${LANG_PART%%_*}"          # en

BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
OUT="app/src/main/assets/voices"
mkdir -p "$OUT"

for ext in onnx onnx.json; do
  url="$BASE/$LANG2/$LANG_PART/$NAME/$QUALITY/$VOICE.$ext"
  echo "== fetching $url"
  curl -fL -o "$OUT/$VOICE.$ext" "$url"
done

echo "== saved to $OUT/$VOICE.{onnx,onnx.json}"
