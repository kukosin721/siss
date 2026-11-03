#!/usr/bin/env bash
# Arnés del ecualizador (DSP en la JVM, sin Android ni dispositivo).
#
#   ./tools/eq-tests/run.sh
#
# Compila los archivos Kotlin puros del EQ junto al sondeo y ejecuta las mediciones
# (respuesta en frecuencia real del filtro, headroom automático, estabilidad del motor
# tras barridos, coherencia entre la curva dibujada y la medida).
#
# Requiere `kotlinc` (o KOTLINC=/ruta/a/kotlinc).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$HERE/build}"
JAR="$OUT/eq-probe.jar"

mkdir -p "$OUT"
"$KOTLINC" -nowarn \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/Biquad.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqModel.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqEngine.kt \
  "$HERE"/EqProbe.kt \
  -include-runtime -d "$JAR"

java -jar "$JAR"
