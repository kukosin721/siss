#!/usr/bin/env bash
# Corre la verificación del ecualizador sin Android (perfiles, migración, persistencia).
# Requiere: JDK 11+, kotlinc y kotlinx-coroutines-core-jvm.jar en KC.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KC="${KC:?definí KC con la ruta al compilador de Kotlin (ej: /opt/kotlinc)}"
COROUTINES="${COROUTINES:?definí COROUTINES con la ruta a kotlinx-coroutines-core-jvm.jar}"
OUT="$(mktemp -d)"
javac -d "$OUT/stubs" "$ROOT"/tools/android-stubs/android/content/*.java
"$KC/bin/kotlinc" -nowarn -cp "$COROUTINES:$OUT/stubs" -d "$OUT/app" \
  "$ROOT"/tools/EqProfileHarness.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/Biquad.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqModel.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqEngine.kt \
  "$ROOT"/app/src/main/java/com/kukosin/app/eq/EqManager.kt
java -cp "$OUT/app:$KC/lib/kotlin-stdlib.jar:$COROUTINES:$OUT/stubs" HarnessKt
