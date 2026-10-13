package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.components.ProxyModeCard
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun ServersScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val servers by state.servers.collectAsState()
    val selectedGuid by state.selectedGuid.collectAsState()
    val coreState by state.coreState.collectAsState()
    val scenario by state.scenario.collectAsState()
    val lastError by state.lastError.collectAsState()
    val pingMap by state.pingMap.collectAsState()
    val isPinging by state.isPinging.collectAsState()
    val isUpdatingSub by state.isUpdatingSub.collectAsState()
    val isV2WScanning by state.isV2WScanning.collectAsState()
    val smartConnectStatus by state.smartConnectStatus.collectAsState()
    val searchQuery by state.searchQuery.collectAsState()
    val httpPort by state.httpPort.collectAsState()
    val socksPort by state.socksPort.collectAsState()
    val clipboardNotice by state.clipboardNotice.collectAsState()

    val filteredServers = remember(servers, searchQuery) {
        if (searchQuery.isBlank()) servers
        else servers.filter { (_, p) ->
            p.remarks.contains(searchQuery, ignoreCase = true) ||
            p.server?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    val selectedProfile = remember(servers, selectedGuid) {
        servers.firstOrNull { it.first == selectedGuid }?.second
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp)
    ) {
        // Clipboard notice banner
        AnimatedVisibility(visible = clipboardNotice != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AccentMint.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, AccentMint.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentMint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = clipboardNotice.orEmpty(),
                        color = AccentMint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // --- 1. Quick Connection Card ---
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, if (coreState == DesktopCoreState.RUNNING) AccentMint.copy(alpha = 0.5f) else DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (coreState) {
                                    DesktopCoreState.RUNNING -> "Ядро активно"
                                    DesktopCoreState.STARTING -> "Подключение..."
                                    DesktopCoreState.ERROR -> "Ошибка запуска"
                                    DesktopCoreState.STOPPED -> "Не подключено"
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (coreState) {
                                    DesktopCoreState.RUNNING -> AccentMint
                                    DesktopCoreState.ERROR -> AccentRed
                                    else -> TextPrimary
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            if (coreState == DesktopCoreState.RUNNING) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AccentMint.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "SOCKS: $socksPort | HTTP: $httpPort",
                                        color = AccentMint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = selectedProfile?.remarks ?: "Сервер не выбран",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Connect/Disconnect Button
                    val isRunning = coreState == DesktopCoreState.RUNNING
                    Button(
                        onClick = { state.toggleConnect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) AccentRed else AccentCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(46.dp)
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "Отключить" else "Подключить",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Error Message if any
                AnimatedVisibility(visible = lastError != null) {
                    lastError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentRed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text(
                                text = err,
                                color = AccentRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 16.dp))

                // Scenario selector chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Сценарий:",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    ScenarioChip(
                        title = "Авто",
                        selected = scenario == AppScenario.AUTO,
                        onClick = { state.setScenario(AppScenario.AUTO) }
                    )
                    ScenarioChip(
                        title = "Белый список (БС)",
                        selected = scenario == AppScenario.WHITELIST,
                        onClick = { state.setScenario(AppScenario.WHITELIST) }
                    )
                    ScenarioChip(
                        title = "Черный список (ЧС)",
                        selected = scenario == AppScenario.VPN_BLACKLIST,
                        onClick = { state.setScenario(AppScenario.VPN_BLACKLIST) }
                    )
                    ScenarioChip(
                        title = "YouTube",
                        selected = scenario == AppScenario.YOUTUBE,
                        onClick = { state.setScenario(AppScenario.YOUTUBE) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 2. Proxy Mode Card (Standalone / Pure Proxy) ---
        ProxyModeCard(state = state)

        Spacer(Modifier.height(16.dp))

        // --- 3. Action Controls & Search ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { state.setSearchQuery(it) },
                placeholder = { Text("Поиск узлов...", color = TextTertiary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.width(320.dp).height(46.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // SmartConnect Button (VIP -> v2w-core -> Xray e2e/speed -> Fallback)
                Button(
                    onClick = { state.runSmartConnect() },
                    enabled = !isV2WScanning && servers.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan.copy(alpha = 0.2f),
                        contentColor = AccentCyan
                    ),
                    border = BorderStroke(1.dp, AccentCyan),
                    modifier = Modifier.height(46.dp)
                ) {
                    if (isV2WScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = smartConnectStatus ?: "Поиск...",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Smart Connect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Ping All Button
                OutlinedButton(
                    onClick = { state.pingAllServers() },
                    enabled = !isPinging && servers.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    modifier = Modifier.height(46.dp)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Пингуем...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Тест пинга", fontSize = 13.sp)
                    }
                }

                // Update Subscription Button
                FilledTonalButton(
                    onClick = { state.updateSubscription() },
                    enabled = !isUpdatingSub,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DarkSurfaceElevated,
                        contentColor = TextPrimary
                    ),
                    modifier = Modifier.height(46.dp)
                ) {
                    if (isUpdatingSub) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Загрузка...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить", fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- 3. Server List ---
        if (filteredServers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (servers.isEmpty()) "Список серверов пуст" else "Ничего не найдено",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (servers.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Укажите URL подписки в Настройках или нажмите «Обновить»",
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredServers, key = { it.first }) { (guid, profile) ->
                    ServerItemCard(
                        guid = guid,
                        profile = profile,
                        isSelected = guid == selectedGuid,
                        ping = pingMap[guid],
                        onSelect = { state.selectServer(guid) },
                        onPing = { state.pingServer(guid) },
                        onDelete = { state.deleteServer(guid) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) AccentCyan.copy(alpha = 0.2f) else DarkSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) AccentCyan else DarkBorder),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = title,
            color = if (selected) AccentCyan else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ServerItemCard(
    guid: String,
    profile: ProfileItem,
    isSelected: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isSelected) AccentCyan else DarkBorder

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) DarkSurfaceElevated else DarkSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
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
                // Protocol Badge
                ProtocolBadge(type = profile.configType)

                Spacer(Modifier.width(12.dp))

                // Country Flag / Tag
                val countryTag = ServerTagResolver.resolveServerTag(profile.remarks)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DarkBorder,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text(
                        text = countryTag,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Server Remarks & Address
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.remarks.ifBlank { "Сервер" },
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.server ?: "—"}:${profile.serverPort ?: "—"}",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Ping Badge & Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                PingBadge(latencyMs = ping, onClick = onPing)

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Удалить",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolBadge(type: EConfigType) {
    val (label, color) = when (type) {
        EConfigType.VLESS -> "VLESS" to AccentCyan
        EConfigType.VMESS -> "VMESS" to AccentPurple
        EConfigType.TROJAN -> "TROJAN" to AccentMint
        EConfigType.SHADOWSOCKS -> "SS" to AccentAmber
        EConfigType.HYSTERIA2 -> "HY2" to Color(0xFFFF5E7E)
        EConfigType.WIREGUARD -> "WG" to Color(0xFF00B4D8)
        EConfigType.SOCKS -> "SOCKS" to Color(0xFF90E0EF)
        EConfigType.HTTP -> "HTTP" to Color(0xFFADE8F4)
        else -> "CUSTOM" to TextSecondary
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PingBadge(
    latencyMs: Long?,
    onClick: () -> Unit
) {
    val (text, color) = when {
        latencyMs == null -> "—" to TextTertiary
        latencyMs < 0L -> "Таймаут" to AccentRed
        latencyMs < 120L -> "${latencyMs} ms" to AccentMint
        latencyMs < 300L -> "${latencyMs} ms" to AccentAmber
        else -> "${latencyMs} ms" to AccentRed
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
