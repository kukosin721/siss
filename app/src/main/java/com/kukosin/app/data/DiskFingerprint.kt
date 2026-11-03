package com.kukosin.app.data

/**
 * Firma del archivo en disco: tamaño + fecha de modificación.
 *
 * Es lo que permite reutilizar lo ya sondeado **sólo si el archivo no cambió**. La clave
 * de la caché es la uri, y una uri no cambia cuando el usuario reemplaza el MP3 por otro
 * con el mismo nombre; sin esta firma la app mostraba duración, bitrate y carátula del
 * archivo anterior.
 *
 * Kotlin puro (sin Android): se puede probar en la JVM, y por eso está en su propio
 * archivo en vez de anidado dentro de `LibraryCacheStore`.
 */
data class DiskFingerprint(val size: Long, val modifiedMs: Long) {

    /** `true` si [other] describe el mismo estado del archivo. */
    fun matches(other: DiskFingerprint?): Boolean =
        other != null && other.size == size && other.modifiedMs == modifiedMs

    /** Formato guardado en el JSON de la caché: `"tamaño:mtime"`. */
    val encoded: String get() = "$size:$modifiedMs"

    companion object {
        /**
         * Lee una firma guardada. Devuelve `null` si falta o está corrupta: en ese caso
         * la entrada se considera válida (no se puede comparar) en vez de forzar un
         * re-sondeo completo en cada arranque.
         */
        fun parse(raw: String?): DiskFingerprint? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val size = parts[0].toLongOrNull() ?: return null
            val modified = parts[1].toLongOrNull() ?: return null
            if (size < 0 || modified < 0) return null
            return DiskFingerprint(size, modified)
        }
    }
}
