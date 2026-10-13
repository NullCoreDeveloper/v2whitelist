package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun SettingsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val currentSubUrl by state.subscriptionUrl.collectAsState()
    val currentXrayPath by state.xrayBinaryPath.collectAsState()
    val currentV2wPath by state.v2wScanPath.collectAsState()
    val currentHttpPort by state.httpPort.collectAsState()
    val currentSocksPort by state.socksPort.collectAsState()
    val currentAutoSystemProxy by state.autoEnableSystemProxy.collectAsState()
    val currentAllowLan by state.allowLan.collectAsState()

    var subUrlInput by remember(currentSubUrl) { mutableStateOf(currentSubUrl) }
    var xrayPathInput by remember(currentXrayPath) { mutableStateOf(currentXrayPath) }
    var v2wPathInput by remember(currentV2wPath) { mutableStateOf(currentV2wPath) }
    var httpPortInput by remember(currentHttpPort) { mutableStateOf(currentHttpPort.toString()) }
    var socksPortInput by remember(currentSocksPort) { mutableStateOf(currentSocksPort.toString()) }
    var autoSystemProxyInput by remember(currentAutoSystemProxy) { mutableStateOf(currentAutoSystemProxy) }
    var allowLanInput by remember(currentAllowLan) { mutableStateOf(currentAllowLan) }

    var isSavedSnackbar by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Параметры приложения",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(Modifier.height(20.dp))

        // Subscription Section
        SettingsCard(title = "Подписка и зеркала") {
            Text(
                text = "Ссылка на конфигурацию или автообновляемую подписку:",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = subUrlInput,
                onValueChange = { subUrlInput = it },
                placeholder = { Text("https://example.com/sub/...", color = TextTertiary, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = defaultTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        // Xray Core Section
        SettingsCard(title = "Ядро Xray") {
            Text(
                text = "Путь к бинарнику xray / xray.exe:",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = xrayPathInput,
                    onValueChange = { xrayPathInput = it },
                    placeholder = { Text("/usr/bin/xray или xray.exe", color = TextTertiary, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = {
                        val detected = state.xrayRunner.findXrayExecutable()
                        if (detected != null) {
                            xrayPathInput = detected.absolutePath
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Автопоиск", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // v2w-core Scanner Section
        SettingsCard(title = "Ядро глубокого сканирования v2w-core") {
            Text(
                text = "Путь к бинарнику v2w-scan (stateless reality/tls handshake):",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = v2wPathInput,
                    onValueChange = { v2wPathInput = it },
                    placeholder = { Text("v2w-core/v2w-scan или v2w-scan.exe", color = TextTertiary, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = {
                        val detected = com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.findV2WScanExecutable()
                        if (detected != null) {
                            v2wPathInput = detected.absolutePath
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Автопоиск", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Inbound Proxy Ports Section
        SettingsCard(title = "Локальные Inbound прокси порты") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SOCKS5 Порт:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = socksPortInput,
                        onValueChange = { socksPortInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = defaultTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("HTTP Порт:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = httpPortInput,
                        onValueChange = { httpPortInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = defaultTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Standalone Proxy Mode Settings
        SettingsCard(title = "Режим прокси (Standalone Proxy)") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Auto enable system proxy toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Авто-включение системного прокси ОС",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Автоматически настраивать системный прокси (KDE / GNOME / Windows) при старте ядра и выключать при остановке.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = autoSystemProxyInput,
                        onCheckedChange = { autoSystemProxyInput = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = AccentMint,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))

                // Allow LAN toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = "Разрешить подключения из локальной сети (Allow LAN / 0.0.0.0)",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Прослушивать все сетевые интерфейсы (0.0.0.0), чтобы другие устройства в Wi-Fi сети могли использовать этот ПК как прокси.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        val detectedIp = remember { com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager.getLocalIpAddress() }
                        if (detectedIp != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Локальный IP этого ПК: $detectedIp",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Switch(
                        checked = allowLanInput,
                        onCheckedChange = { allowLanInput = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = AccentCyan,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Save Button
        Button(
            onClick = {
                val httpP = httpPortInput.toIntOrNull() ?: 10809
                val socksP = socksPortInput.toIntOrNull() ?: 10808
                state.saveSettings(
                    subUrl = subUrlInput.trim(),
                    xrayPath = xrayPathInput.trim(),
                    v2wPath = v2wPathInput.trim(),
                    httpP = httpP,
                    socksP = socksP,
                    autoSystemProxy = autoSystemProxyInput,
                    lan = allowLanInput
                )
                isSavedSnackbar = true
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentCyan,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сохранить настройки", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        if (isSavedSnackbar) {
            Spacer(Modifier.height(12.dp))
            Text("✓ Настройки успешно сохранены!", color = AccentMint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(32.dp))

        // About card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurfaceElevated,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("v2whitelist Desktop Client (PC Beta)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Версия: 2.0.0 (KMP Compose Multiplatform)\n" +
                    "Поддерживаемые протоколы: VLESS Reality, VMess, Trojan, Shadowsocks, Hysteria2\n" +
                    "Умные сценарии: Белые Списки РФ, Черные списки, YouTube",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = {
                Text(text = title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                content()
            }
        )
    }
}

@Composable
private fun defaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = DarkBorder,
    focusedContainerColor = DarkSurfaceElevated,
    unfocusedContainerColor = DarkSurfaceElevated,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
