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
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import com.kiktor.v2whitelist.desktop.state.SubStatusType
import com.kiktor.v2whitelist.desktop.ui.components.LocationFilterDialog
import com.kiktor.v2whitelist.desktop.ui.components.ModeSettingsDialog
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
    val isUpdatingSub by state.isUpdatingSub.collectAsState()
    val isV2WScanning by state.isV2WScanning.collectAsState()
    val fullScanState by state.fullScanState.collectAsState()
    val smartConnectStatus by state.smartConnectStatus.collectAsState()
    val routingMode by state.routingMode.collectAsState()
    val subStatus by state.subscriptionStatus.collectAsState()
    val showElapsedTime by state.subShowElapsedTime.collectAsState()
    val subscriptions by state.subscriptions.collectAsState()
    val exitIp by state.exitIp.collectAsState()
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val isManualSelection by state.isManualSelection.collectAsState()

    var qrProfile by remember { mutableStateOf<ProfileItem?>(null) }

    val activeProfile = remember(servers, selectedGuid) {
        val guid = selectedGuid ?: scenarioFilteredServers.firstOrNull()?.first ?: servers.firstOrNull()?.first
        servers.firstOrNull { it.first == guid }?.second
    }

    // Словарь имен подписок по ID
    val subNamesMap = remember(subscriptions) {
        subscriptions.associate { it.id to it.name }
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
            if (showElapsedTime) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Справа: статус актуальности подписки и время
                    Column(horizontalAlignment = Alignment.End) {
                        val statusColor = when (subStatus.type) {
                            SubStatusType.FAILED -> AccentRed
                            SubStatusType.ACTUAL, SubStatusType.NEUTRAL_UPDATED -> AccentMint
                            SubStatusType.NO_SUBS -> MaterialTheme.colorScheme.onSurfaceVariant
                            SubStatusType.UPDATING, SubStatusType.OUTDATED -> AccentAmber
                        }

                        val statusTitle = when (subStatus.type) {
                            SubStatusType.UPDATING -> strings.subUpdatingBtn
                            SubStatusType.FAILED -> "${strings.subStatusFailed} (${subStatus.failedCount})"
                            SubStatusType.NO_SUBS -> strings.noSubsText
                            SubStatusType.NEUTRAL_UPDATED -> strings.subStatusUpdatedNeutral
                            SubStatusType.ACTUAL -> strings.subStatusActual
                            SubStatusType.OUTDATED -> strings.subStatusOutdated
                        }

                        Text(
                            text = statusTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        val failedNames = remember(subscriptions, subStatus.type) {
                            if (subStatus.type != SubStatusType.FAILED) emptyList()
                            else subscriptions.filter {
                                it.enabled &&
                                    !com.kiktor.v2whitelist.core.model.PersonalServersConfig.isPersonalServer(it.id) &&
                                    it.lastUpdateFailed
                            }.map { it.name.ifBlank { strings.unknown } }
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
            }

            // ================= БАННЕР КОНФЛИКТОВ С ЗАПРЕТ / ДРУГИМ VPN =================
            val conflictWarning by state.conflictWarning.collectAsState()
            conflictWarning?.let { conflict ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = AccentAmber,
                                modifier = Modifier.size(20.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = conflict.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = conflict.details,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = { state.dismissConflictWarning() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = strings.btnDismiss,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

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
            val isFullScanning = fullScanState.isScanning
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.82f else 1f),
                border = BorderStroke(
                    1.dp,
                    when {
                        coreState == DesktopCoreState.RUNNING -> AccentMint.copy(alpha = 0.5f)
                        coreState == DesktopCoreState.STARTING || isFullScanning -> AccentAmber.copy(alpha = 0.5f)
                        coreState == DesktopCoreState.ERROR -> AccentRed.copy(alpha = 0.5f)
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
                    val dotColor = when {
                        coreState == DesktopCoreState.RUNNING -> AccentMint
                        coreState == DesktopCoreState.STARTING || isFullScanning -> AccentAmber
                        coreState == DesktopCoreState.ERROR -> AccentRed
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
                            text = when {
                                isFullScanning && coreState != DesktopCoreState.RUNNING -> strings.serverListFullScanRunning
                                coreState == DesktopCoreState.RUNNING -> strings.statusConnected
                                coreState == DesktopCoreState.STARTING -> strings.statusTesting
                                coreState == DesktopCoreState.ERROR -> strings.statusError
                                else -> strings.statusDisconnected
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = when {
                                isFullScanning && coreState != DesktopCoreState.RUNNING -> AccentAmber
                                coreState == DesktopCoreState.RUNNING -> AccentMint
                                coreState == DesktopCoreState.STARTING -> AccentAmber
                                coreState == DesktopCoreState.ERROR -> AccentRed
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )

                        val detailText = when {
                            isFullScanning && coreState != DesktopCoreState.RUNNING -> {
                                "${fullScanState.completedCount} / ${fullScanState.totalCount} • ${fullScanState.aliveCount}"
                            }
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

                    Spacer(Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = { state.promptTabChange(DesktopTab.SERVERS) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = strings.serverListOpenBtn, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (servers.isNotEmpty()) "${strings.navServers} (${servers.size})" else strings.navServers,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
            val isAnimating = coreState == DesktopCoreState.STARTING || isV2WScanning || isFullScanning

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
                    onClick = {
                        if (isFullScanning && coreState != DesktopCoreState.RUNNING && coreState != DesktopCoreState.STARTING) {
                            state.stopFullScan()
                        } else {
                            state.toggleConnect()
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isAnimating -> AccentAmber
                            coreState == DesktopCoreState.RUNNING -> AccentMint
                            coreState == DesktopCoreState.ERROR -> AccentRed
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = when {
                            isAnimating || coreState == DesktopCoreState.RUNNING || coreState == DesktopCoreState.ERROR -> Color.Black
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    modifier = Modifier.size(220.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp, pressedElevation = 2.dp),
                    border = BorderStroke(
                        2.dp,
                        if (!isAnimating && coreState == DesktopCoreState.STOPPED) MaterialTheme.colorScheme.outline else Color.Transparent
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

            // Живой статус подключения или полного сканирования под центральной кнопкой
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
                            text = if (isFullScanning && coreState != DesktopCoreState.STARTING) {
                                "${strings.serverListFullScanRunning} (${fullScanState.completedCount}/${fullScanState.totalCount})"
                            } else {
                                smartConnectStatus?.ifBlank { null } ?: strings.statusTestingServers
                            },
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

                // Быстрые действия: кнопка «Режим» (слева) и «Локации» (справа)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speedEnabled by state.speedTestOnCheck.collectAsState()
                    val minSpeed by state.minSpeedThresholdMbps.collectAsState()
                    val modeLabel = when (scenario) {
                        AppScenario.WHITELIST -> strings.modeOptionWl
                        AppScenario.VPN_BLACKLIST, AppScenario.YOUTUBE -> strings.modeOptionBl
                        else -> strings.modeOptionAuto
                    }
                    val speedBadge = if (!speedEnabled || minSpeed <= 0.0) {
                        "0"
                    } else if (minSpeed % 1.0 == 0.0) {
                        "${minSpeed.toInt()} ${strings.modeSpeedUnitMbps}"
                    } else {
                        "${minSpeed} ${strings.modeSpeedUnitMbps}"
                    }
                    FilledTonalButton(
                        onClick = { state.promptModeSettingsDialog() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${String.format(strings.btnModeLabel, modeLabel)} • $speedBadge",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val locMode by state.locationFilterMode.collectAsState()
                    val locSet by state.locationFilterSet.collectAsState()
                    val availLocs by state.availableLocations.collectAsState()
                    val locBtnLabel = when {
                        locMode == com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode.WHITELIST && locSet.isNotEmpty() ->
                            "${strings.locationsFilter} (${locSet.size}/${availLocs.size})"
                        locMode == com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode.EXCLUDE && locSet.isNotEmpty() ->
                            "${strings.locationsFilter} (-${locSet.size}/${availLocs.size})"
                        else -> "${strings.locationsFilter} (${availLocs.size})"
                    }
                    OutlinedButton(
                        onClick = { state.promptLocationFilterDialog() },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(locBtnLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

        // Диалог настройки режима, скорости и проверки сервисов
        ModeSettingsDialog(state = state)

        // Диалог фильтра локаций серверов (1-в-1 как в Android версии)
        LocationFilterDialog(state = state)

        // Диалог проверки доступности сервисов
        com.kiktor.v2whitelist.desktop.ui.components.ServiceAvailabilityDialog(state = state)
    }
}
