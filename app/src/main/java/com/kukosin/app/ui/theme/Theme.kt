package com.kukosin.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import com.kukosin.app.ui.FontWeightOption
import com.kukosin.app.ui.LocalUiSettings
import com.kukosin.app.ui.UiSettingsManager

/**
 * Esquema Material derivado del color de acento elegido.
 *
 * Antes era un `val` de nivel superior construido con el dorado fijo: al cambiar el acento,
 * el resto de la app se repintaba pero MaterialTheme seguía en dorado (chips, sliders,
 * ripples). Ahora se arma con el acento actual y se recalcula solo cuando cambia.
 */
private fun hifiScheme(accent: androidx.compose.ui.graphics.Color, accentDim: androidx.compose.ui.graphics.Color) =
    darkColorScheme(
        primary = accent,
        onPrimary = Ink,
        primaryContainer = accentDim,
        onPrimaryContainer = Cream,
        secondary = CreamMuted,
        onSecondary = Ink,
        background = Ink,
        onBackground = Cream,
        surface = Ink,
        onSurface = Cream,
        surfaceVariant = InkElevated,
        onSurfaceVariant = CreamMuted,
        outline = Line,
        outlineVariant = Line,
    )

/** Variante "Bold": todos los estilos base pasan a negrita. */
private fun Typography.asBold(): Typography = copy(
    displayLarge = displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = bodyLarge.copy(fontWeight = FontWeight.Bold),
    bodyMedium = bodyMedium.copy(fontWeight = FontWeight.Bold),
    bodySmall = bodySmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = labelSmall.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val accent = AccentTheme.color
    val accentDim = AccentTheme.colorDim
    val scheme = remember(accent, accentDim) { hifiScheme(accent, accentDim) }
    val uiSettings by UiSettingsManager.settings.collectAsState()
    val typography = remember(uiSettings.fontOption) {
        if (uiSettings.fontOption == FontWeightOption.BOLD) Typography.asBold() else Typography
    }

    // Los ajustes visuales (blur, velocidad) quedan disponibles para cualquier composable
    // sin pasarlos como parámetro: `LocalUiSettings.current`.
    CompositionLocalProvider(LocalUiSettings provides uiSettings) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}
