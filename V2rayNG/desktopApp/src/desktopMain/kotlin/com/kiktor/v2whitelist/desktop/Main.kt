package com.kiktor.v2whitelist.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import com.kiktor.v2whitelist.desktop.ui.components.Sidebar
import com.kiktor.v2whitelist.desktop.ui.screens.AppearanceScreen
import com.kiktor.v2whitelist.desktop.ui.screens.GeekModeScreen
import com.kiktor.v2whitelist.desktop.ui.screens.MainScreen
import com.kiktor.v2whitelist.desktop.ui.screens.SettingsScreen
import com.kiktor.v2whitelist.desktop.ui.screens.SubscriptionsScreen
import com.kiktor.v2whitelist.desktop.ui.theme.DesktopTheme
import com.kiktor.v2whitelist.desktop.ui.theme.MeshGradientBackground
import com.kiktor.v2whitelist.desktop.ui.theme.getAccentColor
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Загружает оригинальную иконку приложения из ресурсов (icon.png).
 * Если файл не найден — рисует fallback иконку с буквами "V2".
 */
private fun loadAppIcon(connected: Boolean): BitmapPainter {
    val stream = object {}::class.java.classLoader?.getResourceAsStream("icon.png")
    if (stream != null) {
        try {
            val img = ImageIO.read(stream)
            if (img != null) {
                val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
                val g = result.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(img, 0, 0, null)
                if (connected) {
                    val r = img.width / 5
                    val x = img.width - r - img.width / 12
                    val y = img.height - r - img.height / 12
                    g.color = Color(0, 230, 120)
                    g.fillOval(x, y, r, r)
                    g.color = Color(0, 0, 0, 180)
                    g.drawOval(x, y, r, r)
                }
                g.dispose()
                return BitmapPainter(result.toComposeImageBitmap())
            }
        } catch (_: Exception) {}
    }

    val size = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = if (connected) Color(0, 200, 100) else Color(20, 20, 20)
    g.fillRoundRect(2, 2, size - 4, size - 4, 14, 14)
    g.color = Color.WHITE
    g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 22)
    val fm = g.fontMetrics
    val sw = fm.stringWidth("V2")
    g.drawString("V2", (size - sw) / 2, size / 2 + fm.ascent / 2 - 2)
    g.dispose()
    return BitmapPainter(image.toComposeImageBitmap())
}

@Composable
fun App(state: DesktopAppState) {
    val currentTab by state.activeTab.collectAsState()
    val servers by state.servers.collectAsState()
    val coreState by state.coreState.collectAsState()
    val themeMode by state.themeMode.collectAsState()
    val themePalette by state.themePalette.collectAsState()
    val isAmoled by state.pureBlackAmoled.collectAsState()
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val meshMode by state.meshGradientMode.collectAsState()

    DesktopTheme(
        themeMode = themeMode,
        palette = themePalette,
        isAmoled = isAmoled
    ) {
        MeshGradientBackground(
            enabled = meshEnabled,
            mode = meshMode,
            connected = coreState == DesktopCoreState.RUNNING,
            accentColor = getAccentColor(themePalette)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    currentTab = currentTab,
                    serverCount = servers.size,
                    coreState = coreState,
                    onTabSelected = { state.setTab(it) }
                )

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentTab) {
                        DesktopTab.MAIN -> MainScreen(state = state)
                        DesktopTab.SUBSCRIPTIONS -> SubscriptionsScreen(state = state)
                        DesktopTab.GEEK_MODE -> GeekModeScreen(state = state)
                        DesktopTab.APPEARANCE -> AppearanceScreen(state = state)
                        DesktopTab.SETTINGS -> SettingsScreen(state = state)
                    }
                }
            }
        }
    }
}

fun main() = application {
    val state = remember { DesktopAppState() }
    var isWindowVisible by remember { mutableStateOf(true) }
    val trayState = rememberTrayState()
    val coreState by state.coreState.collectAsState()
    val isSystemProxyActive by state.isSystemProxyActive.collectAsState()
    val routingMode by state.routingMode.collectAsState()
    val minimizeToTray by state.minimizeToTray.collectAsState()

    val trayIcon = remember(coreState) {
        loadAppIcon(connected = coreState == DesktopCoreState.RUNNING)
    }

    if (androidx.compose.ui.window.isTraySupported) {
        Tray(
            state = trayState,
            icon = trayIcon,
            tooltip = "v2whitelist — ${when (coreState) {
                DesktopCoreState.RUNNING -> "Подключено"
                DesktopCoreState.STARTING -> "Подключение..."
                DesktopCoreState.ERROR -> "Ошибка"
                DesktopCoreState.STOPPED -> "Отключено"
            }}",
            onAction = {
                isWindowVisible = !isWindowVisible
            },
            menu = {
                Item(
                    text = if (isWindowVisible) "Свернуть в трей" else "Открыть v2whitelist",
                    onClick = { isWindowVisible = !isWindowVisible }
                )
                Separator()
                Item(
                    text = when (coreState) {
                        DesktopCoreState.RUNNING -> "⏹ Отключить VPN"
                        DesktopCoreState.STARTING -> "⏳ Подключение..."
                        DesktopCoreState.ERROR -> "🔄 Повторить подключение"
                        DesktopCoreState.STOPPED -> "▶ Подключить VPN"
                    },
                    enabled = coreState != DesktopCoreState.STARTING,
                    onClick = { state.toggleConnect() }
                )
                Item(
                    text = if (routingMode == "TUN") "Режим: TUN → переключить на Прокси" else "Режим: Прокси → переключить на TUN",
                    onClick = { state.toggleRoutingMode() }
                )
                Item(
                    text = if (isSystemProxyActive) "✔ Системный прокси ОС: ВКЛ (нажать — выкл)" else "Системный прокси ОС: ВЫКЛ (нажать — вкл)",
                    onClick = { state.toggleSystemProxy() }
                )
                Separator()
                Item(
                    text = "Выход",
                    onClick = {
                        state.stopCore()
                        exitApplication()
                    }
                )
            }
        )
    }

    val windowState = rememberWindowState(
        width = 1100.dp,
        height = 760.dp
    )

    Window(
        onCloseRequest = {
            if (minimizeToTray && androidx.compose.ui.window.isTraySupported) {
                isWindowVisible = false
            } else {
                state.stopCore()
                exitApplication()
            }
        },
        state = windowState,
        visible = isWindowVisible,
        title = "пробел — v2whitelist",
        icon = trayIcon
    ) {
        App(state = state)
    }
}
