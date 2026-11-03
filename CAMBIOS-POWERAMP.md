# Navegación estilo Poweramp

## Reproductor

- El reproductor es la pantalla inicial, incluso si todavía no hay canciones o permiso de audio.
- Los accesos inferiores abren **Biblioteca**, **Ecualizador**, **Búsqueda** y **Configuración**. La búsqueda mantiene tanto la biblioteca local como YouTube Music.
- Deslizar la carátula hacia arriba abre las letras: la portada se reduce y se mueve a la esquina superior izquierda junto a título, artista y calidad. También se puede tocar la carátula para abrirlas. El gesto se reconoce en un contenedor fijo para que la aparición del panel no interrumpa el arrastre.
- Para cerrarlas, arrastra hacia abajo la cabecera/caratulita, toca la flecha de letras o usa Atrás. El panel usa las letras embebidas que ya reconoce Kukosin; no descarga letras de internet.
- El gesto horizontal sigue cambiando de pista; el gesto vertical hacia abajo sigue minimizando el reproductor. La transición compartida se conserva tanto desde la vista normal como desde la vista de letras.
- La onda es más alta (128 dp; 108 dp en pantallas compactas) y mantiene la aguja fija y los controles integrados. El corazón superior marca o desmarca una canción local como favorita.
- Las secciones de Biblioteca, Ecualizador y Búsqueda entran y salen con el morph del reproductor; Configuración tiene una transición propia de entrada y salida.

## Biblioteca

Las 12 categorías son: Todas las canciones, Carpetas, Álbumes, Artistas, Artistas del álbum, Géneros, Años, Compositores, Listas de reproducción, La cola, Favoritos y Más reproducido.

Las categorías por etiquetas usan los metadatos de los archivos. Carpetas incluye directorios intermedios y otras ubicaciones. La cola se actualiza con el reproductor y permite abrir el editor existente.

**Favoritos y Más reproducido** funcionan para pistas locales y se guardan entre sesiones. Para el ranking se cuenta cada inicio real de una pista; preparar una pista en pausa o pausar/reanudar no suma. No se agregan automáticamente reproducciones anteriores a esta versión.

**Ecualizador y listas de reproducción son solo interfaz por ahora**: el ecualizador todavía no altera el audio y las listas aún no se guardan.

## Comprobación manual sugerida en Android

1. Abrir sin permiso y concederlo desde el reproductor; comprobar también el estado de biblioteca vacía.
2. Reproducir un álbum, deslizar horizontalmente en ambas direcciones y probar el primer y último tema.
3. Deslizar la carátula hacia arriba, hacer scroll en letras, volver atrás, y minimizar/abrir el reproductor también desde letras.
4. Probar la onda, los botones de reproducción, favoritos y el ranking tras reproducir varias canciones.
5. Explorar cada categoría, carpetas anidadas y cola; comprobar Atrás desde una subcarpeta y desde la raíz de Biblioteca.
6. Revisar búsqueda local y YouTube Music, configuración, y la ruta de señal/permisos USB si hay un DAC conectado.

Las pruebas de unidad se ejecutan con `./gradlew :app:testDebugUnitTest` y el APK de depuración se genera con `./gradlew :app:assembleDebug`. Las animaciones, los gestos reales y el audio requieren además una prueba en un dispositivo o emulador Android.
