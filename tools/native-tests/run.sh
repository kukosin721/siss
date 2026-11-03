#!/usr/bin/env bash
# Compila y ejecuta las pruebas del motor de audio nativo en el host (Linux/macOS).
# Usa dobles de Oboe y de <android/log.h>: no necesita NDK, ni device, ni Gradle.
#
#   ./tools/native-tests/run.sh            # pruebas
#   ./tools/native-tests/run.sh --asan     # con AddressSanitizer + UBSan
#   ./tools/native-tests/run.sh --tsan     # con ThreadSanitizer
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CXX="${CXX:-g++}"
OUT="${OUT:-$HERE/build/audio-engine-tests}"

FLAGS=(-std=c++17 -O1 -g -Wall -Wextra -Wno-unused-parameter -pthread
       -I"$HERE/stubs" -I"$ROOT/app/src/main/cpp")

case "${1:-}" in
  --asan) FLAGS+=(-fsanitize=address,undefined -fno-omit-frame-pointer) ;;
  --tsan) FLAGS+=(-fsanitize=thread) ;;
esac

mkdir -p "$(dirname "$OUT")"
echo "Compilando $OUT"
"$CXX" "${FLAGS[@]}" "$HERE/AudioEngineTests.cpp" -o "$OUT"
"$OUT"
