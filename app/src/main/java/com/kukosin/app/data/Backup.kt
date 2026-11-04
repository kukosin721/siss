package com.kukosin.app.data

import android.content.Context
import com.kukosin.app.eq.EqBand
import com.kukosin.app.eq.EqBandType
import com.kukosin.app.eq.EqManager
import com.kukosin.app.eq.EqSettings
import com.kukosin.app.playback.AudioSettingsManager
import com.kukosin.app.ui.AnimationSpeed
import com.kukosin.app.ui.BlurStrength
import com.kukosin.app.ui.FontWeightOption
import com.kukosin.app.ui.UiSettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copia de seguridad de todo lo que la app guarda **sin** la música en sí.
 *
 * Qué entra y por qué:
 *  - Playlists (nombre + URIs, en el orden en que quedaron).
 *  - Favoritos y contadores de reproducción: son meses de uso, no se pueden reconstruir.
 *  - Ecualizador: preset, preamp, bandas con frecuencia/Q/tipo y `autoHeadroom`.
 *  - Ajustes de reproducción y de interfaz que alguien eligió a mano.
 *
 * Qué **no** entra: la biblioteca (se reconstruye escaneando) y las carátulas de playlist
 * (son imágenes en `files/`; exportarlas haría el JSON enormemente más pesado para un dato
 * que se puede volver a elegir en dos toques).
 *
 * Restaurar es **aditivo**: las playlists se agregan si el nombre no existe, los favoritos
 * se unen y los contadores se quedan con el máximo de los dos lados. Nunca borra nada que
 * ya estuviera en el teléfono.
 */
object Backup {

    const val MIME = "application/json"
    private const val FORMAT = "kukosin-backup"
    private const val VERSION = 1

    data class Result(
        val playlists: Int,
        val favorites: Int,
        val plays: Int,
        val eq: Boolean,
        val settings: Int,
        val skipped: Int,
    )

    fun suggestedName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "kukosin-backup-$stamp.json"
    }

    /** Serializa todo a un `JSONObject` legible (indentado: si algún día hay que mirarlo a mano). */
    fun export(context: Context): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("app", "kukosin")

        val playlists = JSONArray()
        PlaylistStore.playlists.value.forEach { playlist ->
            playlists.put(
                JSONObject()
                    .put("name", playlist.name)
                    .put("createdAt", playlist.createdAt)
                    .put("uris", JSONArray(playlist.trackUris)),
            )
        }
        root.put("playlists", playlists)

        root.put("favorites", JSONArray(LibraryInsights.favorites.value.toList()))

        val plays = JSONObject()
        LibraryInsights.playCounts.value.forEach { (uri, count) -> plays.put(uri, count) }
        root.put("playCounts", plays)

        val eq = EqManager.settings.value
        root.put("eq", JSONObject().apply {
            put("enabled", eq.enabled)
            put("preampDb", eq.preampDb.toDouble())
            put("autoHeadroom", eq.autoHeadroom)
            put("presetName", eq.presetName)
            put("bands", JSONArray().apply {
                eq.bands.forEach { band ->
                    put(
                        JSONObject()
                            .put("freqHz", band.freqHz.toDouble())
                            .put("gainDb", band.gainDb.toDouble())
                            .put("q", band.q.toDouble())
                            .put("type", band.type.name),
                    )
                }
            })
        })

        val audio = AudioSettingsManager.configState.value
        root.put("audio", JSONObject().apply {
            put("crossfadeMs", audio.crossfadeMs)
            put("replayGainMode", audio.replayGainMode)
            put("replayGainPreampDb", audio.replayGainPreampDb)
            put("volumeCurve", audio.volumeCurve)
            put("fades", audio.fades)
            put("bufferMs", audio.bufferMs)
            put("outputMode", audio.outputMode.name)
        })

        val ui = UiSettingsManager.settings.value
        root.put("ui", JSONObject().apply {
            put("animationSpeed", ui.animationSpeed.name)
            put("fontOption", ui.fontOption.name)
            put("blurStrength", ui.blurStrength.name)
        })

        return root.toString(2)
    }

    /**
     * Aplica un respaldo leído de disco. Devuelve cuántas cosas entraron, para poder
     * mostrar un resumen honesto en vez de un "listo" que no dice nada.
     */
    fun restore(context: Context, text: String): Result {
        val root = JSONObject(text)
        val format = root.optString("format", "")
        require(format == FORMAT) { "This file is not a Kukosin backup" }

        var playlistsAdded = 0
        var skipped = 0
        val existingNames = PlaylistStore.playlists.value.map { it.name.lowercase() }.toMutableSet()
        val array = root.optJSONArray("playlists")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                val uris = item.optJSONArray("uris")?.let { list ->
                    buildList {
                        for (j in 0 until list.length()) {
                            val uri = list.optString(j, "")
                            if (uri.isNotBlank()) add(uri)
                        }
                    }
                } ?: emptyList()
                if (name.isEmpty()) {
                    skipped++
                    continue
                }
                if (name.lowercase() in existingNames) {
                    // Misma lista de los dos lados: se completan las canciones que falten
                    // en vez de duplicar la playlist entera.
                    val target = PlaylistStore.playlists.value.firstOrNull { it.name.equals(name, true) }
                    if (target != null && uris.isNotEmpty()) PlaylistStore.addTracks(target.id, uris)
                    skipped++
                    continue
                }
                PlaylistStore.create(name, uris)
                existingNames.add(name.lowercase())
                playlistsAdded++
            }
        }

        var favoritesAdded = 0
        root.optJSONArray("favorites")?.let { list ->
            for (i in 0 until list.length()) {
                val uri = list.optString(i, "")
                if (uri.isBlank()) continue
                if (LibraryInsights.favorites.value.contains(uri)) continue
                LibraryInsights.restoreFavorite(uri)
                favoritesAdded++
            }
        }

        var playsRestored = 0
        root.optJSONObject("playCounts")?.let { obj ->
            obj.keys().forEach { uri ->
                val count = obj.optInt(uri, 0)
                if (count > 0 && LibraryInsights.restorePlayCount(uri, count)) playsRestored++
            }
        }

        var eqRestored = false
        root.optJSONObject("eq")?.let { eq ->
            val bands = eq.optJSONArray("bands")?.let { list ->
                buildList {
                    for (i in 0 until list.length()) {
                        val band = list.optJSONObject(i) ?: continue
                        val type = runCatching { EqBandType.valueOf(band.optString("type", "PEAK")) }
                            .getOrDefault(EqBandType.PEAK)
                        add(
                            EqBand(
                                freqHz = band.optDouble("freqHz", 1000.0).toFloat(),
                                gainDb = band.optDouble("gainDb", 0.0).toFloat(),
                                q = band.optDouble("q", 1.41).toFloat(),
                                type = type,
                            ),
                        )
                    }
                }
            }
            if (!bands.isNullOrEmpty()) {
                EqManager.update { current ->
                    EqSettings(
                        enabled = eq.optBoolean("enabled", current.enabled),
                        preampDb = eq.optDouble("preampDb", current.preampDb.toDouble()).toFloat(),
                        autoHeadroom = eq.optBoolean("autoHeadroom", current.autoHeadroom),
                        presetName = eq.optString("presetName", current.presetName),
                        bands = bands,
                    )
                }
                eqRestored = true
            }
        }

        var settingsRestored = 0
        root.optJSONObject("audio")?.let { audio ->
            if (audio.has("crossfadeMs")) {
                AudioSettingsManager.setCrossfadeMs(audio.optInt("crossfadeMs", 2000))
                settingsRestored++
            }
            if (audio.has("replayGainMode")) {
                AudioSettingsManager.setReplayGainMode(audio.optInt("replayGainMode", 0))
                settingsRestored++
            }
            if (audio.has("replayGainPreampDb")) {
                AudioSettingsManager.setReplayGainPreamp(audio.optInt("replayGainPreampDb", 0))
                settingsRestored++
            }
            if (audio.has("volumeCurve")) {
                AudioSettingsManager.setVolumeCurve(audio.optInt("volumeCurve", 1))
                settingsRestored++
            }
            if (audio.has("fades")) {
                AudioSettingsManager.setFades(audio.optBoolean("fades", true))
                settingsRestored++
            }
            if (audio.has("bufferMs")) {
                AudioSettingsManager.setBufferMs(audio.optInt("bufferMs", 250))
                settingsRestored++
            }
        }
        root.optJSONObject("ui")?.let { ui ->
            runCatching {
                UiSettingsManager.setAnimationSpeed(
                    AnimationSpeed.valueOf(ui.optString("animationSpeed", "NORMAL")),
                )
                settingsRestored++
            }
            runCatching {
                UiSettingsManager.setFontOption(
                    FontWeightOption.valueOf(ui.optString("fontOption", "DEFAULT")),
                )
                settingsRestored++
            }
            runCatching {
                UiSettingsManager.setBlurStrength(
                    BlurStrength.valueOf(ui.optString("blurStrength", "SOFT")),
                )
                settingsRestored++
            }
        }

        return Result(
            playlists = playlistsAdded,
            favorites = favoritesAdded,
            plays = playsRestored,
            eq = eqRestored,
            settings = settingsRestored,
            skipped = skipped,
        )
    }
}
