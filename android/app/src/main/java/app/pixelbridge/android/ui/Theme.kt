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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours that Material's scheme doesn't cover: background gradient, pastel icon tiles, status dots. */
@Immutable
data class BridgeColors(
    val bgTop: Color,
    val bgBottom: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val muted: Color,
    val blueTile: Color, val blueIcon: Color,
    val greenTile: Color, val greenIcon: Color,
    val violetTile: Color, val violetIcon: Color,
    val amberTile: Color, val amberIcon: Color,
    val online: Color,
    val waiting: Color,
    val offline: Color,
    val laptopBezel: Color,
    val laptopBase: Color,
    val dark: Boolean,
)

private val Light = BridgeColors(
    bgTop = Color(0xFFD9E2FF), bgBottom = Color(0xFFF6F7FB),
    card = Color.White, cardBorder = Color(0xFFE5E8F2),
    text = Color(0xFF12152A), muted = Color(0xFF6A7090),
    blueTile = Color(0xFFE6EEFF), blueIcon = Color(0xFF3E5BFF),
    greenTile = Color(0xFFE3F6EC), greenIcon = Color(0xFF16955B),
    violetTile = Color(0xFFEFE9FF), violetIcon = Color(0xFF7448F5),
    amberTile = Color(0xFFFFF1DD), amberIcon = Color(0xFFD9820B),
    online = Color(0xFF1FB36B), waiting = Color(0xFFF0A020), offline = Color(0xFFE5484D),
    laptopBezel = Color(0xFF1C2131), laptopBase = Color(0xFFC8CDDA),
    dark = false,
)

private val Dark = BridgeColors(
    bgTop = Color(0xFF1C2448), bgBottom = Color(0xFF0D1120),
    card = Color(0xFF161B2D), cardBorder = Color(0xFF252B44),
    text = Color(0xFFEEF0FA), muted = Color(0xFF9AA1BF),
    blueTile = Color(0xFF1F2A55), blueIcon = Color(0xFF8DA2FF),
    greenTile = Color(0xFF15372A), greenIcon = Color(0xFF5BD69A),
    violetTile = Color(0xFF2B2152), violetIcon = Color(0xFFB59BFF),
    amberTile = Color(0xFF3A2C14), amberIcon = Color(0xFFFFC266),
    online = Color(0xFF34D17F), waiting = Color(0xFFFFB547), offline = Color(0xFFFF6B6F),
    laptopBezel = Color(0xFF05070D), laptopBase = Color(0xFF3B4260),
    dark = true,
)

val LocalBridgeColors = staticCompositionLocalOf { Light }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    )
}

val Brand = Color(0xFF3E5BFF)

@Composable
fun PixelBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF8DA2FF), onPrimary = Color(0xFF0B1240),
            surface = c.card, background = c.bgBottom, onSurface = c.text, onBackground = c.text)
    } else {
        lightColorScheme(primary = Brand, onPrimary = Color.White,
            surface = c.card, background = c.bgBottom, onSurface = c.text, onBackground = c.text)
    }
    CompositionLocalProvider(LocalBridgeColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

object BridgeTheme {
    val colors: BridgeColors @Composable get() = LocalBridgeColors.current
}

val Muted: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(color = BridgeTheme.colors.muted)
