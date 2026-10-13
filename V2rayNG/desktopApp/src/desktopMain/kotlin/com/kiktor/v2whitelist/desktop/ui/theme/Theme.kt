package com.kiktor.v2whitelist.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBg = Color(0xFF0D1117)
val DarkSurface = Color(0xFF161B22)
val DarkSurfaceElevated = Color(0xFF1F242C)
val DarkBorder = Color(0xFF30363D)

val AccentCyan = Color(0xFF00E5FF)
val AccentMint = Color(0xFF00F5D4)
val AccentPurple = Color(0xFF7928CA)
val AccentAmber = Color(0xFFFFB703)
val AccentRed = Color(0xFFFF3366)

val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)
val TextTertiary = Color(0xFF6E7681)

private val DesktopDarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    secondary = AccentMint,
    onSecondary = Color.Black,
    tertiary = AccentPurple,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = AccentRed,
    onError = Color.White
)

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DesktopDarkColorScheme,
        content = content
    )
}
