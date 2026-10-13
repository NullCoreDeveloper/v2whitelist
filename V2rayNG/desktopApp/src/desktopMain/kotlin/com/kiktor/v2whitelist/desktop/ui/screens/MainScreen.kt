package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.components.PulseConnectingView
import com.kiktor.v2whitelist.desktop.ui.components.QrCodeDialog
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun MainScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val servers by state.servers.collectAsState()
    val scenarioFilteredServers by state.scenarioFilteredServers.collectAsState()
    val selectedGuid by state.selectedGuid.collectAsState()
    val coreState by state.coreState.collectAsState()
    val scenario by state.scenario.collectAsState()
    val lastError by state.lastError.collectAsState()
    val pingMap by state.pingMap.collectAsState()
    val isPinging by state.isPinging.collectAsState()
    val isUpdatingSub by state.isUpdatingSub.collectAsState()
    val isV2WScanning by state.isV2WScanning.collectAsState()
    val smartConnectStatus by state.smartConnectStatus.collectAsState()
    val routingMode by state.routingMode.collectAsState()
    val subStatus by state.subscriptionStatus.collectAsState()
    val subscriptions by state.subscriptions.collectAsState()
    val exitIp by state.exitIp.collectAsState()

    var isServerListExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf<EConfigType?>(null) }
    var sortByPing by remember { mutableStateOf(false) }
    var qrProfile by remember { mutableStateOf<ProfileItem?>(null) }

    val activeProfile = remember(servers, selectedGuid) {
        val guid = selectedGuid ?: scenarioFilteredServers.firstOrNull()?.first ?: servers.firstOrNull()?.first
        servers.firstOrNull { it.first == guid }?.second
    }

    // Словарь имен подписок по ID
    val subNamesMap = remember(subscriptions) {
        subscriptions.associate { it.id to it.name }
    }

    val displayServers = remember(scenarioFilteredServers, searchQuery, selectedProtocol, sortByPing, pingMap) {
        var list = scenarioFilteredServers
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

    if (qrProfile != null) {
        QrCodeDialog(
            profile = qrProfile!!,
            onCopy = { text ->
                state.copyToClipboard(text, qrProfile?.remarks ?: "Ссылка узла")
            },
            onDismiss = { qrProfile = null }
        )
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ================= 1. ШАПКА В СТИЛЕ ANDROID ПРОБЕЛ =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Слева: жирный логотип "пробел"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "пробел",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Справа: статус актуальности подписки и время
                Column(horizontalAlignment = Alignment.End) {
                    val statusColor = when {
                        subStatus.failedCount > 0 -> AccentRed
                        subStatus.isActual -> AccentMint
                        subStatus.statusText == "Нет подписок" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> AccentAmber
                    }

                    Text(
                        text = subStatus.statusText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    if (subStatus.timeText.isNotBlank()) {
                        Text(
                            text = subStatus.timeText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ================= 2. СТАТУС-КАРТОЧКА =================
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    when (coreState) {
                        DesktopCoreState.RUNNING -> AccentMint.copy(alpha = 0.5f)
                        DesktopCoreState.STARTING -> AccentAmber.copy(alpha = 0.5f)
                        DesktopCoreState.ERROR -> AccentRed.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Индикатор состояния
                    val dotColor = when (coreState) {
                        DesktopCoreState.RUNNING -> AccentMint
                        DesktopCoreState.STARTING -> AccentAmber
                        DesktopCoreState.ERROR -> AccentRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (coreState) {
                                DesktopCoreState.RUNNING -> "Подключено"
                                DesktopCoreState.STARTING -> "Поиск и подключение…"
                                DesktopCoreState.ERROR -> "Ошибка соединения"
                                DesktopCoreState.STOPPED -> "Не подключено"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = when (coreState) {
                                DesktopCoreState.RUNNING -> AccentMint
                                DesktopCoreState.STARTING -> AccentAmber
                                DesktopCoreState.ERROR -> AccentRed
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )

                        val detailText = when {
                            isV2WScanning && smartConnectStatus != null -> smartConnectStatus.orEmpty()
                            coreState == DesktopCoreState.RUNNING -> activeProfile?.remarks ?: "Защищённый туннель активен"
                            else -> "Нажмите Старт для безопасного подключения"
                        }
                        Text(
                            text = detailText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (coreState == DesktopCoreState.RUNNING && !exitIp.isNullOrBlank()) {
                            Text(
                                text = "Exit IP: $exitIp",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // Ошибка если есть
            AnimatedVisibility(visible = lastError != null, enter = fadeIn(), exit = fadeOut()) {
                lastError?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AccentRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 680.dp)
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            text = err,
                            color = AccentRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            // ================= 3. ЦЕНТРАЛЬНАЯ КНОПКА ПОДКЛЮЧЕНИЯ (220dp) =================
            val isAnimating = coreState == DesktopCoreState.STARTING || isV2WScanning

            Box(
                modifier = Modifier
                    .size(320.dp),
                contentAlignment = Alignment.Center
            ) {
                // Анимированный PulseConnectingView вокруг кнопки
                if (isAnimating) {
                    PulseConnectingView(
                        modifier = Modifier.fillMaxSize(),
                        size = 320.dp,
                        activeColor = AccentAmber
                    )
                }

                // Круглая кнопка подключения (220dp, как btn_big_connect на Android)
                Button(
                    onClick = { state.toggleConnect() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (coreState) {
                            DesktopCoreState.RUNNING -> AccentMint
                            DesktopCoreState.STARTING -> AccentAmber
                            DesktopCoreState.ERROR -> AccentRed
                            DesktopCoreState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = when (coreState) {
                            DesktopCoreState.STOPPED -> MaterialTheme.colorScheme.onSurface
                            else -> Color.Black
                        }
                    ),
                    modifier = Modifier.size(220.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp, pressedElevation = 2.dp),
                    border = BorderStroke(
                        2.dp,
                        if (coreState == DesktopCoreState.STOPPED) MaterialTheme.colorScheme.outline else Color.Transparent
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isAnimating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = Color.Black,
                                strokeWidth = 3.5.dp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Отмена",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.Black
                            )
                        } else {
                            Icon(
                                imageVector = if (coreState == DesktopCoreState.RUNNING) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = if (coreState == DesktopCoreState.STOPPED) MaterialTheme.colorScheme.primary else Color.Black
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = when (coreState) {
                                    DesktopCoreState.RUNNING -> "Стоп"
                                    DesktopCoreState.ERROR -> "Повтор"
                                    else -> "Старт"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = if (coreState == DesktopCoreState.STOPPED) MaterialTheme.colorScheme.onSurface else Color.Black
                            )
                        }
                    }
                }
            }

            // ================= 4. КНОПКА «СМЕНИТЬ СЕРВЕР» (ВИДНА ТОЛЬКО ПРИ ПОДКЛЮЧЕНИИ) =================
            AnimatedVisibility(visible = coreState == DesktopCoreState.RUNNING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { state.runSmartConnect() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 340.dp)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сменить сервер", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ================= 5. ПЕРЕКЛЮЧАТЕЛЬ ПРОКСИ / TUN И ВЫБОР РЕЖИМА =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Переключатель Прокси / TUN
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Режим работы: ${if (routingMode == "TUN") "TUN (Виртуальный адаптер)" else "Прокси (SOCKS5 / HTTP)"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (routingMode == "TUN") "Весь сетевой трафик системы идет через TUN туннель" else "Системный прокси на 127.0.0.1:10808 / 10809",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { state.toggleRoutingMode() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (routingMode == "TUN") AccentMint else MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(if (routingMode == "TUN") "Включен TUN" else "Включен Прокси", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 4 сценария: Авто, Белый список, Чёрный список, YouTube и музыка
                Text(
                    text = "Сценарий маршрутизации:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScenarioChip(
                        title = "Авто",
                        subtitle = "Баланс БС и ЧС",
                        selected = scenario == AppScenario.AUTO,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.AUTO) }
                    )
                    ScenarioChip(
                        title = "Белый список",
                        subtitle = "БС (Только РФ)",
                        selected = scenario == AppScenario.WHITELIST,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.WHITELIST) }
                    )
                    ScenarioChip(
                        title = "Чёрный список",
                        subtitle = "ЧС (Обход блоков)",
                        selected = scenario == AppScenario.VPN_BLACKLIST,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.VPN_BLACKLIST) }
                    )
                    ScenarioChip(
                        title = "YouTube",
                        subtitle = "YouTube и Музыка",
                        selected = scenario == AppScenario.YOUTUBE,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.YOUTUBE) }
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ================= 6. СПИСОК СЕРВЕРОВ (ПО УМОЛЧАНИЮ СКРЫТ) =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
            ) {
                // Заголовок-аккордеон
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isServerListExpanded = !isServerListExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isServerListExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (isServerListExpanded) "Скрыть список серверов" else "Показать список серверов (${displayServers.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Кнопка быстрого пинг-теста
                            OutlinedButton(
                                onClick = { state.pingAllServers() },
                                enabled = !isPinging && servers.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("Пинг", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Раскрывающееся содержимое списка серверов
                AnimatedVisibility(visible = isServerListExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        // Строка поиска и фильтров
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Поиск узлов по названию или хосту...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        )

                        Spacer(Modifier.height(8.dp))

                        // Фильтры протоколов
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedProtocol == null,
                                    onClick = { selectedProtocol = null },
                                    label = { Text("Все", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedProtocol == EConfigType.VLESS,
                                    onClick = { selectedProtocol = if (selectedProtocol == EConfigType.VLESS) null else EConfigType.VLESS },
                                    label = { Text("VLESS", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedProtocol == EConfigType.VMESS,
                                    onClick = { selectedProtocol = if (selectedProtocol == EConfigType.VMESS) null else EConfigType.VMESS },
                                    label = { Text("VMESS", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedProtocol == EConfigType.TROJAN,
                                    onClick = { selectedProtocol = if (selectedProtocol == EConfigType.TROJAN) null else EConfigType.TROJAN },
                                    label = { Text("Trojan", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedProtocol == EConfigType.SHADOWSOCKS,
                                    onClick = { selectedProtocol = if (selectedProtocol == EConfigType.SHADOWSOCKS) null else EConfigType.SHADOWSOCKS },
                                    label = { Text("SS", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedProtocol == EConfigType.HYSTERIA2,
                                    onClick = { selectedProtocol = if (selectedProtocol == EConfigType.HYSTERIA2) null else EConfigType.HYSTERIA2 },
                                    label = { Text("Hy2", fontSize = 11.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = sortByPing,
                                    onClick = { sortByPing = !sortByPing },
                                    label = { Text("⚡ Пинг ▲", fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Сами карточки серверов
                        if (displayServers.isEmpty()) {
                            Text(
                                text = "Нет серверов по выбранным критериям",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            for ((guid, profile) in displayServers) {
                                val isSelected = guid == selectedGuid
                                val ping = pingMap[guid]
                                val subName = subNamesMap[profile.subscriptionId] ?: profile.subscriptionId.ifBlank { "Подписка" }

                                ServerCardItem(
                                    profile = profile,
                                    subscriptionName = subName,
                                    isSelected = isSelected,
                                    ping = ping,
                                    onSelect = { state.selectServer(guid) },
                                    onPing = { state.pingServer(guid) },
                                    onQr = { qrProfile = profile },
                                    onDelete = { state.deleteServer(guid) }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp)) // отступ под нижнюю кнопку QR
        }

        // ================= 7. КНОПКА QR В ПРАВОМ НИЖНЕМ УГЛУ =================
        FloatingActionButton(
            onClick = {
                val current = activeProfile ?: servers.firstOrNull()?.second
                if (current != null) {
                    qrProfile = current
                } else {
                    state.copyToClipboard("", "Нет активного сервера")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(54.dp)
        ) {
            Icon(Icons.Default.QrCode2, contentDescription = "Показать QR код узла", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun ScenarioChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ServerCardItem(
    profile: ProfileItem,
    subscriptionName: String,
    isSelected: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onQr: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
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
                // Протокол
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = profile.configType.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.remarks.ifBlank { "${profile.server}:${profile.serverPort}" },
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Бейдж подписки, откуда загружен сервер
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Источник: $subscriptionName",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!profile.server.isNullOrBlank()) {
                            Text(
                                text = " • ${profile.server}:${profile.serverPort}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            // Пинг + действия
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ping != null) {
                    val pingColor = when {
                        ping < 0 -> AccentRed
                        ping < 250 -> AccentMint
                        ping < 600 -> AccentAmber
                        else -> AccentRed
                    }
                    Text(
                        text = if (ping > 0) "${ping}ms" else "Сбой",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = pingColor,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                IconButton(onClick = onPing, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Проверить пинг", modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onQr, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.QrCode, contentDescription = "QR код", modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = AccentRed, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
