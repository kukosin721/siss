package com.kukosin.app.playback

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kukosin.app.audio.AudioEngineStatus
import com.kukosin.app.data.LibraryInsights
import com.kukosin.app.data.analysis.EmbeddedMetadata
import com.kukosin.app.widget.WidgetBridge
import com.kukosin.app.data.ytm.YtmService
import com.kukosin.app.data.ytm.describeYtmError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

@UnstableApi
class PlaybackService : MediaSessionService() {
    private companion object {
        const val TAG = "KukosinPlayback"

        /**
         * Cambiar de modo de salida y después el buffer (o tocar dos ajustes seguidos)
         * generaba dos reconstrucciones con dos aperturas de stream. Con el debounce se
         * hace una sola, 350 ms después del último cambio.
         */
        const val SETTINGS_DEBOUNCE_MS = 350L

        /**
         * Cuánto audio de la pista siguiente se deja decodificado y listo. 12 s alcanzan
         * para que el cambio de pista no tenga que esperar al decoder; más que eso sólo
         * ocupa memoria sin que se note.
         */
        const val PRELOAD_AHEAD_US = 12_000_000L
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var settingsJob: Job? = null
    private var restoringCurrentItem = false
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val playbackStartTracker = PlaybackStartTracker { key ->
        val scheme = Uri.parse(key).scheme
        if (scheme == "content" || scheme == "file") LibraryInsights.recordPlay(key)
    }
    private val playbackCountListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playbackStartTracker.onTransition(
                key = mediaItem?.localConfiguration?.uri?.toString(),
                preserveIfSame = restoringCurrentItem && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                isPlaying = player?.isPlaying == true,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playbackStartTracker.onPlayingChanged(
                key = player?.currentMediaItem?.localConfiguration?.uri?.toString(),
                isPlaying = isPlaying,
            )
        }
    }

    // ── Fundidos entre pistas + ReplayGain ────────────────────────────────────
    //
    // El fundido de salida lo arma un vigilante que mira cuánto falta para el final; el de
    // entrada lo arma la transición. Como el segundo llega recién cuando ExoPlayer cambió
    // de ítem, `fadeOutArmed` evita que el vigilante vuelva a armar la salida en el nuevo
    // tema (lo que dejaría el volumen bajando para siempre).
    @Volatile private var fadeOutArmed = false

    private val gainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            fadeOutArmed = false
            val config = AudioSettingsManager.configState.value
            if (config.crossfadeMs > 0) {
                // Arranca en cero: la primera muestra del tema nuevo no chasquea.
                PlaybackGain.snapTo(0f)
                PlaybackGain.fadeIn(minOf(config.crossfadeMs, 1500))
            } else {
                PlaybackGain.snapTo(1f)
            }
            applyReplayGain(mediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                // Pausa a mitad de un fundido: se restaura la ganancia para que al
                // reanudar no suene bajito para siempre.
                PlaybackGain.snapTo(1f)
            }
        }
    }

    // ── Estado para el widget y el tile ───────────────────────────────────────
    //
    // Se publica en cada cambio de metadatos y de reproducción. Es la única fuente: el
    // widget y el botón de ajustes rápidos leen de acá (vía preferencias), así que no
    // pueden mostrar algo distinto de lo que está sonando.
    private val widgetListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            publishWidgetState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishWidgetState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishWidgetState()
        }
    }

    private fun publishWidgetState() {
        val p = player ?: return
        val metadata = p.currentMediaItem?.mediaMetadata
        runCatching {
            WidgetBridge.update(
                context = this,
                title = metadata?.title?.toString() ?: p.currentMediaItem?.localConfiguration?.uri?.lastPathSegment,
                artist = metadata?.artist?.toString() ?: metadata?.albumArtist?.toString(),
                artUri = metadata?.artworkUri,
                isPlaying = p.isPlaying,
            )
        }
    }

    private fun startFadeWatchdog() {
        serviceScope.launch {
            while (isActive) {
                val p = player
                val config = AudioSettingsManager.configState.value
                if (p != null && p.isPlaying && config.crossfadeMs > 0 && !fadeOutArmed) {
                    val duration = p.duration
                    val fade = config.crossfadeMs.toLong()
                    if (duration != C.TIME_UNSET && duration > fade * 2 && p.hasNextMediaItem()) {
                        val remaining = duration - p.currentPosition
                        if (remaining in 1..fade) {
                            fadeOutArmed = true
                            PlaybackGain.fadeOut(config.crossfadeMs)
                        }
                    }
                }
                delay(200)
            }
        }
    }

    private val replayGainCache = HashMap<String, Float>()

    /**
     * Aplica la etiqueta ReplayGain del tema actual.
     *
     * La lectura de etiquetas abre el archivo, así que nunca ocurre en el hilo de audio ni
     * en el de la UI: se hace en IO y el resultado queda cacheado por URI + modo. Mientras
     * tanto suena con la pre-ganancia sola, que es un cambio de volumen mínimo y sólo la
     * primera vez que se reproduce ese archivo.
     */
    private fun applyReplayGain(item: MediaItem?) {
        val config = AudioSettingsManager.configState.value
        if (config.replayGainMode == 0) {
            PlaybackGain.setReplayGainDb(0f)
            return
        }
        val preamp = config.replayGainPreampDb.toFloat()
        val uri = item?.localConfiguration?.uri
        if (uri == null) {
            PlaybackGain.setReplayGainDb(preamp)
            return
        }
        val key = "${config.replayGainMode}|$uri"
        val cached = synchronized(replayGainCache) { replayGainCache[key] }
        if (cached != null) {
            PlaybackGain.setReplayGainDb(cached + preamp)
            return
        }
        PlaybackGain.setReplayGainDb(preamp)
        val album = config.replayGainMode == 2
        serviceScope.launch(Dispatchers.IO) {
            val gain = readReplayGainDb(uri, album) ?: return@launch
            synchronized(replayGainCache) { replayGainCache[key] = gain }
            withContext(Dispatchers.Main) {
                val current = AudioSettingsManager.configState.value
                val currentUri = player?.currentMediaItem?.localConfiguration?.uri
                if (current.replayGainMode != 0 && currentUri == uri) {
                    PlaybackGain.setReplayGainDb(gain + current.replayGainPreampDb)
                }
            }
        }
    }

    private fun readReplayGainDb(uri: Uri, album: Boolean): Float? {
        val snapshot = runCatching {
            EmbeddedMetadata.extract(this, uri, uri.path ?: "")
        }.getOrNull() ?: return null
        val label = if (album) "ReplayGain Album" else "ReplayGain Track"
        val raw = snapshot.fields.firstOrNull { it.first == label }?.second ?: return null
        val number = Regex("[-+]?\\d+([.,]\\d+)?").find(raw)?.value ?: return null
        return number.replace(',', '.').toFloatOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        AudioSettingsManager.init(this)
        // El volumen del sistema se observa (nunca se escribe): es la fuente de verdad y
        // la app sólo lo refleja. Escribirlo era lo que hacía que el volumen "subiera
        // solo" al empezar cada pista.
        VolumeControl.observe(this)
        // El EQ debe estar cargado antes de construir el sink: el procesador lee su estado
        // desde acá.
        com.kukosin.app.eq.EqManager.init(this)
        startRecreateWorker()
        recreatePlayer()
        startFadeWatchdog()

        // Sólo se reconstruye el player si cambió algo que afecta al AUDIO. Antes se
        // recreaba con cualquier ajuste (incluso el aspecto de la cola) y eso cortaba la
        // reproducción sin motivo. El `drop(1)` descarta el valor inicial del StateFlow.
        settingsJob = AudioSettingsManager.configState
            .drop(1)
            .map { it.audioSignature }
            .distinctUntilChanged()
            .debounce(SETTINGS_DEBOUNCE_MS)
            .onEach { recreatePlayer() }
            .catch { throwable -> Log.e(TAG, "settings stream failed", throwable) }
            .launchIn(serviceScope)
    }

    private var audioRetries: Int = 0
    private var retryJob: Job? = null
    private var retrying = false

    /**
     * Una sola reconstrucción a la vez, y **como máximo una pendiente**.
     *
     * Antes había una bandera `recreating` con recursión al terminar: si el build
     * fallaba y quedaba un pedido pendiente, se llamaba a `recreatePlayer()` otra vez
     * desde el `finally`, y eso podía encadenarse sin fin bloqueando el hilo principal.
     * Un `Channel` conflado resuelve lo mismo sin recursión: los pedidos que llegan
     * mientras se reconstruye se descartan salvo el último.
     */
    private var recreating = false
    private val recreateRequests = Channel<Unit>(Channel.CONFLATED)

    private fun startRecreateWorker() {
        serviceScope.launch {
            for (request in recreateRequests) {
                if (recreating) continue
                recreating = true
                try {
                    rebuildPlayerOnMain()
                } catch (throwable: Throwable) {
                    Log.e(TAG, "no se pudo reconstruir el reproductor", throwable)
                    AudioEngineStatus.error(
                        "Could not restart the audio engine: ${throwable.message ?: "unknown error"}",
                    )
                } finally {
                    recreating = false
                }
            }
        }
    }

    /**
     * Recuperación de la salida de audio: DAC desenchufado a mitad de tema, stream exclusivo
     * rechazado por el HAL o modo "DAC USB" sin DAC. Se reconstruye el player una vez,
     * conservando pista y posición (`recreatePlayer` ya guarda el snapshot).
     */
    private val audioErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // 5000..5099 = familia ERROR_CODE_*_AUDIO_*. Se compara por rango para no
            // depender de constantes que cambian de nombre entre versiones de Media3.
            val audioSinkFailure = error.errorCode in 5000..5099
            AudioEngineStatus.error(
                if (audioSinkFailure) {
                    "Audio output failed (${error.errorCode}). Retrying with the current route…"
                } else {
                    "Playback error (${error.errorCode}): ${error.message ?: "no details"}"
                },
            )
            if (!audioSinkFailure || audioRetries >= 1 || retrying) return
            audioRetries++
            retrying = true
            retryJob?.cancel()
            retryJob = serviceScope.launch {
                delay(300)
                try {
                    if ((player?.mediaItemCount ?: 0) > 0) recreatePlayer()
                } finally {
                    retrying = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) audioRetries = 0
        }

        // No se reinicia el contador en STATE_READY: un sink roto puede pasar por READY
        // y fallar en el primer write. Si se resetea ahí, el reintento se repite para
        // siempre (READY → error → rebuild → READY → error) y la app queda reconstruyendo
        // el reproductor sin parar. El contador sólo se limpia cuando la reproducción
        // realmente arrancó (arriba), que es la señal de que la salida funciona.
    }

    /**
     * Pide una reconstrucción del reproductor conservando cola, pista y posición.
     *
     * El pedido entra a un `Channel` conflado y lo procesa [startRecreateWorker] en el
     * hilo principal, de a uno. Nada se libera hasta que el reproductor nuevo está
     * construido: si el build falla, la reproducción actual sigue intacta.
     */
    private fun recreatePlayer() {
        recreateRequests.trySend(Unit)
    }

    /**
     * Reconstrucción en el hilo principal (ExoPlayer exige crear y liberar en su hilo
     * de aplicación). El orden importa:
     *
     * 1. Se construyen el reproductor y la sesión **nuevos**.
     * 2. Se publican (`player` / `mediaSession`) sin dejar un hueco en el que el
     *    servicio se quede sin sesión.
     * 3. Lo viejo se libera en el siguiente turno del hilo principal, fuera del frame
     *    del toque que cambió el ajuste. `release()` espera al hilo de audio y, con
     *    Oboe, puede tardar; hacerlo dentro del clic era lo que congelaba la pantalla.
     */
    private fun rebuildPlayerOnMain() {
        val currentItems = player?.let { p ->
            val count = p.mediaItemCount
            if (count == 0) return@let null
            val list = List(count) { i -> p.getMediaItemAt(i) }
            val index = p.currentMediaItemIndex
            val pos = p.currentPosition
            val isPlaying = p.isPlaying
            Triple(list, index, pos to isPlaying)
        }

        val mediaSourceFactory = buildMediaSourceFactory()
        val newPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(HiFiRenderersFactory(this))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSkipSilenceEnabled(false)
            .build()
        // `volume = 1f` = "el reproductor no atenúa". NO es el volumen del usuario: el
        // volumen real lo aplica el DAC (atenuador USB), el sistema o el motor nativo,
        // según el modo. Un valor menor acá multiplicaría la señal dos veces (esto es
        // parte de lo que "subía solo" el volumen al empezar cada pista).
        newPlayer.volume = 1f

        // Precarga de la pista siguiente: con el buffer de carga lleno de antemano, el
        // "next" no arranca desde cero (ni el decoder ni el extractor pagan su costo en el
        // momento en que el usuario pide el salto). El límite es de tiempo, no de cantidad:
        // precargar más de una pista en una cola larga sólo gasta batería y RAM.
        newPlayer.setPreloadConfiguration(
            androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration(
                /* targetPreloadDurationUs= */ PRELOAD_AHEAD_US,
            ),
        )

        AudioRoute.attach(this, newPlayer)
        newPlayer.addListener(prefetchListener)
        newPlayer.addListener(playbackCountListener)
        newPlayer.addListener(gainListener)
        newPlayer.addListener(widgetListener)
        // El reproductor nuevo arranca neutro: si venía de una pista con ReplayGain, el
        // factor viejo no debe aplicarse a la primera muestra del nuevo sink.
        PlaybackGain.reset()
        newPlayer.currentMediaItem?.let { applyReplayGain(it) }
        newPlayer.addListener(audioErrorListener)

        // La sesión nueva se crea antes de tocar la vieja. Si el builder falla, el
        // reproductor nuevo se descarta y la sesión actual sigue viva.
        //
        // El ID por defecto es la cadena vacía y Media3 sólo permite una sesión con
        // ese ID por proceso. Como acá conviven un instante la vieja y la nueva, cada
        // una necesita su propio ID; si no, `build()` lanza "Session ID must be
        // unique" y el cambio de ajuste deja el reproductor sin sesión.
        val newSession = try {
            MediaSession.Builder(this, newPlayer)
                .setId("kukosin-${System.nanoTime()}")
                .build()
        } catch (throwable: Throwable) {
            runCatching { newPlayer.release() }
            throw throwable
        }

        val oldPlayer = player
        val oldSession = mediaSession

        mediaSession = newSession
        player = newPlayer

        oldPlayer?.let { old ->
            old.removeListener(prefetchListener)
            old.removeListener(playbackCountListener)
            old.removeListener(audioErrorListener)
        }

        if (currentItems != null && currentItems.first.isNotEmpty()) {
            val (items, index, posPlaying) = currentItems
            restoringCurrentItem = true
            try {
                newPlayer.setMediaItems(items, index, posPlaying.first)
                newPlayer.prepare()
            } finally {
                restoringCurrentItem = false
            }
            if (posPlaying.second) {
                newPlayer.play()
            }
        }

        // Fuera del frame actual: la UI ya dibujó el ajuste nuevo y el control sigue
        // conectado a la sesión nueva mientras el reproductor viejo termina de cerrar.
        Handler(Looper.getMainLooper()).post {
            runCatching { oldPlayer?.release() }
            runCatching { oldSession?.release() }
        }
    }

    /**
     * Fuente de datos con resolución diferida para YouTube Music.
     *
     * Los MediaItem de YTM usan el esquema [YtmService.STREAM_SCHEME]; acá se cambia esa URI
     * simbólica por la URL real de googlevideo en el momento de abrir el stream. Cualquier otra
     * URI (archivos locales, http normal) pasa intacta, así que la reproducción local no cambia.
     */
    private fun buildMediaSourceFactory(): MediaSource.Factory {
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { dataSpec ->
            resolveStreamDataSpec(dataSpec)
        }
        val default = DefaultMediaSourceFactory(this).setDataSourceFactory(resolving)
        // Los FLAC locales se decodifican con el decodificador propio (PCM entero en el
        // ancho nativo del archivo). Sin esto, un FLAC de 24 bits pasa por MediaCodec y se
        // queda en float o en 16 bits antes de llegar al DAC.
        return LocalFlacMediaSourceFactory(this, resolving, default)
    }

    private fun resolveStreamDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != YtmService.STREAM_SCHEME) return dataSpec
        val videoId = dataSpec.uri.lastPathSegment ?: return dataSpec

        val startedAt = System.currentTimeMillis()
        val audio = try {
            // El resolver es síncrono por contrato (se ejecuta en el hilo de carga de ExoPlayer).
            runBlocking { YtmService.resolveAudio(videoId) }
        } catch (throwable: Throwable) {
            Log.w(TAG, "stream $videoId failed: ${describeYtmError(throwable)}")
            throw IOException(
                "Could not resolve YouTube Music audio: ${describeYtmError(throwable)}",
                throwable,
            )
        }
        Log.d(
            TAG,
            "stream $videoId -> ${audio.codecLabel} ${audio.bitrateKbps}kbps " +
                "(${System.currentTimeMillis() - startedAt} ms)",
        )
        return dataSpec.withUri(audio.url.toUri())
    }

    /**
     * Resuelve en segundo plano el audio del tema que viene.
     *
     * Resolver una URL de YouTube Music tarda ~1,4 s en frío. Si se hace recién al abrir el
     * stream, entre canción y canción se siente un silencio largo. Acá se llena la caché de
     * [YtmService] con anticipación, así el "next" arranca casi instantáneo.
     */
    private val prefetchListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            player?.let { prefetchNext(it) }
        }
    }

    private fun prefetchNext(player: Player) {
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val uri = player.getMediaItemAt(nextIndex).localConfiguration?.uri ?: return
        if (uri.scheme != YtmService.STREAM_SCHEME) return
        val videoId = uri.lastPathSegment ?: return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { YtmService.resolveAudio(videoId) }
                .onSuccess { Log.d(TAG, "prefetch $videoId listo") }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Si el usuario saca la app de recientes y no hay nada cargado en el reproductor,
     * el servicio se apaga: no tiene sentido quedar vivo en segundo plano sin cola.
     * Si hay algo cargado (aunque esté en pausa) no se toca, porque puede volver a
     * sonar desde la notificación o desde Android Auto.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        if (currentPlayer == null || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        retryJob?.cancel()
        serviceScope.cancel()
        player?.removeListener(audioErrorListener)
        player?.removeListener(prefetchListener)
        player?.removeListener(playbackCountListener)
        AudioRoute.detach()
        mediaSession?.run {
            release()
        }
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
