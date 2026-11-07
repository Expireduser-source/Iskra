package ru.iskra.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Her own icons (0.16.0), instead of emoji that look different on every phone: round line icons on a 24 grid, 2 px
 * strokes with round ends — soft like the rest of her. Tinted by [androidx.compose.material3.Icon].
 */
object IskraIcons {
    val Send by lazy { line("send", "M12 19V5", "M6 11l6-6 6 6", width = 2.4f) }
    val Mic by lazy { line("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M5 11a7 7 0 0 0 14 0", "M12 18v3") }
    val Stop by lazy { solid("stop", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z") }
    val Chat by lazy { line("chat", "M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.9A8 8 0 1 1 21 12z") }
    val Book by lazy { line("book", "M12 6.5C10 5 7 4.5 4 5v13c3-.5 6 0 8 1.5 2-1.5 5-2 8-1.5V5c-3-.5-6 0-8 1.5z", "M12 6.5V19") }
    val Tune by lazy {
        line("tune", "M4 8h10", "M18 8h2", "M18 8a2 2 0 1 1-4 0a2 2 0 1 1 4 0", "M4 16h2", "M10 16h10", "M10 16a2 2 0 1 1-4 0a2 2 0 1 1 4 0")
    }
    val Shield by lazy { line("shield", "M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z", "M9 12l2 2 4-4") }
    val ChevronDown by lazy { line("down", "M6 9l6 6 6-6", width = 2.4f) }
    val Close by lazy { line("close", "M6 6l12 12", "M18 6L6 18") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.4f) }
    val Moon by lazy { line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val Bell by lazy { line("bell", "M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0") }
    val Note by lazy { line("note", "M6 4h8l4 4v12H6z", "M14 4v4h4", "M9 13h6", "M9 16.5h4") }
    val Person by lazy { line("person", "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0", "M4.5 20a7.5 7.5 0 0 1 15 0") }
    val Circle by lazy { line("circle", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0") }
    val CheckCircle by lazy { line("check-circle", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0", "M8.5 12.3l2.4 2.4 4.6-4.8") }

    /** The four-pointed star of her name (Искра — a spark). */
    val Sparkle by lazy { solid("sparkle", "M12 3C12.8 8.2 15.8 11.2 21 12C15.8 12.8 12.8 15.8 12 21C11.2 15.8 8.2 12.8 3 12C8.2 11.2 11.2 8.2 12 3Z") }

    private fun builder(name: String) = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)

    private fun line(name: String, vararg paths: String, width: Float = 2f): ImageVector = builder(name).apply {
        for (d in paths) addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun solid(name: String, vararg paths: String): ImageVector = builder(name).apply {
        for (d in paths) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
    }.build()
}
