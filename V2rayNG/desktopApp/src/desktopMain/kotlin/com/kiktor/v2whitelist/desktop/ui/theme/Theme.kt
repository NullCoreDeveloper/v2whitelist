package com.kiktor.v2whitelist.desktop.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

// Базовые темные цвета
val DarkBg = Color(0xFF0D1117)
val DarkSurface = Color(0xFF161B22)
val DarkSurfaceElevated = Color(0xFF1F242C)
val DarkBorder = Color(0xFF30363D)

// AMOLED цвета
val AmoledBg = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0A)
val AmoledSurfaceElevated = Color(0xFF121212)
val AmoledBorder = Color(0xFF222222)

// Светлые цвета
val LightBg = Color(0xFFF6F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFF0F2F5)
val LightBorder = Color(0xFFD0D7DE)
val LightTextPrimary = Color(0xFF1F2328)
val LightTextSecondary = Color(0xFF656D76)

// Палитры акцентных цветов
val AccentCyan = Color(0xFF00E5FF)
val AccentBlue = Color(0xFF2979FF)
val AccentMint = Color(0xFF00F5D4)
val AccentPurple = Color(0xFF9D4EDD)
val AccentAmber = Color(0xFFFFB703)
val AccentRose = Color(0xFFFF4D6D)
val AccentRed = Color(0xFFFF3366)

// Тексты для тёмных тем
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)
val TextTertiary = Color(0xFF6E7681)

fun getAccentColor(palette: String): Color {
    return when (palette.uppercase()) {
        "BLUE" -> AccentBlue
        "MINT" -> AccentMint
        "PURPLE" -> AccentPurple
        "AMBER" -> AccentAmber
        "ROSE" -> AccentRose
        else -> AccentCyan
    }
}

fun buildDarkScheme(palette: String, isAmoled: Boolean): androidx.compose.material3.ColorScheme {
    val accent = getAccentColor(palette)
    val bg = if (isAmoled) AmoledBg else DarkBg
    val surf = if (isAmoled) AmoledSurface else DarkSurface
    val elevated = if (isAmoled) AmoledSurfaceElevated else DarkSurfaceElevated
    val border = if (isAmoled) AmoledBorder else DarkBorder

    return darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        secondary = when (palette.uppercase()) {
            "MINT" -> AccentCyan
            "PURPLE" -> AccentRose
            "AMBER" -> AccentMint
            else -> AccentMint
        },
        onSecondary = Color.Black,
        tertiary = AccentAmber,
        background = bg,
        onBackground = TextPrimary,
        surface = surf,
        onSurface = TextPrimary,
        surfaceVariant = elevated,
        onSurfaceVariant = TextSecondary,
        outline = border,
        error = AccentRed,
        onError = Color.White
    )
}

fun buildLightScheme(palette: String): androidx.compose.material3.ColorScheme {
    val accent = getAccentColor(palette)

    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = AccentBlue,
        onSecondary = Color.White,
        tertiary = AccentAmber,
        background = LightBg,
        onBackground = LightTextPrimary,
        surface = LightSurface,
        onSurface = LightTextPrimary,
        surfaceVariant = LightSurfaceElevated,
        onSurfaceVariant = LightTextSecondary,
        outline = LightBorder,
        error = AccentRed,
        onError = Color.White
    )
}

/**
 * Определяет тёмную тему на уровне ОС (Windows: реестр AppsUseLightTheme, macOS: AppleInterfaceStyle, Linux: gsettings).
 * Возвращает null, если определить не удалось.
 */
private fun detectOsDarkTheme(): Boolean? {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return try {
        when {
            os.contains("windows") -> {
                val proc = ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor(2, TimeUnit.SECONDS)
                val match = Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x([0-9a-fA-F]+)""").find(out)
                val valInt = match?.groupValues?.getOrNull(1)?.toIntOrNull(16)
                when (valInt) {
                    0 -> true
                    1 -> false
                    else -> null
                }
            }
            os.contains("mac") -> {
                val proc = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val ok = proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0
                if (ok && out.equals("Dark", ignoreCase = true)) true else false
            }
            else -> {
                val proc = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                    when {
                        out.contains("prefer-dark", ignoreCase = true) -> true
                        out.contains("prefer-light", ignoreCase = true) || out.contains("default", ignoreCase = true) -> false
                        else -> null
                    }
                } else null
            }
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun DesktopTheme(
    themeMode: String = "DARK", // "SYSTEM", "LIGHT", "DARK"
    palette: String = "CYAN",   // "CYAN", "BLUE", "MINT", "PURPLE", "AMBER", "ROSE"
    isAmoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val composeSystemDark = isSystemInDarkTheme()
    val systemIsLight by produceState(initialValue = !composeSystemDark, themeMode, composeSystemDark) {
        if (!themeMode.equals("SYSTEM", ignoreCase = true)) {
            value = themeMode.equals("LIGHT", ignoreCase = true)
            return@produceState
        }
        while (isActive) {
            val osDark = withContext(Dispatchers.IO) { detectOsDarkTheme() }
            value = !(osDark ?: composeSystemDark)
            delay(3000L)
        }
    }

    val isLight = when {
        themeMode.equals("LIGHT", ignoreCase = true) -> true
        themeMode.equals("SYSTEM", ignoreCase = true) -> systemIsLight
        else -> false
    }
    val colorScheme = if (isLight) {
        buildLightScheme(palette)
    } else {
        buildDarkScheme(palette, isAmoled)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Градиентный фон (Mesh Gradient)
 * Режимы:
 * - "STATIC": статический мягкий рассеянный градиент
 * - "BREATHING": плавное медленное перетекание (как в Android версии)
 * - "REACTIVE": при активном подключении градиент стягивается и зеленеет к кнопке
 */
@Composable
fun MeshGradientBackground(
    enabled: Boolean,
    mode: String = "STATIC", // "STATIC", "BREATHING", "REACTIVE"
    connected: Boolean = false,
    accentColor: Color = AccentCyan,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier = modifier) {
            content()
        }
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mesh_gradient")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val color1: Color
            val color2: Color
            val centerOffset1: Offset
            val centerOffset2: Offset

            when (mode.uppercase()) {
                "BREATHING" -> {
                    val shiftX1 = cos(phase.toDouble()).toFloat() * (w * 0.12f)
                    val shiftY1 = sin(phase.toDouble()).toFloat() * (h * 0.10f)
                    val shiftX2 = sin(phase.toDouble() * 0.8).toFloat() * (w * 0.15f)
                    val shiftY2 = cos(phase.toDouble() * 0.8).toFloat() * (h * 0.12f)

                    centerOffset1 = Offset(w * 0.30f + shiftX1, h * 0.35f + shiftY1)
                    centerOffset2 = Offset(w * 0.75f + shiftX2, h * 0.65f + shiftY2)
                    color1 = accentColor.copy(alpha = 0.38f + sin(phase.toDouble()).toFloat() * 0.08f)
                    color2 = AccentPurple.copy(alpha = 0.32f + cos(phase.toDouble()).toFloat() * 0.06f)
                }
                "REACTIVE" -> {
                    if (connected) {
                        centerOffset1 = Offset(w * 0.50f, h * 0.45f) // Стягивание к центральной кнопке
                        centerOffset2 = Offset(w * 0.50f, h * 0.55f)
                        color1 = AccentMint.copy(alpha = 0.45f)
                        color2 = Color(0xFF00E676).copy(alpha = 0.30f)
                    } else {
                        centerOffset1 = Offset(w * 0.25f, h * 0.35f)
                        centerOffset2 = Offset(w * 0.80f, h * 0.65f)
                        color1 = accentColor.copy(alpha = 0.35f)
                        color2 = AccentAmber.copy(alpha = 0.22f)
                    }
                }
                else -> { // STATIC
                    centerOffset1 = Offset(w * 0.30f, h * 0.35f)
                    centerOffset2 = Offset(w * 0.75f, h * 0.65f)
                    color1 = accentColor.copy(alpha = 0.36f)
                    color2 = AccentPurple.copy(alpha = 0.28f)
                }
            }

            // Базовый темный фон подложки
            drawRect(color = DarkBg)

            // Первое пятно
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color1, Color.Transparent),
                    center = centerOffset1,
                    radius = (w.coerceAtLeast(h)) * 0.75f
                )
            )

            // Второе пятно для глубины и насыщенности Mesh
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color2, Color.Transparent),
                    center = centerOffset2,
                    radius = (w.coerceAtLeast(h)) * 0.65f
                )
            )
        }

        content()
    }
}
