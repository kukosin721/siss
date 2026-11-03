# Fluidez y cortes al cambiar de pista (Ronda 5)

Dos problemas distintos que el usuario vive como uno solo: *"al adelantar o skippear hay un
corte"* y *"la app tarda en cargar y las animaciones no son suaves"*. Acá está qué se cambió,
por qué, y **qué se pudo medir** contra qué sólo se puede comprobar con un teléfono.

---

## 1. El corte al cambiar de pista

### 1.1 De dónde sale el corte (las tres causas, en orden de importancia)

Cuando el usuario adelanta o salta de canción, el sonido no cambia en el instante en que se
abre la pista nueva. Hay tres sumandos:

| # | Causa | Cuánto | Se puede evitar |
|---|---|---|---|
| 1 | **El buffer del HAL ya tiene audio viejo.** Antes de escuchar la muestra nueva hay que vaciar lo que el DAC ya tiene encolado. | hasta `bufferMs` (250 ms por defecto) | **No** (el hardware ya lo tiene), pero se puede achicar el buffer |
| 2 | **El preroll del arranque.** El motor no arranca hasta tener 2 ráfagas o 20 ms en su anillo. | ~20 ms | Sí |
| 3 | **Los fundidos** (25 ms de subida, 60 ms de bajada). Enmascaran clics, pero hacen que el arranque "respire". | ~85 ms | Sí (opcional) |
| 4 | **El decodificador**: abrir el archivo, leer cabeceras y decodificar el primer bloque. | variable (decenas de ms) | Parcialmente (precarga + caché) |

El motor ya descartaba su propio anillo en un seek (eso es memoria nuestra, instantáneo),
pero **esperaba al HAL**: `flush()` pedía pausa y esperaba hasta 120 ms a `PAUSED`, después
pedía flush y esperaba otros 120 ms a `FLUSHED`. En un seek suelto no se notaba; entre
canción y canción se sumaba al arranque y se escuchaba como bache.

### 1.2 Lo que se cambió

**a) `AudioEngine::flushForTransition()`** (`cpp/audio/AudioEngine.{h,cpp}`)

Mismo efecto sobre los datos (el anillo se descarta igual, el resampler se resetea, la
contabilidad se reinicia) pero **sin esperar** las transiciones del HAL: se piden y se sigue.
El flush que espera queda para el ajuste manual (rebuild del player), donde la latencia no
importa.

Lo usa `OboeAudioOutput.flush()`, que es el punto que Media3 llama antes de escribir la
primera muestra de la pista nueva. Se puede desactivar con `fastTransitions`.

**b) Preroll configurable** (`EngineConfig::prerollMs`)

`-1` = automático (lo de siempre, 2 ráfagas o 20 ms). `0` = una ráfaga: el stream arranca en
el primer `write()` en vez de esperar a tener margen.

**c) Presets de continuidad** (`playback/ContinuityPreset.kt`)

Mueven buffer + preroll + fundidos **juntos**, porque por separado no arreglan nada:

| Preset | Buffer | Preroll | Fundidos | Para quién |
|---|---|---|---|---|
| Stable | 250 ms | auto | sí | el de antes: máxima estabilidad |
| **Balanced** (por defecto) | 100 ms | 6 ms | sí | la mayoría de teléfonos y DAC USB |
| Instant | 40 ms | 0 | no | DAC USB / equipos que aguantan |

**d) Curva de volumen perceptual** (`EngineConfig::volumeCurve`, por defecto activa)

Antes el volumen era lineal en las muestras, así que el deslizador "no hacía nada" hasta el
último tercio. Ahora la ganancia es `v^2,5`. **Al 100 % da exactamente 1.0**, así que el
camino bit-perfect no se toca (hay test que lo verifica con `==`).

**e) Precarga de la pista siguiente** (`PlaybackService`)

`setPreloadConfiguration(12 s)`: Media3 deja decodificado el comienzo de la pista siguiente,
así el `next` no paga el arranque del decoder en el momento del salto.

### 1.3 Qué se midió (y qué no)

Arnés del motor en host, **121 comprobaciones** (antes 85), en plano, ASan/UBSan y TSan:

```
motor        : 121, 0 fallas   ·   --asan: 121, 0 fallas   ·   --tsan: 121, 0 fallas
```

Nuevas:

- **10a** `flushForTransition()` descarta los datos igual que `flush()`, deja el stream
  reutilizable y **no tarda** (se mide el reloj: < 5 ms con un stream que nunca confirma el
  cambio de estado, que es el peor caso).
- **10b** con `prerollMs = 0` el motor arranca con **una** ráfaga (192 frames) en vez de las
  960 del automático; el automático no cambió.
- **10c** curva perceptual: `1.0 → 1.0` exacto, monótona, nunca mayor que la recta, `0.5 →
  0.177`; la lineal devuelve exactamente el valor pedido.

**No medido (necesita dispositivo):** cuántos milisegundos gana cada preset en un DAC real.
El buffer del HAL, la concesión de exclusividad y el tiempo de apertura del driver son del
equipo; lo que sí está garantizado es que el motor ya no *agrega* esperas propias.

---

## 2. Arranque de la app

| Cambio | Archivo | Efecto |
|---|---|---|
| Caché de arranque de la biblioteca | `data/StartupCache.kt` | la lista completa se dibuja **de inmediato** desde el último escaneo; el escaneo real corre detrás y la reemplaza |
| `scanJob` único | `ui/LibraryViewModel.kt` | dos `refresh()` seguidos ya no lanzan dos escaneos en paralelo |
| Precarga de carátulas en memoria | `KukosinApp.kt` + `LibraryInsights.recentAndFavorites()` | las carátulas de favoritos y más escuchadas se resuelven antes de que la UI las pida (miniaturas de 256 px en un hilo de fondo) |
| Escritura atómica de la caché de arranque | `StartupCache.save()` | si el proceso muere a mitad, la próxima apertura no arranca con datos truncados |
| `AudioSettingsManager.init` en `Application` | `KukosinApp.kt` | los ajustes de audio ya están cargados cuando arranca el servicio |

Nota honesta: la caché de arranque es **optimista**. Mientras el escaneo real no termina, la
lista puede mostrar una canción que ya no existe o no mostrar una nueva. Se corrige sola en
segundos y sólo afecta a la vista; reproducir algo borrado da el error normal de Media3.

---

## 3. Animaciones

### 3.1 Un solo vocabulario de movimiento (`ui/Motion.kt`)

Antes cada pantalla definía su `tween(220)` o `spring(stiffness = …)`: una docena de
movimientos distintos que se sentían "pegados". Ahora hay tres curvas con criterio:

- **emphasized** (contenido: carátula, título, pantallas): entra rápido, frena suave.
- **standard** (apariciones cortas).
- **snappy** (respuesta al dedo: deslizador, cola, chips): resorte sin rebote visible.

Todas se escalan solas con el ajuste de velocidad que ya existía (entra al `Recomposer`).

### 3.2 Blur configurable (Ajustes → Animaciones → *Transition blur*)

El blur es el efecto más caro de la app (una pasada de `RenderEffect` por capa). Antes estaba
fijo en tres lugares distintos. Ahora:

| Ajuste | Radio | Para quién |
|---|---|---|
| **Off** | 0 | cero costo: la animación es la misma sin la pasada de efecto |
| **Soft** (por defecto) | 18 dp / 6 dp | el efecto sutil de siempre |
| **Strong** | ×1,7 | GPU de sobra |

Se publica por `LocalUiSettings` (lo provee `AppTheme`), así que cualquier composable lo lee
sin recibirlo por parámetro. Con **Off** el código ni siquiera llama a `Modifier.blur`.

### 3.3 Carátula con fundido cruzado (`Artwork` en `PlayerViews.kt`)

Antes, al cambiar de pista el `AsyncImage` cambiaba de modelo y la carátula se reemplazaba de
golpe (con un hueco mientras Coil resolvía la nueva). Ahora se mantiene la anterior visible y
se cruzan en 420 ms. La escritura del estado va en un `SideEffect` para no forzar una
recomposición inmediata en cada cambio.

### 3.4 Barra de progreso

Eran 420 ms **lineales** por cada actualización del reloj; con posiciones que llegan cada
250–400 ms la barra nunca alcanzaba y se veía arrastrada. Ahora 180 ms con curva suave: sigue
el reloj sin perseguirlo.

### 3.5 Lo que se intentó y **no** se hizo

- **Mover el `Recomposer` fuera del hilo principal.** Sería más rápido en composición, pero
  los `Dialog`/`Popup` de Compose crean ventanas de Android durante la composición y exigen
  un hilo con Looper. Riesgo de crash a cambio de una ganancia no medida: descartado y
  documentado en `MainActivity`.
- **Reconstruir el `ExoPlayer` en cada cambio de pista** para "forzar" el formato nuevo: es
  justamente lo que produce el corte. Se mantiene un solo player.

---

## 4. Resumen de lo que hay que tocar en el teléfono para notarlo

1. **Ajustes → No gaps between tracks → Instant** (y volver a Balanced si aparece algún
   chasquido: en un equipo saturado el buffer chico cuesta xruns).
2. **Ajustes → Transition blur → Off** si el equipo es de gama baja.
3. La primera apertura después de actualizar tarda lo normal (escaneo completo); a partir de
   la segunda, la biblioteca aparece de inmediato desde la caché de arranque.
