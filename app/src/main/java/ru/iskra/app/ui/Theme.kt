package ru.iskra.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Lilac = Color(0xFFB98CFF)
val Bg = Color(0xFF17121F)
val Card = Color(0xFF241B30)
val Muted = Color(0xFFA796BD)

/** Follows the system: dark or light. Text colours keep ≥ 4.5:1 contrast on their backgrounds in both. */
@Composable
fun IskraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Lilac, onPrimary = Color(0xFF221433),
            secondary = Color(0xFFFF8FB8),
            background = Bg, surface = Card, onSurface = Color(0xFFF2EAFB), onBackground = Color(0xFFF2EAFB),
            surfaceVariant = Color(0xFF2E2440), onSurfaceVariant = Muted,
        ) else lightColorScheme(
            primary = Color(0xFF6B3FC4), onPrimary = Color.White,
            secondary = Color(0xFFAD2A66),
            background = Color(0xFFFBF8FF), surface = Color(0xFFF1EAFA), onSurface = Color(0xFF1E1726), onBackground = Color(0xFF1E1726),
            surfaceVariant = Color(0xFFE6DCF2), onSurfaceVariant = Color(0xFF574A66),
        ),
        content = content,
    )
}
