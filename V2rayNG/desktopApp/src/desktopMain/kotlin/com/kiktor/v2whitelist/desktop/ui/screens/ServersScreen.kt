package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.domain.network.NetworkStatusChecker
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.components.QrCodeDialog
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
    val netStatus by state.networkStatus.collectAsState()
    val publicIp by state.publicIpInfo.collectAsState()
    val isCheckingNet by state.isCheckingNetwork.collectAsState()
    val isSystemProxyActive by state.isSystemProxyActive.collectAsState()

    var selectedProtocol by remember { mutableStateOf<EConfigType?>(null) }
    var sortByPing by remember { mutableStateOf(false) }
    var qrProfile by remember { mutableStateOf<ProfileItem?>(null) }

    val filteredServers = remember(servers, searchQuery, selectedProtocol, sortByPing, pingMap) {
        var list = servers
        if (searchQuery.isNotBlank()) {
            list = list.filter { (_, p) ->
                p.remarks.contains(searchQuery, ignoreCase = true) ||
                p.server?.contains(searchQuery, ignoreCase = true) == true
            }
        }
        if (selectedProtocol != null) {
            list = list.filter { (_, p) -> p.configType == selectedProtocol }
        }
        if (sortByPing) {
            list = list.sortedBy { (g, _) ->
                val p = pingMap[g]
                if (p == null || p < 0) Long.MAX_VALUE else p
            }
        }
        list
    }

    val selectedProfile = remember(servers, selectedGuid) {
        servers.firstOrNull { it.first == selectedGuid }?.second
    }

    if (qrProfile != null) {
        QrCodeDialog(
            profile = qrProfile!!,
            onCopy = { text ->
                state.copyToClipboard(text, qrProfile?.remarks ?: "Ссылка узла")
            },
            onDismiss = { qrProfile = null }
        )
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ===== ЛЕВАЯ ПАНЕЛЬ: статус + кнопка подключения + быстрые действия =====
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(Modifier.height(8.dp))

                // Статус-карточка (как в Android)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        when (coreState) {
                            DesktopCoreState.RUNNING -> AccentMint.copy(alpha = 0.6f)
                            DesktopCoreState.ERROR -> AccentRed.copy(alpha = 0.6f)
                            DesktopCoreState.STARTING -> AccentAmber.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.outline
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Индикатор состояния (dot)
                        val dotColor = when (coreState) {
                            DesktopCoreState.RUNNING -> AccentMint
                            DesktopCoreState.ERROR -> AccentRed
                            DesktopCoreState.STARTING -> AccentAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (coreState) {
                                    DesktopCoreState.RUNNING -> "Подключено"
                                    DesktopCoreState.STARTING -> "Подключение..."
                                    DesktopCoreState.ERROR -> "Ошибка подключения"
                                    DesktopCoreState.STOPPED -> "Не подключено"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = when (coreState) {
                                    DesktopCoreState.RUNNING -> AccentMint
                                    DesktopCoreState.ERROR -> AccentRed
                                    DesktopCoreState.STARTING -> AccentAmber
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = selectedProfile?.remarks ?: "Сервер не выбран",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Ошибка (если есть)
                AnimatedVisibility(visible = lastError != null, enter = fadeIn(), exit = fadeOut()) {
                    lastError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AccentRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) {
                            Text(
                                text = err,
                                color = AccentRed,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Большая кнопка подключения (как Android btn_big_connect)
                ConnectButton(
                    coreState = coreState,
                    isScanning = isV2WScanning,
                    scanStatus = smartConnectStatus,
                    onClick = {
                        if (isV2WScanning) return@ConnectButton
                        state.toggleConnect()
                    }
                )

                Spacer(Modifier.height(24.dp))

                // Кнопка смены сервера (видна только когда подключено, как Android btn_switch_server)
                AnimatedVisibility(visible = coreState == DesktopCoreState.RUNNING) {
                    Button(
                        onClick = { state.runSmartConnect() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сменить сервер", fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Сценарии
                Text(
                    text = "Сценарий",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScenarioButton(
                            title = "Авто",
                            selected = scenario == AppScenario.AUTO,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.AUTO) }
                        )
                        ScenarioButton(
                            title = "Белый список",
                            selected = scenario == AppScenario.WHITELIST,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.WHITELIST) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScenarioButton(
                            title = "Чёрный список",
                            selected = scenario == AppScenario.VPN_BLACKLIST,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.VPN_BLACKLIST) }
                        )
                        ScenarioButton(
                            title = "YouTube",
                            selected = scenario == AppScenario.YOUTUBE,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.YOUTUBE) }
                        )
                    }
                }
            }

            // Нижняя панель: быстрые действия (как Android actions grid)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Статус сети + IP
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val (statusLabel, statusColor) = when (netStatus) {
                            NetworkStatusChecker.STATUS_FULL_INTERNET -> "🟢 Полный доступ" to AccentMint
                            NetworkStatusChecker.STATUS_WHITELIST_ONLY -> "🟡 РКН (Белый список)" to AccentAmber
                            else -> "🔴 Нет сети" to AccentRed
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = statusLabel, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                            if (!publicIp.isNullOrBlank()) {
                                Text(text = publicIp.orEmpty(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (isCheckingNet) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentCyan, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Проверить сеть",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { state.checkNetworkStatus() }
                            )
                        }
                    }
                }

                // Порты + системный прокси
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSystemProxyActive) AccentMint.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (isSystemProxyActive) AccentMint.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.toggleSystemProxy() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = if (isSystemProxyActive) AccentMint else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSystemProxyActive) "Системный прокси ОС: ВКЛ" else "Системный прокси ОС: ВЫКЛ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSystemProxyActive) AccentMint else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "SOCKS :$socksPort  HTTP :$httpPort",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Быстрые действия: Smart Connect + Обновить подписку
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Smart Connect
                    OutlinedButton(
                        onClick = { state.runSmartConnect() },
                        enabled = !isV2WScanning && servers.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(40.dp),
                        border = BorderStroke(1.dp, if (!isV2WScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    ) {
                        if (isV2WScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.Bolt, null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isV2WScanning) "Поиск..." else "SmartConnect", fontSize = 11.sp, maxLines = 1)
                    }

                    // Обновить подписку
                    OutlinedButton(
                        onClick = { state.updateAllActiveSubscriptions() },
                        enabled = !isUpdatingSub,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        if (isUpdatingSub) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isUpdatingSub) "Обновление..." else "Обновить", fontSize = 11.sp, maxLines = 1)
                    }
                }

                // Clipboard notice
                AnimatedVisibility(visible = clipboardNotice != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AccentMint.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, AccentMint.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = AccentMint, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(text = clipboardNotice.orEmpty(), color = AccentMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Разделитель
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )

        // ===== ПРАВАЯ ПАНЕЛЬ: список серверов =====
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp)
        ) {
            // Заголовок + поиск + действия
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Поиск
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { state.setSearchQuery(it) },
                    placeholder = { Text("Поиск узлов...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f).height(44.dp)
                )

                Spacer(Modifier.width(10.dp))

                // Тест пинга
                OutlinedButton(
                    onClick = { state.pingAllServers() },
                    enabled = !isPinging && servers.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Пинг...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Тест пинга", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Удалить дубликаты
                OutlinedButton(
                    onClick = { state.removeDuplicateServers() },
                    enabled = servers.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Дубликаты", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Фильтры по протоколу + сортировка
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChipItem(
                        label = "Все",
                        isSelected = selectedProtocol == null,
                        onClick = { selectedProtocol = null }
                    )
                }
                item {
                    FilterChipItem(
                        label = "VLESS",
                        isSelected = selectedProtocol == EConfigType.VLESS,
                        onClick = { selectedProtocol = if (selectedProtocol == EConfigType.VLESS) null else EConfigType.VLESS }
                    )
                }
                item {
                    FilterChipItem(
                        label = "VMESS",
                        isSelected = selectedProtocol == EConfigType.VMESS,
                        onClick = { selectedProtocol = if (selectedProtocol == EConfigType.VMESS) null else EConfigType.VMESS }
                    )
                }
                item {
                    FilterChipItem(
                        label = "TROJAN",
                        isSelected = selectedProtocol == EConfigType.TROJAN,
                        onClick = { selectedProtocol = if (selectedProtocol == EConfigType.TROJAN) null else EConfigType.TROJAN }
                    )
                }
                item {
                    FilterChipItem(
                        label = "SS",
                        isSelected = selectedProtocol == EConfigType.SHADOWSOCKS,
                        onClick = { selectedProtocol = if (selectedProtocol == EConfigType.SHADOWSOCKS) null else EConfigType.SHADOWSOCKS }
                    )
                }
                item {
                    FilterChipItem(
                        label = "HY2",
                        isSelected = selectedProtocol == EConfigType.HYSTERIA2,
                        onClick = { selectedProtocol = if (selectedProtocol == EConfigType.HYSTERIA2) null else EConfigType.HYSTERIA2 }
                    )
                }
                item {
                    FilterChipItem(
                        label = if (sortByPing) "⚡ Пинг ▲" else "⚡ По пингу",
                        isSelected = sortByPing,
                        onClick = { sortByPing = !sortByPing }
                    )
                }
                if (servers.isNotEmpty()) {
                    item {
                        Text(
                            text = "${filteredServers.size} / ${servers.size}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Список серверов
            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (servers.isEmpty()) "Список серверов пуст" else "Ничего не найдено",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (servers.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Перейдите во вкладку «Подписки» или нажмите «Обновить»",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredServers, key = { it.first }) { (guid, profile) ->
                        ServerItemCard(
                            guid = guid,
                            profile = profile,
                            isSelected = guid == selectedGuid,
                            ping = pingMap[guid],
                            onSelect = { state.selectServer(guid) },
                            onPing = { state.pingServer(guid) },
                            onShare = { qrProfile = profile },
                            onCopyLink = {
                                val uri = UniversalConfigParser.toUri(profile) ?: ""
                                state.copyToClipboard(uri, profile.remarks.ifBlank { "Ссылка узла" })
                            },
                            onDelete = { state.deleteServer(guid) }
                        )
                    }
                }
            }
        }
    }
}

// ======= Большая круглая кнопка подключения =======
@Composable
private fun ConnectButton(
    coreState: DesktopCoreState,
    isScanning: Boolean,
    scanStatus: String?,
    onClick: () -> Unit
) {
    val isRunning = coreState == DesktopCoreState.RUNNING
    val isStarting = coreState == DesktopCoreState.STARTING

    val buttonColor = when {
        isRunning -> AccentMint
        isStarting || isScanning -> AccentAmber
        coreState == DesktopCoreState.ERROR -> AccentRed.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.primary
    }

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = if (isStarting || isScanning) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(200.dp)
    ) {
        // Пульсирующий внешний круг (как PulseConnectingView в Android)
        if (isStarting || isScanning || isRunning) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                buttonColor.copy(alpha = 0.15f),
                                buttonColor.copy(alpha = 0.04f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Основная кнопка
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor,
                contentColor = Color.Black
            ),
            modifier = Modifier.size(160.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp, pressedElevation = 4.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isStarting || isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.Black,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Отмена",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.Black
                    )
                } else {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color.Black
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (coreState) {
                            DesktopCoreState.RUNNING -> "Стоп"
                            DesktopCoreState.ERROR -> "Повтор"
                            else -> "Старт"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }

    // Статус под кнопкой (как tvStatusDetail)
    if (isScanning && scanStatus != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = scanStatus,
            fontSize = 11.sp,
            color = AccentAmber,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp)
        )
    }
}

// ======= Кнопка сценария =======
@Composable
private fun ScenarioButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = title,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

// ======= Filter chip =======
@Composable
private fun FilterChipItem(
    label: String,
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
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

// ======= Карточка сервера =======
@Composable
private fun ServerItemCard(
    guid: String,
    profile: ProfileItem,
    isSelected: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Протокол badge
                ProtocolBadge(type = profile.configType)

                Spacer(Modifier.width(8.dp))

                // Страна/тег
                val countryTag = ServerTagResolver.resolveServerTag(profile.remarks)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = countryTag,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Имя + адрес
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.remarks.ifBlank { "Сервер" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.server ?: "—"}:${profile.serverPort ?: "—"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Пинг + действия
            Row(verticalAlignment = Alignment.CenterVertically) {
                PingBadge(latencyMs = ping, onClick = onPing)

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.QrCode2, "QR", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onCopyLink, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, "Копировать", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
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
        else -> "CUSTOM" to Color.Gray
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PingBadge(
    latencyMs: Long?,
    onClick: () -> Unit
) {
    val (text, color) = when {
        latencyMs == null -> "—" to Color.Gray
        latencyMs < 0L -> "Timeout" to AccentRed
        latencyMs < 120L -> "${latencyMs}ms" to AccentMint
        latencyMs < 300L -> "${latencyMs}ms" to AccentAmber
        else -> "${latencyMs}ms" to AccentRed
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
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
