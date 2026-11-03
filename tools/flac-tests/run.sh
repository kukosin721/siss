#!/usr/bin/env bash
# Arnés del decodificador FLAC propio, sin Android ni dispositivo.
#
#   ./tools/flac-tests/run.sh
#
# 1. Genera archivos FLAC de prueba (16 y 24 bits, 44.1/48/96 kHz, mono y estéreo) con
#    señales que ejercitan los tres modos de subframe (verbatim, fixed y LPC), el silencio
#    (run-length) y los distintos modos estéreo (izq/der, izq-lado, medio-lado).
# 2. Decodifica el PCM de referencia con **libFLAC** (vía libsndfile/python) y lo guarda al
#    lado del .flac.
# 3. Compila y ejecuta el arnés de C++, que compara byte a byte lo que entrega el
#    decodificador propio contra ese PCM.
#
# Requiere: g++, python3 con `soundfile` + `numpy` (para generar los fixtures).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${OUT:-$HERE/build}"
FIXTURES="$HERE/fixtures"

mkdir -p "$OUT" "$FIXTURES"

echo "== Generando fixtures (libsndfile/libFLAC) =="
python3 "$HERE/generate_fixtures.py" "$FIXTURES"

SANITIZE=""
NAME="flac-decode-tests"
for arg in "$@"; do
  case "$arg" in
    --asan) SANITIZE="-fsanitize=address,undefined -fno-omit-frame-pointer"; NAME="flac-decode-tests-asan" ;;
    --tsan) SANITIZE="-fsanitize=thread"; NAME="flac-decode-tests-tsan" ;;
    *) echo "opción desconocida: $arg" >&2; exit 2 ;;
  esac
done

echo "== Compilando el arnés =="
g++ -std=c++17 -O2 -g -Wall -Wextra -Wno-unused-parameter -pthread $SANITIZE \
  -I"$ROOT/app/src/main/cpp" \
  "$HERE/FlacDecodeTests.cpp" \
  "$ROOT/app/src/main/cpp/audio/FlacDecoder.cpp" \
  -o "$OUT/$NAME"

echo "== Ejecutando =="
"$OUT/$NAME" "$FIXTURES"
