#!/usr/bin/env bash
# Arnés del volumen de hardware del DAC (USB Audio Class), sin Android ni dispositivo.
#
#   ./tools/dac-volume-tests/run.sh
#
# Arma descriptores USB Audio Class sintéticos (UAC1 y UAC2) y verifica el parseo de la
# Feature Unit, el rango declarado, la cuantización al paso del DAC, el recorrido útil del
# control y la confirmación por read-back.
#
# Requiere `kotlinc` (o KOTLINC=/ruta/a/kotlinc).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$HERE/build}"
JAR="$OUT/dac-volume-probe.jar"

mkdir -p "$OUT"
"$KOTLINC" -nowarn \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/DacHardwareVolume.kt \
  "$HERE"/DacVolumeProbe.kt \
  -include-runtime -d "$JAR"

java -jar "$JAR"
