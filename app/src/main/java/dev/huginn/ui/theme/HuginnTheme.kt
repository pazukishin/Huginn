package dev.huginn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tokens de `huginn-ui-spec.md` §3. La spec da medidas en px de panel; aquí se
 * traducen a dp/sp razonables para la densidad de la Thor (se validará en M1 y, si
 * el texto queda pequeño, se suben 1-2 sp antes que tocar la estructura).
 */
object HuginnColors {
    val Bg = Color(0xFF05060A)
    val Panel = Color(0xFF0C0E14)
    val Surface = Color(0xFF151823)
    val Surface2 = Color(0xFF1D2130)
    val Border = Color(0xFF252B3B)
    val Text = Color(0xFFF1F3F8)
    val Muted = Color(0xFF9BA3B4)
    val Dim = Color(0xFF6B7385)
    val Accent = Color(0xFFF4C542)
    val AccentInk = Color(0xFF1A1405)
    val SelBg = Color(0xFF2A2410)
    val SelBorder = Color(0xFF5C4C15)
    val Ok = Color(0xFF4CD2A5)
    val Warn = Color(0xFFFFB454)
    val Err = Color(0xFFFF6B6B)
}

object HuginnTypography {
    val display = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp)
    val secondary = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp)
    val micro = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
}

private val huginnDarkScheme = darkColorScheme(
    primary = HuginnColors.Accent,
    onPrimary = HuginnColors.AccentInk,
    background = HuginnColors.Bg,
    onBackground = HuginnColors.Text,
    surface = HuginnColors.Panel,
    onSurface = HuginnColors.Text,
    surfaceVariant = HuginnColors.Surface,
    onSurfaceVariant = HuginnColors.Muted,
    outline = HuginnColors.Border,
    error = HuginnColors.Err,
)

@Composable
fun HuginnTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // La app es OLED oscura siempre (spec §2.4); no se sigue el tema del sistema.
    MaterialTheme(colorScheme = huginnDarkScheme, content = content)
}
