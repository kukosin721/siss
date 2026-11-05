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

## 7. Playlists (crear, editar, portada y "agregar a la playlist")

- **Biblioteca → Playlists** deja de ser un cartel de "próximamente": es una rejilla con
  una tarjeta **"New playlist"** siempre primera y, arriba, **"Save queue"** cuando hay
  algo en la cola (guarda la cola actual tal cual como lista nueva).
- **Crear**: nombre libre. La lista se guarda como URIs, no como copia de la metadata: al
  abrirla, títulos, artistas y carátulas se resuelven contra la biblioteca actual.
- **Detalle** (hoja al 94 %): portada, nombre, cantidad y duración total, y los botones
  Play / Shuffle / Add songs.
- **Modificar**:
  - Tocar una fila reproduce desde esa canción.
  - **Mantener pulsado y arrastrar** reordena (las filas se corren para mostrar el hueco).
  - La ✕ saca la canción de la lista (si el archivo ya no está, la fila lo dice y también
    se puede quitar).
  - Menú (⋮): **Rename**, **Change cover**, **Remove cover**, **Add songs**, **Delete
    playlist**.
  - **Add songs**: buscador sobre la biblioteca; tocar agrega al instante y marca con ✓ lo
    que ya está.
- **Portada**: se elige con el selector de imágenes del sistema; se copia y se reduce a
  1024 px en `filesDir/playlists/covers/<id>.jpg` (JPEG 90 %), así no depende del permiso
  del archivo original y no guarda una foto de 12 MB. Sin portada, la tarjeta usa un
  degradado del acento con el ícono de cola.
- **Agregar a la playlist** aparece en el menú ⋮ de **cualquier** pista (biblioteca,
  carpetas, cola, álbum): abre una hoja con las listas, cuántas de las canciones elegidas
  ya están adentro, y un campo para **crear una lista nueva** con esas canciones sin salir
  de la hoja. Las repetidas no se duplican.

## 8. Ecualizador

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

## 9. Oboe apagado por defecto

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

## 10. Extras: ícono, selección múltiple, recientes, temporizador y M3U

- **Ícono nuevo**: la mascota (pingüino con auriculares). Se generan las cinco densidades
  (`mdpi` a `xxxhdpi`) con esquinas redondeadas y versión circular, más el ícono
  adaptativo de Android 8+ (el arte va de fondo y la máscara del launcher lo recorta).
- **Selección múltiple** en las listas: mantener pulsada una fila entra en modo selección
  (círculo ✓, se oculta el menú ⋮). La barra flotante permite **agregar a una playlist**,
  **agregar a la cola**, **reproducir a continuación** o cancelar. Tocar una fila
  seleccionada la desmarca; al vaciar la selección el modo se cierra solo.
- **Recientes** (Biblioteca → Recently played): usa un timestamp de última reproducción por
  pista (un solo valor, no un historial), ordena de más nuevo a más viejo y muestra
  "5 min ago" / "3 h ago" / "2 d ago" en la segunda línea.
- **Temporizador de sueño**: quinto acceso en la barra del reproductor (luna). Opciones de
  5 a 90 min; en los últimos 8 segundos el volumen baja en rampa y después pausa y vuelve
  al 100 %. Con uno andando queda un punto dorado en el ícono y el diálogo muestra cuánto
  falta. Funciona igual en AudioTrack, Oboe y DAC USB porque sólo usa el volumen del
  reproductor.
- **M3U/M3U8**: importar desde la categoría Playlists (botón de subir) y exportar desde el
  menú ⋮ de cada lista. Al importar, cada línea se resuelve por URI, por ruta (normalizando
  `\` de Windows) y por nombre de archivo; lo que no está en el teléfono se descarta. Al
  exportar se escribe `#EXTM3U` + `#EXTINF:duración,Artista - Título` + ruta del archivo,
  que es lo que leen Poweramp, Musicolet y VLC.

## 11. Transiciones entre pistas y volumen parejo

- **Crossfade**: al cambiar de pista el tema que termina baja y el que empieza sube. Se
  elige en Ajustes → *Track Transitions & Volume* (Off, 0.5 s a 8 s; por defecto 2 s). El
  fundido se calcula por reloj dentro de la cadena de audio, así que no depende del tamaño
  del buffer ni reinicia el stream. Con *Off* vuelve el corte seco (disco en vivo, DJ mix).
- **Gapless**: los discos pensados para escucharse de corrido siguen pegados; el fundido
  sólo actúa cuando hay salto real entre pistas.
- **ReplayGain**: lee `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN` de los archivos y
  nivela el volumen (Off / Track / Album, más pre-ganancia de -6 a +6 dB). Las etiquetas se
  leen en segundo plano y quedan cacheadas por archivo; los temas sin etiqueta no se tocan.
  El valor se recorta entre -24 y +12 dB: una etiqueta mal escrita no puede saturar ni
  dejar el tema mudo.
- Las dos etapas viven en `GainAudioProcessor`, **antes** del ecualizador. Cuando no hay
  nada configurado copian los bytes sin tocarlos, igual que el EQ en bypass.

## 12. Respaldo y restauración

Un solo archivo JSON (Ajustes → *Backup*) con playlists, favoritos, contadores de
reproducción, ecualizador y ajustes de audio e interfaz. Restaurar es **aditivo**: nunca
borra nada y, si una playlist ya existe con el mismo nombre, sólo le agrega las canciones
que le faltaban. Sirve para cambiar de teléfono sin perder meses de uso.

## 13. Widget y botón de ajustes rápidos

- **Widget** de 4×1: carátula, título, artista y play/pausa/anterior/siguiente. Los botones
  despachan eventos de media a la sesión activa, así que funcionan aunque la app esté
  cerrada o el proceso se haya recreado.
- **Tile** en los ajustes rápidos: con algo cargado alterna play/pausa; sin nada, abre la
  app. Muestra el título como subtítulo y se pinta activo cuando suena.

## 14. Visualizador de espectro

32 barras sobre la carátula, alimentadas por el audio real que pasa por la cadena (FFT de
512 puntos, ventana de Hann, reparto logarítmico y caída suave). Se apaga en Ajustes →
*Visualizer*; apagado, la etapa de análisis no decodifica nada.

## 15. Controles del reproductor, sin ruido encima de la portada

- **La carátula quedó limpia**: se fue el botón redondo del ecualizador (se abre desde la
  barra de accesos, que es donde ya estaba) y también el de la ruta de señal.
- **Ruta de señal abajo de la onda**, como chip permanente: `dispositivo · bits · kHz`
  (por ejemplo `Pixel 8 · 24-bit · 96 kHz`), leído de la pista en reproducción y del
  dispositivo activo. Tocarlo abre el detalle de la ruta, igual que antes.
- **Repetir y aleatorio** quedaron centrados en la franja inferior de la portada, en vez de
  repartidos en las esquinas con los otros dos botones.
- **La cola ya no tiene botón**: se abre solamente con el gesto hacia arriba desde la
  franja debajo de las barras. La zona táctil sigue siendo todo el ancho de la tarjeta y
  dispara apenas se pasa el umbral, pero no se dibuja ninguna píldora "QUEUE".

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
siguiente/anterior, EQ y shuffle/repetición, **arrastrar hacia arriba el tirador
QUEUE** debajo de la onda (debe abrir la cola con el fondo desenfocado), y en Biblioteca →
Playlists crear una lista, meterle canciones desde el menú ⋮ de una pista, cambiarle la
portada y reordenarla manteniendo pulsado. También: mantener pulsada una fila para
seleccionar varias y agregarlas de una vez, importar/exportar un `.m3u8`, y probar el
temporizador de sueño con 5 minutos (debe bajar el volumen y pausar).
