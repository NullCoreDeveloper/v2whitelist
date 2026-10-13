package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*

private val USER_AGENTS = listOf(
    "Auto (Default)",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36 Edg/118.0.2088.46",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Safari/605.1.15",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val logs by state.logs.collectAsState()
    val servers by state.servers.collectAsState()
    val selectedGuid by state.selectedGuid.collectAsState()
    val pings by state.pingMap.collectAsState()
    val exitIp by state.exitIp.collectAsState()
    val vipGuids by state.vipGuids.collectAsState()
    val coreState by state.coreState.collectAsState()

    val currentSni by state.globalSni.collectAsState()
    val currentCfIp by state.cloudflareIp.collectAsState()
    val currentUa by state.customUserAgent.collectAsState()

    val isForcePinging by state.isForcePinging.collectAsState()
    val isPingingVip by state.isPingingVip.collectAsState()
    val isFindingVip by state.isFindingVip.collectAsState()

    var sniInput by remember(currentSni) { mutableStateOf(currentSni) }
    var cfIpInput by remember(currentCfIp) { mutableStateOf(currentCfIp) }
    var uaExpanded by remember { mutableStateOf(false) }
    var logFilter by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    val activeProfile = remember(servers, selectedGuid) {
        val guid = selectedGuid ?: servers.firstOrNull()?.first
        servers.firstOrNull { it.first == guid }?.second
    }
    val activePing = selectedGuid?.let { pings[it] }

    val filteredLogs = remember(logs, logFilter) {
        if (logFilter.isBlank()) logs
        else logs.filter { it.contains(logFilter, ignoreCase = true) }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        if (coreState == DesktopCoreState.RUNNING && exitIp == null) {
            state.fetchExitIp()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(20.dp)
    ) {
        // Заголовок Geek Mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Geek Mode — Инженерная консоль",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DarkSurfaceElevated
                ) {
                    Text(
                        text = "${logs.size} записей",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val allLogsText = logs.joinToString("\n")
                        state.copyToClipboard(allLogsText, "Все логи GeekMode")
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { GeekModeLogger.clear() },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Очистить", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Карточка 1: Информация об активном узле и Exit IP (1-в-1 как в GeekMode на Android)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeProfile != null) "Активный узел: ${activeProfile.remarks}" else "Узел не выбран",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        if (activeProfile != null) {
                            Text(
                                text = "Протокол: ${activeProfile.configType.name} | Сервер: ${activeProfile.server}:${activeProfile.serverPort}" +
                                        if (activePing != null && activePing > 0) " | Пинг: ${activePing}ms" else "",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Exit IP бэйдж
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (exitIp != null) "Exit IP: $exitIp" else "Exit IP: Не определён",
                                fontSize = 12.sp,
                                color = if (exitIp != null) AccentMint else TextTertiary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { state.fetchExitIp() },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить Exit IP",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Панель быстрых инженерных действий (Force Ping, Find More VIP, Ping VIP)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { state.forcePingTest() },
                enabled = !isForcePinging && activeProfile != null,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = TextPrimary),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isForcePinging) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentCyan)
                } else {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                }
                Spacer(Modifier.width(8.dp))
                Text("Force Ping", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { state.findMoreVipServers() },
                enabled = !isFindingVip,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = TextPrimary),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isFindingVip) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentAmber)
                } else {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentAmber)
                }
                Spacer(Modifier.width(8.dp))
                Text("Найти VIP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { state.pingVipServers() },
                enabled = !isPingingVip && vipGuids.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = TextPrimary),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isPingingVip) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentMint)
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentMint)
                }
                Spacer(Modifier.width(8.dp))
                Text("Пинг VIP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))

        // VIP-серверы карточка (FlowRow чипов 1-в-1 как в GeekMode на Android)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "VIP Серверы (${vipGuids.size})",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        text = "Нажмите на чип для переключения",
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (vipGuids.isEmpty()) {
                    Text(
                        text = "Список VIP серверов пуст. Нажмите «Найти VIP» выше или отметьте серверы звёздочкой в общем списке.",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (guid in vipGuids) {
                            val prof = servers.firstOrNull { it.first == guid }?.second
                            val ping = pings[guid]
                            val isSelected = guid == selectedGuid

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AccentCyan.copy(alpha = 0.2f) else DarkSurfaceElevated,
                                border = BorderStroke(1.dp, if (isSelected) AccentCyan else DarkBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { state.selectServer(guid) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${prof?.remarks ?: guid} " + if (ping != null && ping > 0) "(${ping}ms)" else "",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentCyan else TextPrimary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Удалить из VIP",
                                        tint = TextSecondary,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .clickable { state.removeVipServer(guid) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Инженерные оверрайды (Global SNI, Cloudflare IP, User-Agent)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Инженерные оверрайды Xray (Geek Mode)",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Global SNI
                    OutlinedTextField(
                        value = sniInput,
                        onValueChange = {
                            sniInput = it
                            state.setGlobalSni(it.trim())
                        },
                        label = { Text("Global SNI оверрайд", fontSize = 11.sp) },
                        placeholder = { Text("например: dl.google.com", fontSize = 11.sp) },
                        singleLine = true,
                        colors = defaultTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )

                    // Cloudflare Clean IP
                    OutlinedTextField(
                        value = cfIpInput,
                        onValueChange = {
                            cfIpInput = it
                            state.setCloudflareIp(it.trim())
                        },
                        label = { Text("Cloudflare Clean IP", fontSize = 11.sp) },
                        placeholder = { Text("например: 104.16.132.229", fontSize = 11.sp) },
                        singleLine = true,
                        colors = defaultTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )

                    // Custom User-Agent
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = currentUa,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Custom User-Agent", fontSize = 11.sp) },
                            trailingIcon = {
                                IconButton(onClick = { uaExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                                }
                            },
                            colors = defaultTextFieldColors(),
                            modifier = Modifier.fillMaxWidth().clickable { uaExpanded = true }
                        )

                        DropdownMenu(
                            expanded = uaExpanded,
                            onDismissRequest = { uaExpanded = false },
                            modifier = Modifier.background(DarkSurfaceElevated)
                        ) {
                            USER_AGENTS.forEach { ua ->
                                DropdownMenuItem(
                                    text = { Text(ua, fontSize = 12.sp, color = TextPrimary) },
                                    onClick = {
                                        state.setCustomUserAgent(ua)
                                        uaExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Консоль логов ядра с полем быстрого фильтра
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF090C10),
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Поле поиска по логам
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = logFilter,
                        onValueChange = { logFilter = it },
                        placeholder = { Text("Фильтр логов (например: Xray, VIP, error)...", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary) },
                        trailingIcon = {
                            if (logFilter.isNotEmpty()) {
                                IconButton(onClick = { logFilter = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        colors = defaultTextFieldColors(),
                        modifier = Modifier.weight(1f).height(46.dp)
                    )
                }

                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (logFilter.isBlank()) "Логи отсутствуют. Запустите подключение или тест узла."
                            else "Нет логов, соответствующих фильтру \"$logFilter\".",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(filteredLogs) { line ->
                            LogLineItem(line = line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(line: String) {
    val tagColor = when {
        line.contains("[XrayCore]") -> AccentCyan
        line.contains("[DesktopRunner]") || line.contains("[SmartConnect]") -> AccentMint
        line.contains("[GeekMode]") -> AccentPurple
        line.contains("[Subscription]") || line.contains("[Subscriptions]") -> AccentAmber
        line.contains("error", ignoreCase = true) || line.contains("ошибка", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> AccentRed
        else -> TextSecondary
    }

    Text(
        text = line,
        color = tagColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
}

@Composable
private fun defaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = DarkBorder,
    focusedContainerColor = DarkSurfaceElevated,
    unfocusedContainerColor = DarkSurfaceElevated,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
