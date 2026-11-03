package com.kukosin.app.ui.theme

import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0B0F)
val InkElevated = Color(0xFF16161C)
val InkSoft = Color(0xFF1F1F27)
val Cream = Color(0xFFF4EDE4)
val CreamMuted = Color(0xFFB7AFA6)
val Line = Color(0xFF2A2A33)

/**
 * Acento de la app.
 *
 * Es dinámico: el usuario puede cambiarlo en Configuración → Personalización. Al leerlo
 * durante la composición se registra la dependencia del estado, así que toda la interfaz se
 * repinta sola cuando cambia el tono (antes era un `val` fijo al dorado).
 */
val Gold: Color
    get() = AccentTheme.color

/** Variante oscura del acento. */
val GoldDim: Color
    get() = AccentTheme.colorDim
