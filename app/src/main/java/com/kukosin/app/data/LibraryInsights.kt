package com.kukosin.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Favoritos y reproducciones de archivos locales, conservados entre sesiones. */
object LibraryInsights {
    private var favoritesPreferences: SharedPreferences? = null
    private var countsPreferences: SharedPreferences? = null

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (favoritesPreferences != null) return
        val app = context.applicationContext
        val favorites = app.getSharedPreferences("library_favorites", Context.MODE_PRIVATE)
        val counts = app.getSharedPreferences("library_play_counts", Context.MODE_PRIVATE)
        favoritesPreferences = favorites
        countsPreferences = counts
        _favorites.value = favorites.getStringSet("uris", emptySet())?.toSet().orEmpty()
        _playCounts.value = counts.all.mapNotNull { (key, value) ->
            (value as? Int)?.takeIf { it > 0 }?.let { key to it }
        }.toMap()
    }

    @Synchronized
    fun toggleFavorite(uri: String) {
        val preferences = favoritesPreferences ?: return
        val updated = _favorites.value.toMutableSet()
        if (!updated.add(uri)) updated.remove(uri)
        val snapshot = updated.toSet()
        _favorites.value = snapshot
        preferences.edit().putStringSet("uris", snapshot).apply()
    }

    /**
     * URIs de las pistas más probables de abrir primero: favoritos y las más escuchadas.
     *
     * Se usa para precargar sus carátulas en la caché de memoria de Coil antes de que la UI
     * las pida. Se guardan así, en el orden en que conviene pedirlas (favoritos primero).
     */
    fun recentAndFavorites(limit: Int = 24): List<String> {
        val favorites = _favorites.value
        val counts = _playCounts.value
        val ordered = ArrayList<String>(limit)
        favorites.forEach { if (ordered.size < limit) ordered.add(it) }
        counts.entries
            .sortedByDescending { it.value }
            .forEach { if (ordered.size < limit && it.key !in favorites) ordered.add(it.key) }
        return ordered
    }

    /** Cuenta un inicio real, no la preparación en pausa ni cada vez que se reanuda. */
    @Synchronized
    fun recordPlay(uri: String) {
        val preferences = countsPreferences ?: return
        val old = _playCounts.value[uri] ?: 0
        val count = if (old == Int.MAX_VALUE) old else old + 1
        _playCounts.value = _playCounts.value + (uri to count)
        preferences.edit().putInt(uri, count).apply()
    }
}
