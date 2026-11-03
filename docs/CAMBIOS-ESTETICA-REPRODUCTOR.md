# Reproductor: onda real, botones grandes y portada que vuela desde la fila

Cambios de esta pasada (sólo estética y las correcciones necesarias para que la
estética se vea bien; el motor de audio sigue igual).

## 1. La onda ya no se corta ni queda "en palo"

El problema tenía cuatro causas, todas corregidas:

1. **La extracción se cortaba a los 4 s.** `WaveformExtractor` decodificaba sólo el
   primer tramo del archivo; el resto quedaba con `0` y la barra lo dibujaba como una
   línea plana. Ahora:
   - Se decodifica **todo** el archivo (tope de seguridad de 45 s y vigilante de
     estancamiento de 6 s, sólo para codecs que se cuelgan).
   - Cada muestra se asigna a su barra por **número de frame / duración real**, no por
     el tamaño de los buffers del codec. La última barra corresponde al final de la
     canción.
   - Si un codec raro corta antes, `stretchBars()` reparte lo medido a lo ancho de toda
     la pista en lugar de dejar media onda y un palo.
2. **Caché corrupta en español.** La onda se guardaba con `"%.4f".format(...)`, que en
   locale es-CL escribe `0,1234`; al releerla se partía en dos números y la onda salía
   recortada o plana en la segunda apertura. Ahora se usa `Float.toString()` (siempre
   punto decimal) y `CACHE_VERSION = 3` invalida las cachés viejas.
3. **Diez pistas seguidas = diez decodificaciones en paralelo.** Ahora hay un `Mutex`
   (una por vez) y la extracción se cancela en el próximo buffer si el usuario cambió
   de canción, además de correr con prioridad de hilo *background* para no competir con
   el audio.
4. **Dibujo.** La cinta de barras es más ancha que la tarjeta y se desplaza; la aguja
   (límite entre lo escuchado y lo que falta) avanza de izquierda a derecha y la
   tarjeta **siempre queda llena de onda**. Antes la aguja estaba fija en el centro y,
   al empezar un tema, la mitad izquierda quedaba vacía (parecía que no había cargado).

Además: barras de **6.2 dp con 3.4 dp de separación** (antes 4.4/3.2), centradas
verticalmente, y revelado escalonado de izquierda a derecha cuando llega la onda real.

## 2. Botones más grandes

- Play **78 dp** (68 en pantallas ≤ 340 dp), anterior/próximo **60 dp** (52), y los dos
  laterales **44 dp** (38) con un disco translúcido detrás para que se lean sobre la onda.
- Los laterales ahora son **±10 s** (retroceso/avance) en vez de repetir shuffle/repetición:
  esas dos funciones pasaron a la tira de acciones sobre la portada.

## 3. Portada tipo Poweramp sin perder la estética Kukosin

- Portada **casi a todo el ancho** (tope 380 dp), con la fila de acciones (EQ, ruta de
  audio, repetir, shuffle) **encima de la parte baja de la portada**, como en la captura.
- Chips de título/artista alineados a la izquierda, cabecera con
  `calidad · salida` (p. ej. `FLAC 24/96 · USB DAC`).
- Barra de accesos más baja (50 dp) y menos relleno vertical para que la portada y la
  onda respiren.
- **Altura de la portada calculada con las barras del sistema**: antes se usaba el alto
  total de la ventana como si no hubiera insets, y en un teléfono de 720 dp con
  navegación de 3 botones la barra inferior quedaba cortada. Ahora se descuentan
  `statusBars` + `navigationBars` y ~250 dp de "chrome" fijo.
- La paleta sigue siendo Ink/Cream/dorado (`Gold`, `AccentTheme`); no se copió el naranja
  de Poweramp.

## 4. Animaciones más suaves (entrada y salida)

- El recorrido del reproductor ya no usa el resorte rígido de ~230 ms: ahora es un
  **tween de 520 ms al abrir y 440 ms al cerrar** con una curva que arranca con calma y
  frena largo (`Motion.sheetTravel` / `Motion.sheetEasing`). Eso es lo que hacía que
  abrir una canción se sintiera instantáneo.
- Si el recorrido viene de un **fling del dedo**, se sigue usando un resorte para no
  perder la velocidad del gesto, pero más blando (stiffness 240, damping 0.94).
- La **sombra de la carátula** florece en el aire y se apaga en los dos extremos
  (`shadowFade`), así el relevo entre la capa en vuelo, la carátula grande y la
  miniatura del dock no tiene un salto de 4 dp.
- La miniatura del **dock** se desvanece durante el 18 % inicial del recorrido en vez de
  desaparecer en el primer frame; si la portada sale desde una fila, no se ve el hueco.
- El **blur** del vuelo se apaga a partir del 82 % del recorrido, antes del relevo con la
  carátula grande (99.8 %), para que no quede un frame desenfocado y aparezca nítida de
  golpe.
- La entrada/salida de **letras** también se ablandó (resorte 240/0.95 en vez de
  320/0.88): la carátula va al rincón con la misma calma que el resto.
- Todas las duraciones siguen escalando con el ajuste de velocidad de animación del
  sistema (`MainActivity`), así que "Slow"/"Off" siguen funcionando.

## 5. La portada crece desde la fila tocada

- Cada `TrackRow` / `YtmTrackRow` publica el rectángulo de **su** carátula al tocarse
  (`TrackPressOrigin`, válido 2 s).
- Al abrir el reproductor, ese rectángulo se congela (`morphOriginRect`) y el morph lo
  usa como origen durante todo el vuelo; al llegar se descarta y el cierre vuelve al
  mini reproductor como siempre.
- Abrir desde el dock, el botón atrás o el mini reproductor limpia el origen para que no
  vuele desde una fila vieja.
- Buscar y tocar un tema local también abre el reproductor (antes sólo sonaba).

## 6. Cola: gesto desde la onda, animación y blur

- Debajo de las barras de la onda hay un tirador **QUEUE** (chevron hacia arriba). Se
  puede **tocar** o **arrastrar hacia arriba** (umbral 14 dp); la píldora sube un poco
  mientras arrastrás como confirmación y hay un tick háptico al abrir.
- La zona táctil es una franja de **44 dp de alto a todo el ancho** de la tarjeta (la
  píldora visible es más chica, centrada), en el borde inferior de la onda: el gesto
  funciona en cualquier punto de esa franja, sin mezclarse con el seek horizontal.
- La hoja de la cola ahora ocupa el **88 % del alto** y su fondo es Ink al 95 %: deja ver
  la carátula del reproductor arriba y a través del fondo.
- El reproductor de atrás se **desenfoca** (radio del ajuste de blur de la app, 0 si
  está en Off) con una animación de 260 ms sincronizada con la entrada de la hoja.
- Cerrar desde el botón (o desde el scrim/atrás) **baja la hoja con su animación** antes
  de sacarla de la composición; el blur se apaga junto con el deslizamiento.

## 7. Ecualizador

- `Flat` restaura las bandas de fábrica (antes sólo ponía las ganancias en 0 y dejaba
  vivas las bandas de corte).
- "Reset band" devuelve una banda sola a su valor de fábrica.
- Selector de **6 tipos** de filtro (peaking, low/high shelf, low/high pass, notch); Q
  sólo se muestra donde aplica y las bandas de corte no muestran ganancia (no la usan).
- Los nodos de la curva para pasa-altos/pasa-bajos/notch se dibujan sobre la **respuesta
  real** del filtro, no sobre una altura inventada.
- Los gestos de la curva leen siempre el último estado (`rememberUpdatedState`), así
  cambiar el tipo o la frecuencia de una banda no deja gestos "viejos".
- Subtítulo con `· bypass` cuando el EQ está apagado (el switch sigue manda: el DSP no
  se aplica si está off).

## 8. Oboe apagado por defecto

Oboe da problemas de audio en varios dispositivos, así que queda **desactivado por
defecto** hasta repararlo:

- El modo de salida por defecto es `DISABLED` (AudioTrack / mezclador de Android).
- **Migración de una sola vez** (`oboe_off_by_default_v1`): una instalación que tenía
  guardado `AUTO` (o cualquier modo de Oboe) pasa a `DISABLED` en el primer arranque de
  esta versión. Si después elegís Oboe a mano en Ajustes, se respeta.
- El respaldo automático cuando un modo exclusivo no tiene DAC también va a `DISABLED`
  (antes iba a `AUTO`, o sea a Oboe).
- Los cinco modos siguen en Ajustes para probar Oboe cuando haga falta; cuando esté
  reparado, se cambia el default y se quita la migración.

El EQ y el análisis automático ya venían apagados por su cuenta.

## Cómo verificar

No hay JDK/Android SDK en este entorno, así que no se pudo compilar acá. En una máquina
con Android Studio:

```bash
./gradlew test
./gradlew lint
./tools/native-tests/kt-check.sh
```

Y a mano: abrir una canción larga desde la lista (la portada debe crecer desde esa
fila), mirar la onda hasta el final (sin cola plana), probar play/pausa, ±10 s,
siguiente/anterior, EQ y shuffle/repetición, y **arrastrar hacia arriba el tirador
QUEUE** debajo de la onda (debe abrir la cola con el fondo desenfocado).
