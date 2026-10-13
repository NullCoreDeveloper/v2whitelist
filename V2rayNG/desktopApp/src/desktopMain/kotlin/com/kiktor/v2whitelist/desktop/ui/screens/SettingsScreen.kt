package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

    val currentDirectDomains by state.customDirectDomains.collectAsState()
    val currentProxyDomains by state.customProxyDomains.collectAsState()
    val currentUpHost by state.upstreamProxyHost.collectAsState()
    val currentUpPort by state.upstreamProxyPort.collectAsState()
    val currentUpType by state.upstreamProxyType.collectAsState()
    val currentCheckUrl by state.connectivityCheckUrl.collectAsState()
    val currentMinSpeed by state.minSpeedThresholdMbps.collectAsState()
    val currentTheme by state.uiTheme.collectAsState()
    val currentCompact by state.uiCompactCards.collectAsState()
    val currentMinimizeToTray by state.minimizeToTray.collectAsState()

    var subUrlInput by remember(currentSubUrl) { mutableStateOf(currentSubUrl) }
    var xrayPathInput by remember(currentXrayPath) { mutableStateOf(currentXrayPath) }
    var v2wPathInput by remember(currentV2wPath) { mutableStateOf(currentV2wPath) }
    var httpPortInput by remember(currentHttpPort) { mutableStateOf(currentHttpPort.toString()) }
    var socksPortInput by remember(currentSocksPort) { mutableStateOf(currentSocksPort.toString()) }
    var autoSystemProxyInput by remember(currentAutoSystemProxy) { mutableStateOf(currentAutoSystemProxy) }
    var allowLanInput by remember(currentAllowLan) { mutableStateOf(currentAllowLan) }

    var directDomainsInput by remember(currentDirectDomains) { mutableStateOf(currentDirectDomains) }
    var proxyDomainsInput by remember(currentProxyDomains) { mutableStateOf(currentProxyDomains) }
    var upHostInput by remember(currentUpHost) { mutableStateOf(currentUpHost) }
    var upPortInput by remember(currentUpPort) { mutableStateOf(if (currentUpPort > 0) currentUpPort.toString() else "") }
    var upTypeInput by remember(currentUpType) { mutableStateOf(currentUpType) }
    var checkUrlInput by remember(currentCheckUrl) { mutableStateOf(currentCheckUrl) }
    var minSpeedInput by remember(currentMinSpeed) { mutableStateOf(currentMinSpeed.toString()) }
    var themeInput by remember(currentTheme) { mutableStateOf(currentTheme) }
    var compactInput by remember(currentCompact) { mutableStateOf(currentCompact) }
    var minimizeToTrayInput by remember(currentMinimizeToTray) { mutableStateOf(currentMinimizeToTray) }

    var isSavedSnackbar by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Расширенные настройки",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(20.dp))

        // --- 1. Оформление и поведение ---
        SettingsCard(title = "Внешний вид и поведение") {
            Text(
                text = "Тема оформления:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeOptionChip(
                    name = "Cyan Neon",
                    isSelected = themeInput.equals("CYAN", ignoreCase = true),
                    onClick = {
                        themeInput = "CYAN"
                        state.setUiTheme("CYAN")
                    }
                )
                ThemeOptionChip(
                    name = "AMOLED Black",
                    isSelected = themeInput.equals("AMOLED", ignoreCase = true),
                    onClick = {
                        themeInput = "AMOLED"
                        state.setUiTheme("AMOLED")
                    }
                )
                ThemeOptionChip(
                    name = "Cyber Purple",
                    isSelected = themeInput.equals("PURPLE", ignoreCase = true),
                    onClick = {
                        themeInput = "PURPLE"
                        state.setUiTheme("PURPLE")
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Компактный режим карточек
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Компактный вид карточек узлов",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Уменьшает высоту карточек серверов для отображения большего количества узлов без скролла.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = compactInput,
                    onCheckedChange = {
                        compactInput = it
                        state.setUiCompactCards(it)
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

            // Сворачивание в трей
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Сворачивать в системный трей при закрытии",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "При нажатии на крестик окно сворачивается в трей ОС. Работает в фоне без прерывания соединения.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = minimizeToTrayInput,
                    onCheckedChange = {
                        minimizeToTrayInput = it
                        state.setMinimizeToTray(it)
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 2. Раздельное туннелирование по доменам ---
        SettingsCard(title = "Раздельное туннелирование (по доменам)") {
            Text(
                text = "Прямое подключение (Direct) — домены в обход VPN (через запятую):",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = directDomainsInput,
                onValueChange = { directDomainsInput = it },
                placeholder = { Text("ya.ru, vk.com, gosuslugi.ru, sber.ru", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                colors = defaultTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Принудительно через VPN (Proxy) — домены (через запятую):",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = proxyDomainsInput,
                onValueChange = { proxyDomainsInput = it },
                placeholder = { Text("instagram.com, twitter.com, notion.so, chatgpt.com", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) },
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                colors = defaultTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- 3. Цепочечный Upstream Прокси (Кастомный финальный эндпоинт) ---
        SettingsCard(title = "Кастомный upstream прокси (Цепочка серверов)") {
            Text(
                text = "Перенаправлять весь трафик после VPN через промежуточный SOCKS5/HTTP прокси (Chaining):",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = upHostInput,
                    onValueChange = { upHostInput = it },
                    placeholder = { Text("Хост upstream (IP или домен)", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(2f)
                )

                OutlinedTextField(
                    value = upPortInput,
                    onValueChange = { upPortInput = it },
                    placeholder = { Text("Порт (напр. 1080)", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.weight(1.2f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThemeOptionChip(
                        name = "SOCKS5",
                        isSelected = upTypeInput.equals("socks5", ignoreCase = true) || upTypeInput.isBlank(),
                        onClick = { upTypeInput = "socks5" }
                    )
                    ThemeOptionChip(
                        name = "HTTP",
                        isSelected = upTypeInput.equals("http", ignoreCase = true),
                        onClick = { upTypeInput = "http" }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 4. Верификация профиля и SmartFailover ---
        SettingsCard(title = "Верификация профиля и SmartFailover") {
            Text(
                text = "URL проверки связности / Network Status Check:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = checkUrlInput,
                onValueChange = { checkUrlInput = it },
                placeholder = { Text("https://cp.cloudflare.com/generate_204", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = defaultTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Минимальный порог скорости для автоподключения (Мбит/с):",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = minSpeedInput,
                onValueChange = { minSpeedInput = it },
                placeholder = { Text("1.0", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = defaultTextFieldColors(),
                modifier = Modifier.width(180.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- 5. Ядро Xray и v2w-core ---
        SettingsCard(title = "Исполняемые файлы ядер (Xray и v2w-core)") {
            Text(text = "Путь к бинарнику xray / xray.exe:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = xrayPathInput,
                    onValueChange = { xrayPathInput = it },
                    placeholder = { Text("/usr/bin/xray или xray.exe", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val detected = state.xrayRunner.findXrayExecutable()
                        if (detected != null) xrayPathInput = detected.absolutePath
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(50.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Автопоиск", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(text = "Путь к бинарнику v2w-scan (stateless reality scan):", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = v2wPathInput,
                    onValueChange = { v2wPathInput = it },
                    placeholder = { Text("v2w-core/v2w-scan или v2w-scan.exe", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = defaultTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val detected = com.kiktor.v2whitelist.desktop.engine.V2WCoreScanner.findV2WScanExecutable()
                        if (detected != null) v2wPathInput = detected.absolutePath
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(50.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Автопоиск", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 6. Локальные Inbound порты и сеть ---
        SettingsCard(title = "Локальные порты и доступ по локальной сети") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SOCKS5 Порт:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
                    Text("HTTP Порт:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Авто-включение системного прокси ОС", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Автоматически настраивать системный прокси (KDE / GNOME / Windows) при подключении ядра.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = autoSystemProxyInput, onCheckedChange = { autoSystemProxyInput = it })
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Разрешить подключения из LAN (0.0.0.0)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Позволяет другим устройствам домашней/офисной сети использовать этот ПК как шлюз.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = allowLanInput, onCheckedChange = { allowLanInput = it })
            }
        }

        Spacer(Modifier.height(24.dp))

        // Save Button
        Button(
            onClick = {
                val httpP = httpPortInput.toIntOrNull() ?: 10809
                val socksP = socksPortInput.toIntOrNull() ?: 10808
                val upP = upPortInput.toIntOrNull() ?: 0
                val minSpd = minSpeedInput.toDoubleOrNull() ?: 1.0

                state.saveSettings(
                    subUrl = subUrlInput.trim(),
                    xrayPath = xrayPathInput.trim(),
                    v2wPath = v2wPathInput.trim(),
                    httpP = httpP,
                    socksP = socksP,
                    autoSystemProxy = autoSystemProxyInput,
                    lan = allowLanInput,
                    directDomains = directDomainsInput.trim(),
                    proxyDomains = proxyDomainsInput.trim(),
                    upHost = upHostInput.trim(),
                    upPort = upP,
                    upType = upTypeInput.trim(),
                    theme = themeInput,
                    compact = compactInput,
                    checkUrl = checkUrlInput.trim(),
                    minSpeed = minSpd
                )
                state.setMinimizeToTray(minimizeToTrayInput)
                isSavedSnackbar = true
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сохранить все настройки", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        if (isSavedSnackbar) {
            Spacer(Modifier.height(12.dp))
            Text("✓ Все настройки успешно сохранены и применены!", color = AccentMint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ThemeOptionChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = name,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = {
                Text(text = title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                content()
            }
        )
    }
}

@Composable
private fun defaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
)
