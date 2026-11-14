package dev.dnote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** DNote design tokens (docs/DESIGN.md). */
@Immutable
data class DNColors(
    val dark: Boolean,
    val bg: Color,
    val chrome: Color,
    val surface: Color,
    val text: Color,
    val text2: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val hover: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
)

val LightDN = DNColors(
    dark = false,
    bg = Color(0xFFFFFFFF),
    chrome = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF0A0C10),
    text2 = Color(0xFF334155),
    muted = Color(0xFF64748B),
    faint = Color(0xFF94A3B8),
    border = Color(0xFFE2E8F0),
    hover = Color(0xFFEEF2F7),
    accent = Color(0xFF2563EB),
    accentSoft = Color(0xFF2563EB).copy(alpha = 0.08f),
    success = Color(0xFF16A34A),
)

val DarkDN = DNColors(
    dark = true,
    bg = Color(0xFF0A0C10),
    chrome = Color(0xFF0E1117),
    surface = Color(0xFF141821),
    text = Color(0xFFF5F7FA),
    text2 = Color(0xFFCBD5E1),
    muted = Color(0xFF94A3B8),
    faint = Color(0xFF64748B),
    border = Color(0xFF1E2430),
    hover = Color(0xFF161B24),
    accent = Color(0xFF3B82F6),
    accentSoft = Color(0xFF3B82F6).copy(alpha = 0.14f),
    success = Color(0xFF22C55E),
)

val LocalDN = staticCompositionLocalOf { LightDN }

/** Tag colours, by name (see dnote_core::workspace::TAG_COLORS). */
fun tagColor(name: String): Color = when (name) {
    "amber" -> Color(0xFFF59E0B)
    "blue" -> Color(0xFF3B82F6)
    "green" -> Color(0xFF22C55E)
    "red" -> Color(0xFFEF4444)
    "violet" -> Color(0xFF8B5CF6)
    "pink" -> Color(0xFFEC4899)
    else -> Color(0xFF94A3B8)
}

@Composable
fun DNoteTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val dn = if (dark) DarkDN else LightDN
    val scheme = if (dark) {
        darkColorScheme(
            primary = dn.accent, onPrimary = Color.White,
            primaryContainer = dn.accentSoft, onPrimaryContainer = dn.accent,
            secondaryContainer = dn.accentSoft, onSecondaryContainer = dn.text,
            background = dn.bg, onBackground = dn.text,
            surface = dn.bg, onSurface = dn.text, onSurfaceVariant = dn.muted,
            surfaceContainer = dn.surface, surfaceContainerLow = dn.chrome,
            surfaceContainerHigh = dn.surface, surfaceContainerHighest = dn.hover,
            surfaceContainerLowest = dn.bg,
            outline = dn.faint, outlineVariant = dn.border,
            error = Color(0xFFF87171),
        )
    } else {
        lightColorScheme(
            primary = dn.accent, onPrimary = Color.White,
            primaryContainer = dn.accentSoft, onPrimaryContainer = dn.accent,
            secondaryContainer = dn.accentSoft, onSecondaryContainer = dn.text,
            background = dn.bg, onBackground = dn.text,
            surface = dn.bg, onSurface = dn.text, onSurfaceVariant = dn.muted,
            surfaceContainer = dn.chrome, surfaceContainerLow = dn.chrome,
            surfaceContainerHigh = dn.surface, surfaceContainerHighest = dn.hover,
            surfaceContainerLowest = dn.bg,
            outline = dn.faint, outlineVariant = dn.border,
            error = Color(0xFFDC2626),
        )
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
    CompositionLocalProvider(LocalDN provides dn) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}

/** "DNote" with the D in accent blue. */
@Composable
fun wordmark() = buildAnnotatedString {
    withStyle(SpanStyle(color = LocalDN.current.accent)) { append("D") }
    withStyle(SpanStyle(color = LocalDN.current.text)) { append("Note") }
}
