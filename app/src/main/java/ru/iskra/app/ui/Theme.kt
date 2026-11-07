package ru.iskra.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.iskra.app.R

/**
 * Nunito (SIL Open Font License 1.1, tools/fonts/OFL.txt): rounded and warm like her, with Cyrillic. Four static weights
 * with only Latin, Cyrillic and the signs replies use (~42 KB each in the APK), made from the variable font by
 * tools/fonts/make_fonts.py; the copyright and the license stay in each file's names table.
 */
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
)

private val base = Typography()
private fun TextStyle.nunito(weight: FontWeight? = null) = copy(fontFamily = Nunito, fontWeight = weight ?: fontWeight)

private val IskraType = Typography(
    displayLarge = base.displayLarge.nunito(FontWeight.Bold),
    displayMedium = base.displayMedium.nunito(FontWeight.Bold),
    displaySmall = base.displaySmall.nunito(FontWeight.Bold),
    headlineLarge = base.headlineLarge.nunito(FontWeight.Bold),
    headlineMedium = base.headlineMedium.nunito(FontWeight.Bold),
    headlineSmall = base.headlineSmall.nunito(FontWeight.Bold).copy(letterSpacing = 0.sp),
    titleLarge = base.titleLarge.nunito(FontWeight.Bold),
    titleMedium = base.titleMedium.nunito(FontWeight.Bold),
    titleSmall = base.titleSmall.nunito(FontWeight.Bold),
    bodyLarge = base.bodyLarge.nunito().copy(lineHeight = 23.sp, letterSpacing = 0.2.sp),
    bodyMedium = base.bodyMedium.nunito().copy(lineHeight = 20.sp),
    bodySmall = base.bodySmall.nunito().copy(lineHeight = 17.sp),
    labelLarge = base.labelLarge.nunito(FontWeight.Bold),
    labelMedium = base.labelMedium.nunito(FontWeight.SemiBold),
    labelSmall = base.labelSmall.nunito(FontWeight.SemiBold),
)

private val IskraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Her own colours beside Material's: the owner's bubbles in a violet-to-magenta gradient, hers light and soft, the
 * glow around her and the three colours of her sparks. White text on both ends of the gradient keeps ≥ 4.5:1.
 */
@Immutable
class IskraColors(
    val dark: Boolean,
    val userBubble: Brush,
    val onUserBubble: Color,
    val herBubble: Color,
    val onHerBubble: Color,
    /** Behind her head in a portrait. */
    val faceBack: Brush,
    /** Her glow. */
    val aura: Color,
    /** The soft tint at the top of the chat, fading out. */
    val skyTop: Color,
    val sparkGold: Color,
    val sparkPink: Color,
    val sparkLilac: Color,
    val success: Color,
    val inputBar: Color,
    /** The thin ring around her portrait at rest. */
    val ring: Color,
) {
    val sparks get() = listOf(sparkGold, sparkPink, sparkLilac, Color.White)
}

private val LightIskra = IskraColors(
    dark = false,
    userBubble = Brush.linearGradient(listOf(Color(0xFF7A4BE0), Color(0xFFA44DC9))),
    onUserBubble = Color.White,
    herBubble = Color.White,
    onHerBubble = Color(0xFF1E1726),
    faceBack = Brush.verticalGradient(listOf(Color(0xFFF3EAFF), Color(0xFFE4D3FF))),
    aura = Color(0xFFB98CFF),
    skyTop = Color(0xFFEFE4FF),
    sparkGold = Color(0xFFFFB13D),
    sparkPink = Color(0xFFFF6FA5),
    sparkLilac = Color(0xFF9B6BFF),
    success = Color(0xFF1E7A4A),
    inputBar = Color.White,
    ring = Color(0xFFD9C8F5),
)

private val DarkIskra = IskraColors(
    dark = true,
    userBubble = Brush.linearGradient(listOf(Color(0xFF6C3FD1), Color(0xFF9440B8))),
    onUserBubble = Color.White,
    herBubble = Color(0xFF241D2F),
    onHerBubble = Color(0xFFEEE6F8),
    faceBack = Brush.verticalGradient(listOf(Color(0xFF34284A), Color(0xFF261C38))),
    aura = Color(0xFF8E5CFF),
    skyTop = Color(0xFF23183A),
    sparkGold = Color(0xFFFFC46B),
    sparkPink = Color(0xFFFF8FB8),
    sparkLilac = Color(0xFFB98CFF),
    success = Color(0xFF7BD8A0),
    inputBar = Color(0xFF211A2B),
    ring = Color(0xFF453A57),
)

private val LocalIskraColors = staticCompositionLocalOf { LightIskra }

/** [IskraColors] of the current theme. */
val iskraColors: IskraColors
    @Composable @ReadOnlyComposable get() = LocalIskraColors.current

/** Follows the system: dark or light. Text colours keep ≥ 4.5:1 contrast on their backgrounds in both. */
@Composable
fun IskraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFC9A8FF), onPrimary = Color(0xFF2E1065),
        primaryContainer = Color(0xFF4E2C93), onPrimaryContainer = Color(0xFFEBDDFF),
        secondary = Color(0xFFFF8FB8), onSecondary = Color(0xFF4A0A2A),
        secondaryContainer = Color(0xFF6E1E45), onSecondaryContainer = Color(0xFFFFD9E4),
        tertiary = Color(0xFFFFC46B), onTertiary = Color(0xFF462A00),
        tertiaryContainer = Color(0xFF643F00), onTertiaryContainer = Color(0xFFFFDDB3),
        background = Color(0xFF120E19), onBackground = Color(0xFFEEE6F8),
        surface = Color(0xFF120E19), onSurface = Color(0xFFEEE6F8),
        surfaceVariant = Color(0xFF2E2540), onSurfaceVariant = Color(0xFFBFB2D3),
        surfaceTint = Color(0xFFC9A8FF),
        inverseSurface = Color(0xFFEEE6F8), inverseOnSurface = Color(0xFF332D3B), inversePrimary = Color(0xFF6B3FC4),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8F80A3), outlineVariant = Color(0xFF41374F),
        surfaceBright = Color(0xFF3A3049), surfaceDim = Color(0xFF120E19),
        surfaceContainerLowest = Color(0xFF0D0A13), surfaceContainerLow = Color(0xFF1A1523),
        surfaceContainer = Color(0xFF1F1929), surfaceContainerHigh = Color(0xFF2A2236),
        surfaceContainerHighest = Color(0xFF352C42),
    ) else lightColorScheme(
        primary = Color(0xFF6B3FC4), onPrimary = Color.White,
        primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF250059),
        secondary = Color(0xFFAD2A66), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFD9E4), onSecondaryContainer = Color(0xFF3E0021),
        tertiary = Color(0xFF855400), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB3), onTertiaryContainer = Color(0xFF2A1800),
        background = Color(0xFFFBF8FF), onBackground = Color(0xFF1E1726),
        surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1E1726),
        surfaceVariant = Color(0xFFE9DFF4), onSurfaceVariant = Color(0xFF574A66),
        surfaceTint = Color(0xFF6B3FC4),
        inverseSurface = Color(0xFF332D3B), inverseOnSurface = Color(0xFFF6EEFF), inversePrimary = Color(0xFFD4BBFF),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF7E7090), outlineVariant = Color(0xFFD3C6E2),
        surfaceBright = Color(0xFFFBF8FF), surfaceDim = Color(0xFFDCD4E6),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F1FD),
        surfaceContainer = Color(0xFFF1EAFA), surfaceContainerHigh = Color(0xFFEBE3F6),
        surfaceContainerHighest = Color(0xFFE5DCF1),
    )
    CompositionLocalProvider(LocalIskraColors provides if (dark) DarkIskra else LightIskra) {
        MaterialTheme(colorScheme = scheme, typography = IskraType, shapes = IskraShapes, content = content)
    }
}
