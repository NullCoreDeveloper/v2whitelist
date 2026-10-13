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
    "v2rayNG/1.10.x",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeekModeScreen(
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
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        // Шапка Geek Mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Geek Mode — Инженерная консоль",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${logs.size} событий",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { GeekModeLogger.clear() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Очистить", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 1. Инфо активного сервера: Имя, Протокол, Адрес (хост:порт), Пинг, Exit IP
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (coreState == DesktopCoreState.RUNNING) AccentMint else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeProfile?.remarks ?: "Сервер не выбран",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "Протокол: ${activeProfile?.configType?.name ?: "—"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "•  Адрес: ${activeProfile?.server ?: "—"}:${activeProfile?.serverPort ?: "—"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (activePing != null && activePing > 0) {
                                Text(
                                    text = "•  Пинг: ${activePing}ms",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentMint
                                )
                            }
                        }
                    }
                }

                // Exit IP + кнопка обновить
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Exit IP:",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = exitIp ?: "Не определен",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (exitIp != null) AccentMint else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { state.fetchExitIp() },
                        modifier = Modifier.size(32.dp).padding(start = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить Exit IP", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. Три кнопки: Пинг-тест, Найти VIP, Пинг VIP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { state.forcePingTest() },
                enabled = !isForcePinging && selectedGuid != null,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                if (isForcePinging) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
                Text("Пинг-тест", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { state.findMoreVipServers() },
                enabled = !isFindingVip && servers.isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                if (isFindingVip) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp), tint = AccentAmber)
                }
                Spacer(Modifier.width(6.dp))
                Text("Найти VIP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { state.pingVipServers() },
                enabled = !isPingingVip && vipGuids.isNotEmpty(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                if (isPingingVip) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp), tint = AccentMint)
                }
                Spacer(Modifier.width(6.dp))
                Text("Пинг VIP (${vipGuids.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // VIP Кэш
        if (vipGuids.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "VIP-кэш быстрых узлов (${vipGuids.size}):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (guid in vipGuids) {
                            val prof = servers.find { it.first == guid }?.second
                            val ping = pings[guid]
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (guid == selectedGuid) AccentAmber.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (guid == selectedGuid) AccentAmber else MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { state.selectServer(guid) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = prof?.remarks?.take(18) ?: guid.take(8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (ping != null && ping > 0) {
                                        Text(
                                            text = " ${ping}ms",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentMint
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Удалить из VIP",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(13.dp)
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

        // 3. DPI Bypass: Global SNI, Cloudflare Clean IP, Custom User-Agent
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "DPI Bypass (Подмена параметров для обхода блокировок):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Global SNI
                    OutlinedTextField(
                        value = sniInput,
                        onValueChange = {
                            sniInput = it
                            state.setGlobalSni(it)
                        },
                        label = { Text("Global SNI", fontSize = 11.sp) },
                        placeholder = { Text("ya.ru, vk.com...", fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    )

                    // Cloudflare IP
                    OutlinedTextField(
                        value = cfIpInput,
                        onValueChange = {
                            cfIpInput = it
                            state.setCloudflareIp(it)
                        },
                        label = { Text("Cloudflare Clean IP", fontSize = 11.sp) },
                        placeholder = { Text("104.16.x.x...", fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    )

                    // User-Agent выпадающий
                    Box(modifier = Modifier.weight(1.1f)) {
                        OutlinedTextField(
                            value = currentUa,
                            onValueChange = { state.setCustomUserAgent(it) },
                            label = { Text("Кастомный User-Agent", fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { uaExpanded = !uaExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = uaExpanded,
                            onDismissRequest = { uaExpanded = false }
                        ) {
                            USER_AGENTS.forEach { ua ->
                                DropdownMenuItem(
                                    text = { Text(ua, fontSize = 11.sp) },
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

        // 4. Логи ядра в реалтайме
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = logFilter,
                onValueChange = { logFilter = it },
                placeholder = { Text("Фильтр логов ядра (поиск по ключевым словам)...", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(15.dp)) },
                trailingIcon = {
                    if (logFilter.isNotEmpty()) {
                        IconButton(onClick = { logFilter = "" }) {
                            Icon(Icons.Default.Clear, null, modifier = Modifier.size(14.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(10.dp)
            ) {
                items(filteredLogs) { line ->
                    val color = when {
                        line.contains("[ERROR]") || line.contains("error", ignoreCase = true) -> AccentRed
                        line.contains("[WARN]") || line.contains("warning", ignoreCase = true) -> AccentAmber
                        line.contains("[SUCCESS]") || line.contains("Успешно") -> AccentMint
                        line.contains("[DEBUG]") -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = color,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}
