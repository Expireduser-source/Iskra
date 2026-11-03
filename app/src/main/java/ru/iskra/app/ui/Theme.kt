package ru.iskra.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Lilac = Color(0xFFB98CFF)
val Bg = Color(0xFF17121F)
val Card = Color(0xFF241B30)
val Muted = Color(0xFFA796BD)

@Composable
fun IskraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lilac, onPrimary = Color(0xFF221433),
            secondary = Color(0xFFFF8FB8),
            background = Bg, surface = Card, onSurface = Color(0xFFF2EAFB), onBackground = Color(0xFFF2EAFB),
            surfaceVariant = Color(0xFF2E2440), onSurfaceVariant = Muted,
        ),
        content = content,
    )
}
