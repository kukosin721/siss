#!/usr/bin/env bash
# Tipado rápido (sin Android SDK): compila los archivos de audio, EQ y búsqueda por
# letra junto a stubs mínimos de Android/Media3 con kotlinc.
#
#   KOTLINC=~/kotlinc/bin/kotlinc ./tools/native-tests/kt-check.sh
#
# No reemplaza `./gradlew test`: sólo detecta errores de tipos/API en la frontera
# entre el motor nativo y Media3 cuando no hay SDK disponible.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$HERE/build/kt-check}"

mkdir -p "$OUT"
# OutputMode, HardwareVolume y VolumeControl son Kotlin puro: se compilan los archivos
# REALES. (Antes OutputMode estaba duplicado en un stub y se desincronizó: ese drift ya
# rompió el CI una vez.)
#
# Las corrutinas vienen con el compilador (kotlinx-coroutines-core-jvm.jar): así los
# stubs no tienen que inventar Flow/MutableStateFlow, que es donde se esconden los errores.
COROUTINES="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlinx-coroutines-core-jvm.jar"
CP=""
if [ -f "$COROUTINES" ]; then CP="-cp $COROUTINES"; fi
# Los stubs .java existen porque algunos imports (constantes `static` de una clase Java)
# no se pueden reproducir desde Kotlin.
#
# OJO: no meter comentarios DENTRO de esta lista de continuaciones `\`: un `#` corta
# el comando y los archivos siguientes dejan de compilarse (sin error visible).
"$KOTLINC" -nowarn $CP \
  "$ROOT"/tools/kt-check/stubs/*.kt \
  "$ROOT"/tools/kt-check/stubs/java/androidx/media3/exoplayer/DefaultRenderersFactory.java \
  "$ROOT"/tools/kt-check/stubs/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/NextRenderersFactory.java \
  "$ROOT"/app/src/main/java/com/kukosin/app/audio/AudioEngine.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/audio/OboeAudioOutput.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/audio/OboeAudioOutputProvider.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/Biquad.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqModel.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqEngine.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/data/analysis/LyricsIndex.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/data/DiskFingerprint.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/data/LibraryCacheStore.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/DacHardwareVolume.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/OutputMode.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/audio/AudioEngineStatus.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/HardwareVolume.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/VolumeControl.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/FlacDecoderBridge.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/FlacPcmExtractor.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/LocalFlacMediaSourceFactory.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/HiFiRenderersFactory.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/EqAudioProcessor.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/PcmCodec.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/data/StartupCache.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/playback/UsbDacManager.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqManager.kt \
  -d "$OUT"
echo "Sin errores de tipado."
