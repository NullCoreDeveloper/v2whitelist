package com.kiktor.v2whitelist.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import com.kiktor.v2whitelist.desktop.ui.components.Sidebar
import com.kiktor.v2whitelist.desktop.ui.screens.LogsScreen
import com.kiktor.v2whitelist.desktop.ui.screens.ServersScreen
import com.kiktor.v2whitelist.desktop.ui.screens.SettingsScreen
import com.kiktor.v2whitelist.desktop.ui.theme.DesktopTheme

@Composable
fun App(state: DesktopAppState) {
    val currentTab by state.activeTab.collectAsState()
    val servers by state.servers.collectAsState()
    val coreState by state.coreState.collectAsState()

    DesktopTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            Sidebar(
                currentTab = currentTab,
                serverCount = servers.size,
                coreState = coreState,
                onTabSelected = { state.setTab(it) }
            )

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (currentTab) {
                    DesktopTab.SERVERS -> ServersScreen(state = state)
                    DesktopTab.LOGS -> LogsScreen(state = state)
                    DesktopTab.SETTINGS -> SettingsScreen(state = state)
                }
            }
        }
    }
}

fun main() = application {
    val state = remember { DesktopAppState() }

    // Корректное завершение процесса Xray при выходе из приложения
    Runtime.getRuntime().addShutdownHook(Thread {
        state.xrayRunner.stop()
    })

    Window(
        onCloseRequest = {
            state.xrayRunner.stop()
            exitApplication()
        },
        title = "v2whitelist — Desktop Client (Beta)",
        state = rememberWindowState(width = 1040.dp, height = 720.dp)
    ) {
        App(state = state)
    }
}
