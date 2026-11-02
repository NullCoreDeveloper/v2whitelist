package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.SubStatusType
import com.kiktor.v2whitelist.desktop.ui.components.LocationFilterDialog
import com.kiktor.v2whitelist.desktop.ui.components.PulseConnectingView
import com.kiktor.v2whitelist.desktop.ui.components.QrCodeDialog
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun MainScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
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
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val vipGuids by state.vipGuids.collectAsState()
    val isManualSelection by state.isManualSelection.collectAsState()

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
        val qrSubName = qrProfile?.subscriptionId?.let { subNamesMap[it] }?.takeIf { it.isNotBlank() }
        QrCodeDialog(
            profile = qrProfile!!,
            subscriptionName = qrSubName,
            onCopy = { text ->
                state.copyToClipboard(text, qrProfile?.remarks ?: strings.qrCopyLink)
            },
            onDismiss = { qrProfile = null }
        )
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (meshEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ================= 1. ШАПКА =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Справа: статус актуальности подписки и время
                Column(horizontalAlignment = Alignment.End) {
                    val statusColor = when (subStatus.type) {
                        SubStatusType.FAILED -> AccentRed
                        SubStatusType.ACTUAL -> AccentMint
                        SubStatusType.NO_SUBS -> MaterialTheme.colorScheme.onSurfaceVariant
                        SubStatusType.UPDATING, SubStatusType.OUTDATED -> AccentAmber
                    }

                    val statusTitle = when (subStatus.type) {
                        SubStatusType.UPDATING -> strings.subUpdatingBtn
                        SubStatusType.FAILED -> "${strings.subStatusFailed} (${subStatus.failedCount})"
                        SubStatusType.NO_SUBS -> strings.noSubsText
                        SubStatusType.ACTUAL -> strings.subStatusActual
                        SubStatusType.OUTDATED -> strings.subStatusOutdated
                    }

                    Text(
                        text = statusTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    val failedNames = remember(subscriptions) {
                        subscriptions.filter { it.enabled && it.lastUpdateFailed }.map { it.name.ifBlank { strings.unknown } }
                    }
                    if (failedNames.isNotEmpty()) {
                        Text(
                            text = "${strings.subFailureBanner}${failedNames.joinToString(", ")}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (subStatus.timeText.isNotBlank()) {
                        Text(
                            text = subStatus.timeText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ================= БАННЕР ОБНОВЛЕНИЯ =================
            val availableUpdate by state.availableAppUpdate.collectAsState()
            if (availableUpdate != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.NewReleases,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "${strings.updateAvailableTitle}${availableUpdate?.latestVersion ?: ""}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = strings.updateAvailableSubtitle,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = { state.showAppUpdateDialog.value = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(strings.btnUpdate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { state.dismissUpdateBanner() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = strings.btnDismiss,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ================= 2. СТАТУС-КАРТОЧКА =================
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.82f else 1f),
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
                                DesktopCoreState.RUNNING -> strings.statusConnected
                                DesktopCoreState.STARTING -> strings.statusTesting
                                DesktopCoreState.ERROR -> strings.statusError
                                DesktopCoreState.STOPPED -> strings.statusDisconnected
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
                            coreState == DesktopCoreState.STARTING -> {
                                smartConnectStatus?.ifBlank { null } ?: strings.statusTestingServers
                            }
                            coreState == DesktopCoreState.RUNNING -> {
                                val modePrefix = if (isManualSelection) strings.manualSelectionPrefix else ""
                                val name = activeProfile?.remarks.orEmpty().ifBlank { activeProfile?.server.orEmpty() }
                                val subName = activeProfile?.subscriptionId?.let { subNamesMap[it] }?.takeIf { it.isNotBlank() }
                                val subSuffix = if (!subName.isNullOrBlank()) " • $subName" else ""
                                if (name.isNotBlank()) "$modePrefix$name$subSuffix" else strings.tunnelActive
                            }
                            isManualSelection && activeProfile != null -> "${strings.scenarioManual}: ${activeProfile.remarks.ifBlank { activeProfile.server }}"
                            activeProfile != null -> "${strings.scenarioAuto}: ${scenario.name}"
                            else -> strings.pressStartToConnect
                        }
                        Text(
                            text = detailText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
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
                                text = strings.btnCancel,
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
                                    DesktopCoreState.RUNNING -> strings.btnStop
                                    DesktopCoreState.ERROR -> strings.btnRetry
                                    else -> strings.btnStart
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = if (coreState == DesktopCoreState.STOPPED) MaterialTheme.colorScheme.onSurface else Color.Black
                            )
                        }
                    }
                }
            }

            // Живой статус подключения под центральной кнопкой (1-в-1 как на Android)
            AnimatedVisibility(
                visible = isAnimating,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = AccentAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .widthIn(max = 500.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = AccentAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = smartConnectStatus?.ifBlank { null } ?: strings.statusTestingServers,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentAmber,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ================= 4. КНОПКА «СМЕНИТЬ СЕРВЕР» (ВИДНА ТОЛЬКО ПРИ ПОДКЛЮЧЕНИИ) =================
            AnimatedVisibility(visible = coreState == DesktopCoreState.RUNNING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    val isSwitchingServer by state.isSwitchingServer.collectAsState()
                    Button(
                        onClick = { state.switchToNextServer() },
                        enabled = !isSwitchingServer,
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
                        if (isSwitchingServer) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.btnSwitchingServer, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.btnSwitchServer, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
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
                                text = "${strings.routingModeTitle}${if (routingMode == "TUN") strings.routingModeTun else strings.routingModeProxy}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (routingMode == "TUN") strings.routingModeTunDesc else strings.routingModeProxyDesc,
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
                            Text(if (routingMode == "TUN") strings.tunEnabled else strings.proxyEnabled, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 4 сценария: Авто, Белый список, Чёрный список, YouTube и музыка
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isManualSelection) strings.scenarioManual else strings.scenarioAuto,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isManualSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { state.promptLocationFilterDialog() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.locationsFilter, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScenarioChip(
                        title = strings.scenarioAutoTitle,
                        subtitle = strings.scenarioAutoSubtitle,
                        selected = !isManualSelection && scenario == AppScenario.AUTO,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.AUTO) }
                    )
                    ScenarioChip(
                        title = strings.scenarioWhitelistTitle,
                        subtitle = strings.scenarioWhitelistSubtitle,
                        selected = !isManualSelection && scenario == AppScenario.WHITELIST,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.WHITELIST) }
                    )
                    ScenarioChip(
                        title = strings.scenarioBlacklistTitle,
                        subtitle = strings.scenarioBlacklistSubtitle,
                        selected = !isManualSelection && scenario == AppScenario.VPN_BLACKLIST,
                        modifier = Modifier.weight(1f),
                        onClick = { state.setScenario(AppScenario.VPN_BLACKLIST) }
                    )
                    ScenarioChip(
                        title = strings.scenarioYoutubeTitle,
                        subtitle = strings.scenarioYoutubeSubtitle,
                        selected = !isManualSelection && scenario == AppScenario.YOUTUBE,
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
                                text = if (isServerListExpanded) strings.hideServerList else "${strings.showServerList} (${displayServers.size})",
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
                                Text(strings.ping, fontSize = 11.sp)
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
                        // Строка поиска узлов с идеальным вертикальным центрированием без обрезания букв
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.70f else 0.50f),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp),
                            decorationBox = { innerTextField ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = strings.searchNodesPlaceholder,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = strings.clear,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
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
                                    label = { Text(strings.all, fontSize = 11.sp) }
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
                                    label = { Text(strings.pingAsc, fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Сами карточки серверов
                        if (displayServers.isEmpty()) {
                            Text(
                                text = strings.noServersFoundCriteria,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 540.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(displayServers, key = { it.first }) { (guid, profile) ->
                                    val isSelected = guid == selectedGuid
                                    val isVip = guid in vipGuids
                                    val ping = pingMap[guid]
                                    val subName = subNamesMap[profile.subscriptionId] ?: profile.subscriptionId.ifBlank { strings.subscriptions }

                                    ServerCardItem(
                                        profile = profile,
                                        subscriptionName = subName,
                                        isSelected = isSelected,
                                        isVip = isVip,
                                        ping = ping,
                                        onSelect = { state.selectServer(guid) },
                                        onToggleVip = { state.toggleVip(guid) },
                                        onPing = { state.pingServer(guid) },
                                        onQr = { qrProfile = profile },
                                        onDelete = { state.deleteServer(guid) }
                                    )
                                }
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
                    state.copyToClipboard("", strings.noActiveServer)
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
            Icon(Icons.Default.QrCode2, contentDescription = strings.qrShowCode, modifier = Modifier.size(26.dp))
        }

        // Диалог фильтра локаций серверов (1-в-1 как в Android версии)
        LocationFilterDialog(state = state)

        // Диалог проверки доступности сервисов
        com.kiktor.v2whitelist.desktop.ui.components.ServiceAvailabilityDialog(state = state)
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
    isVip: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onToggleVip: () -> Unit,
    onPing: () -> Unit,
    onQr: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
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
                            text = "${strings.source}$subscriptionName",
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
                        text = if (ping > 0) "${ping}ms" else "—",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = pingColor,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                IconButton(onClick = onToggleVip, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = if (isVip) strings.geekRemoveVip else strings.findVip,
                        tint = if (isVip) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onPing, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = strings.ping, modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onQr, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.QrCode, contentDescription = "QR", modifier = Modifier.size(15.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = strings.delete, tint = AccentRed, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
