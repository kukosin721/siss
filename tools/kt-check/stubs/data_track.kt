@file:Suppress("unused", "ClassName")

package com.kukosin.app.data

import android.net.Uri

/**
 * Stub mínimo de `Track` para el chequeo de tipos sin SDK.
 *
 * El `Track` real depende de Media3 (`toMediaItem`) y de la carga de carátulas; para
 * verificar la caché de sondeo y el volumen del DAC alcanza con los campos que usan.
 * Cualquier campo nuevo en el `Track` real que use este código debe agregarse acá.
 */
data class Track(
    val id: Long = 0L,
    val uri: Uri = Uri(),
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val durationMs: Long = 0L,
    val albumId: Long = 0L,
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val mimeType: String = "",
    val fileName: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val genre: String = "",
    val composer: String = "",
    val filePath: String = "",
    val fileSize: Long = 0L,
    val comment: String = "",
    val artworkOverride: Uri? = null,
    val streamCodecLabel: String = "",
)
