package app.pixelbridge.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.pixelbridge.android.R

/** Monochrome palette in the spirit of Link to Windows: no accent colours, outlines instead of fills. */
@Immutable
data class BridgeColors(
    val bg: Color,
    val card: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val bar: Color,
    val screen: Color,
    val screenEdge: Color,
    val laptopBase: Color,
    val dark: Boolean,
)

private val Dark = BridgeColors(
    bg = Color(0xFF000000), card = Color(0xFF161616), border = Color(0xFF2C2C2C),
    text = Color(0xFFF2F2F2), muted = Color(0xFF9E9E9E), faint = Color(0xFF5E5E5E),
    bar = Color(0xFF1F1F1F), screen = Color(0xFF2A2A2A), screenEdge = Color(0xFF4A4A4A),
    laptopBase = Color(0xFFCFCFCF), dark = true,
)

private val Light = BridgeColors(
    bg = Color(0xFFF7F7F7), card = Color(0xFFFFFFFF), border = Color(0xFFE0E0E0),
    text = Color(0xFF141414), muted = Color(0xFF6B6B6B), faint = Color(0xFFADADAD),
    bar = Color(0xFFEFEFEF), screen = Color(0xFF3A3A3A), screenEdge = Color(0xFF1E1E1E),
    laptopBase = Color(0xFFBDBDBD), dark = false,
)

val LocalBridgeColors = staticCompositionLocalOf { Dark }

@OptIn(ExperimentalTextApi::class)
private fun sansFlex(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Google Sans Flex, Google's own UI typeface (OFL), bundled as one variable font. */
val GoogleSansFlex = FontFamily(sansFlex(400), sansFlex(500), sansFlex(600))

private fun style(size: Int, weight: Int = 400, line: Int = (size * 1.35f).toInt()) =
    TextStyle(fontFamily = GoogleSansFlex, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp)

private val AppTypography = Typography(
    headlineMedium = style(26, 500),
    titleLarge = style(22, 400),
    titleMedium = style(18, 500),
    bodyLarge = style(16),
    bodyMedium = style(14),
    bodySmall = style(13),
    labelLarge = style(15, 500),
    labelMedium = style(13),
)

@Composable
fun PixelBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.text, onPrimary = c.bg, surface = c.card, background = c.bg,
            onSurface = c.text, onBackground = c.text, surfaceContainer = c.card, outline = c.border)
    } else {
        lightColorScheme(primary = c.text, onPrimary = c.bg, surface = c.card, background = c.bg,
            onSurface = c.text, onBackground = c.text, surfaceContainer = c.card, outline = c.border)
    }
    CompositionLocalProvider(LocalBridgeColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

object BridgeTheme {
    val colors: BridgeColors @Composable get() = LocalBridgeColors.current
}
