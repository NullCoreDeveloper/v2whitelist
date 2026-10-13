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

private val CyanThemeScheme = darkColorScheme(
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

private val AmoledThemeScheme = darkColorScheme(
    primary = AccentMint,
    onPrimary = Color.Black,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    tertiary = AccentAmber,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF262626),
    error = AccentRed,
    onError = Color.White
)

private val PurpleThemeScheme = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color.Black,
    secondary = Color(0xFF8C9EFF),
    onSecondary = Color.Black,
    tertiary = Color(0xFFFF4081),
    background = Color(0xFF0D0B14),
    onBackground = Color(0xFFEDE7F6),
    surface = Color(0xFF171424),
    onSurface = Color(0xFFEDE7F6),
    surfaceVariant = Color(0xFF231E38),
    onSurfaceVariant = Color(0xFFB39DDB),
    outline = Color(0xFF3B3358),
    error = AccentRed,
    onError = Color.White
)

@Composable
fun DesktopTheme(
    themeName: String = "CYAN",
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeName.uppercase()) {
        "AMOLED" -> AmoledThemeScheme
        "PURPLE" -> PurpleThemeScheme
        else -> CyanThemeScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
