package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.domain.inspection.EndpointFilterMode
import com.kiktor.v2whitelist.core.domain.inspection.ServerInspectionCachePolicy
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.domain.inspection.ServerListFilter
import com.kiktor.v2whitelist.core.domain.inspection.ServerSortOrder
import com.kiktor.v2whitelist.core.domain.inspection.ServerStatusFilter
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ScanNetworkCondition
import com.kiktor.v2whitelist.core.model.inspection.countryCodeToFlagEmoji
import com.kiktor.v2whitelist.desktop.engine.DesktopFullScanMode
import com.kiktor.v2whitelist.desktop.engine.DesktopServerListUiState
import com.kiktor.v2whitelist.desktop.engine.DesktopSpeedTestState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.components.QrCodeDialog
import com.kiktor.v2whitelist.desktop.ui.i18n.AppStrings
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*
import com.kiktor.v2whitelist.desktop.ui.util.CountryNames
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DEFAULT_VISIBLE_PAGE_SIZE = 80
private val GOLD_COLOR = Color(0xFFD4AF37)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ServerListScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val appLanguage by state.appLanguage.collectAsState()
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val uiState by state.serverListUiState.collectAsState()
    val subscriptions by state.subscriptions.collectAsState()

    var searchInput by remember { mutableStateOf(uiState.filter.searchQuery) }
    var expandedGuids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var visibleRegularLimit by remember { mutableStateOf(DEFAULT_VISIBLE_PAGE_SIZE) }
    var showFiltersDialog by remember { mutableStateOf(false) }
    var qrProfile by remember { mutableStateOf<ProfileItem?>(null) }

    val subNamesMap = remember(subscriptions) {
        subscriptions.associate { it.id to it.name }
    }

    if (qrProfile != null) {
        val qrSubName = qrProfile?.subscriptionId?.let { subNamesMap[it] }?.takeIf { it.isNotBlank() }
        QrCodeDialog(
            profile = qrProfile!!,
            subscriptionName = qrSubName,
            onCopy = { text -> state.copyToClipboard(text, qrProfile?.remarks ?: strings.qrCopyLink) },
            onDismiss = { qrProfile = null }
        )
    }

    if (showFiltersDialog) {
        ServerFiltersDialog(
            state = uiState,
            appLanguage = appLanguage,
            strings = strings,
            onApply = { newFilter ->
                visibleRegularLimit = DEFAULT_VISIBLE_PAGE_SIZE
                state.fullScanManager.updateFilter(newFilter)
                showFiltersDialog = false
            },
            onReset = {
                searchInput = ""
                visibleRegularLimit = DEFAULT_VISIBLE_PAGE_SIZE
                state.fullScanManager.resetFilters()
                showFiltersDialog = false
            },
            onDismiss = { showFiltersDialog = false }
        )
    }

    val scan = uiState.scanState
    val smartBusy = uiState.isSmartConnectScanning
    val stats = uiState.viewResult.stats
    val vipServers = uiState.viewResult.vipServers
    val allRegular = uiState.viewResult.regularServers
    val visibleRegular = remember(allRegular, visibleRegularLimit) {
        if (allRegular.size > visibleRegularLimit) allRegular.take(visibleRegularLimit) else allRegular
    }
    val hasMoreRegular = allRegular.size > visibleRegular.size
    val activeFilterCount = uiState.filter.activeFilterCount(uiState.endpointEnabled)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (meshEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 880.dp)
                    .weight(1f)
            ) {
                // Заголовок экрана
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.serverListTitle,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (appLanguage.equals("EN", ignoreCase = true)) {
                            "Total: ${stats.totalServers} • Alive: ${stats.aliveCount} • VIP: ${stats.vipCount} • Dupes removed: ${stats.removedDuplicatesCount}"
                        } else {
                            "Всего: ${stats.totalServers} • Рабочих: ${stats.aliveCount} • VIP: ${stats.vipCount} • Дубликатов удалено: ${stats.removedDuplicatesCount}"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Дашборд управления сканированием и фильтрами
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.85f else 1f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val meta = uiState.lastScanMeta
                        val isStale = meta != null && meta.finishedAtMs > 0L &&
                            ServerInspectionCachePolicy.isStale(meta.finishedAtMs, System.currentTimeMillis())

                        val metaText = when {
                            smartBusy -> strings.serverListSmartConnectBusy
                            scan.isScanning -> {
                                if (appLanguage.equals("EN", ignoreCase = true)) {
                                    "Scanning: ${scan.completedCount} / ${scan.totalCount} • Alive: ${scan.aliveCount}"
                                } else {
                                    "Сканирование: ${scan.completedCount} / ${scan.totalCount} • Рабочих: ${scan.aliveCount}"
                                }
                            }
                            meta != null && meta.finishedAtMs > 0L -> {
                                val timeStr = formatScanTimestamp(meta.finishedAtMs)
                                val showCond = uiState.networkCheckEnabled && meta.networkCheckEnabled &&
                                    meta.networkCondition != ScanNetworkCondition.UNKNOWN
                                val condLabel = if (showCond) {
                                    when (meta.networkCondition) {
                                        ScanNetworkCondition.BLACKLIST -> strings.serverListCondBlacklist
                                        ScanNetworkCondition.WHITELIST -> strings.serverListCondWhitelist
                                        ScanNetworkCondition.NO_INTERNET -> strings.serverListCondNoInternet
                                        ScanNetworkCondition.UNKNOWN -> ""
                                    }
                                } else ""
                                val prefix = if (appLanguage.equals("EN", ignoreCase = true)) "Last scan: $timeStr" else "Последний скан: $timeStr"
                                val full = if (condLabel.isNotEmpty()) "$prefix • $condLabel" else prefix
                                if (isStale) full + strings.serverListStaleSuffix else full
                            }
                            else -> strings.serverListNoScanYet
                        }

                        Text(
                            text = metaText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (!scan.isScanning && !smartBusy && isStale) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (smartBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (scan.isScanning) {
                            if (scan.totalCount > 0) {
                                val progress = (scan.completedCount.toFloat() / scan.totalCount.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        // Основные кнопки
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val canClickFull = !smartBusy && (!scan.isScanning || scan.mode == DesktopFullScanMode.FULL)
                            Button(
                                onClick = { state.startOrStopFullScan() },
                                enabled = canClickFull,
                                shape = RoundedCornerShape(10.dp),
                                colors = if (scan.isScanning && scan.mode == DesktopFullScanMode.FULL) {
                                    ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = Color.White)
                                } else {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.Black
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (scan.isScanning && scan.mode == DesktopFullScanMode.FULL) Icons.Default.Stop else Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        smartBusy -> strings.serverListSmartConnectBusy
                                        scan.isScanning && scan.mode == DesktopFullScanMode.FULL -> strings.serverListBtnStop
                                        else -> strings.serverListBtnFullScan
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { showFiltersDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (activeFilterCount > 0) "${strings.serverListBtnFilters} ($activeFilterCount)" else strings.serverListBtnFilters,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            val isFindVip = scan.isScanning && scan.mode == DesktopFullScanMode.VIP_FIND
                            OutlinedButton(
                                onClick = { state.findMoreVipServers() },
                                enabled = !smartBusy && (!scan.isScanning || isFindVip),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isFindVip) strings.serverListBtnStop else strings.geekFindVipBtn,
                                    fontSize = 12.sp
                                )
                            }

                            val isPingVip = scan.isScanning && scan.mode == DesktopFullScanMode.VIP_PING
                            OutlinedButton(
                                onClick = { state.pingVipServers() },
                                enabled = !smartBusy && stats.vipCount > 0 && (!scan.isScanning || isPingVip),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isPingVip) strings.serverListBtnStop else strings.geekPingVipBtn,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Тумблер проверки сервисов (по умолчанию выключен)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (uiState.endpointEnabled) {
                                    strings.serverListSwitchServicesDisabledByEndpoint
                                } else {
                                    strings.serverListSwitchCheckServices
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 12.dp)
                            )
                            Switch(
                                checked = !uiState.endpointEnabled && uiState.scanCheckServicesEnabled,
                                onCheckedChange = { state.setScanCheckServicesEnabled(it) },
                                enabled = !scan.isScanning && !smartBusy && !uiState.endpointEnabled
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Поле поиска
                BasicTextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        visibleRegularLimit = DEFAULT_VISIBLE_PAGE_SIZE
                        state.fullScanManager.setSearchQuery(it)
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.72f else 0.55f),
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
                                if (searchInput.isEmpty()) {
                                    Text(
                                        text = strings.serverListSearchHint,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                            if (searchInput.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchInput = ""
                                        visibleRegularLimit = DEFAULT_VISIBLE_PAGE_SIZE
                                        state.fullScanManager.setSearchQuery("")
                                    },
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

                Text(
                    text = strings.serverListDoubleClickHint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
                )

                // Список серверов
                if (vipServers.isEmpty() && visibleRegular.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = strings.serverListEmpty,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!uiState.filter.isDefault(uiState.endpointEnabled)) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    searchInput = ""
                                    visibleRegularLimit = DEFAULT_VISIBLE_PAGE_SIZE
                                    state.fullScanManager.resetFilters()
                                }) {
                                    Text(strings.serverListResetFilters)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (vipServers.isNotEmpty()) {
                            item(key = "header_vip", contentType = "header") {
                                Text(
                                    text = "★ ${strings.serverListVipSection} (${vipServers.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GOLD_COLOR,
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
                                )
                            }
                            items(
                                items = vipServers,
                                key = { "vip_${it.guid}" },
                                contentType = { "server" }
                            ) { entry ->
                                val isConnected = uiState.isVpnRunning && uiState.connectedGuid == entry.guid
                                ServerInspectionCard(
                                    entry = entry,
                                    isExpanded = entry.guid in expandedGuids,
                                    isConnected = isConnected,
                                    endpointEnabled = uiState.endpointEnabled,
                                    speedState = uiState.speedTestStates[entry.guid] ?: DesktopSpeedTestState.Idle,
                                    isReinspecting = entry.guid in uiState.reinspectingGuids,
                                    fallbackFlag = uiState.fallbackFlags[entry.guid].orEmpty(),
                                    appLanguage = appLanguage,
                                    strings = strings,
                                    meshEnabled = meshEnabled,
                                    onToggleExpand = {
                                        expandedGuids = if (entry.guid in expandedGuids) expandedGuids - entry.guid else expandedGuids + entry.guid
                                    },
                                    onDoubleClick = {
                                        if (isConnected) {
                                            expandedGuids = if (entry.guid in expandedGuids) expandedGuids - entry.guid else expandedGuids + entry.guid
                                        } else {
                                            state.connectToServerDirectly(entry.guid)
                                        }
                                    },
                                    onConnect = { state.connectToServerDirectly(entry.guid) },
                                    onDisconnect = { state.stopCore() },
                                    onSpeedTest = { state.runServerSpeedTest(entry.guid) },
                                    onToggleVip = { state.toggleServerVip(entry.guid) },
                                    onRetest = { state.reinspectServer(entry.guid) },
                                    onShowQr = { qrProfile = entry.profile },
                                    onDelete = { state.deleteServer(entry.guid) }
                                )
                            }
                        }

                        if (visibleRegular.isNotEmpty()) {
                            item(key = "header_regular", contentType = "header") {
                                Text(
                                    text = "${strings.serverListAllSection} (${allRegular.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 2.dp)
                                )
                            }
                            items(
                                items = visibleRegular,
                                key = { "reg_${it.guid}" },
                                contentType = { "server" }
                            ) { entry ->
                                val isConnected = uiState.isVpnRunning && uiState.connectedGuid == entry.guid
                                ServerInspectionCard(
                                    entry = entry,
                                    isExpanded = entry.guid in expandedGuids,
                                    isConnected = isConnected,
                                    endpointEnabled = uiState.endpointEnabled,
                                    speedState = uiState.speedTestStates[entry.guid] ?: DesktopSpeedTestState.Idle,
                                    isReinspecting = entry.guid in uiState.reinspectingGuids,
                                    fallbackFlag = uiState.fallbackFlags[entry.guid].orEmpty(),
                                    appLanguage = appLanguage,
                                    strings = strings,
                                    meshEnabled = meshEnabled,
                                    onToggleExpand = {
                                        expandedGuids = if (entry.guid in expandedGuids) expandedGuids - entry.guid else expandedGuids + entry.guid
                                    },
                                    onDoubleClick = {
                                        if (isConnected) {
                                            expandedGuids = if (entry.guid in expandedGuids) expandedGuids - entry.guid else expandedGuids + entry.guid
                                        } else {
                                            state.connectToServerDirectly(entry.guid)
                                        }
                                    },
                                    onConnect = { state.connectToServerDirectly(entry.guid) },
                                    onDisconnect = { state.stopCore() },
                                    onSpeedTest = { state.runServerSpeedTest(entry.guid) },
                                    onToggleVip = { state.toggleServerVip(entry.guid) },
                                    onRetest = { state.reinspectServer(entry.guid) },
                                    onShowQr = { qrProfile = entry.profile },
                                    onDelete = { state.deleteServer(entry.guid) }
                                )
                            }
                            if (hasMoreRegular) {
                                val remaining = allRegular.size - visibleRegular.size
                                item(key = "load_more", contentType = "load_more") {
                                    OutlinedButton(
                                        onClick = { visibleRegularLimit += DEFAULT_VISIBLE_PAGE_SIZE },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    ) {
                                        Text("${strings.serverListLoadMore} ($remaining)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ServerInspectionCard(
    entry: ServerListEntry,
    isExpanded: Boolean,
    isConnected: Boolean,
    endpointEnabled: Boolean,
    speedState: DesktopSpeedTestState,
    isReinspecting: Boolean,
    fallbackFlag: String,
    appLanguage: String,
    strings: AppStrings,
    meshEnabled: Boolean,
    onToggleExpand: () -> Unit,
    onDoubleClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSpeedTest: () -> Unit,
    onToggleVip: () -> Unit,
    onRetest: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit
) {
    val insp = entry.inspection
    val exitFlag = countryCodeToFlagEmoji(entry.effectiveCountryCode)
    val flagText = exitFlag.ifEmpty { fallbackFlag.ifEmpty { "🌐" } }
    val ping = entry.effectivePingMs

    val borderStroke = when {
        entry.isVip -> BorderStroke(1.5.dp, GOLD_COLOR)
        isConnected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isConnected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = if (meshEnabled) 0.85f else 1f)
        },
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (entry.isDead) 0.65f else 1f)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onToggleExpand,
                        onDoubleClick = onDoubleClick
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = flagText, fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
                    if (entry.isVip) {
                        Text(
                            text = "★",
                            color = GOLD_COLOR,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = entry.profile.remarks.ifBlank { entry.profile.server ?: entry.guid },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isConnected) {
                        Text(
                            text = strings.serverListConnectedBadge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    val (pingText, pingColor) = when {
                        entry.isDead -> "✖" to AccentRed
                        ping > 0L -> "${ping}ms" to when {
                            ping < 250L -> AccentMint
                            ping < 600L -> AccentAmber
                            else -> AccentRed
                        }
                        else -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = pingText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = pingColor,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(18.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.profile.configType.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = "• ${entry.subscriptionName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (insp != null && insp.alive) {
                        if (endpointEnabled) {
                            when (insp.endpointPassed) {
                                true -> Text(strings.serverListEndpointOk, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentMint)
                                false -> Text(strings.serverListEndpointFail, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentRed)
                                null -> Unit
                            }
                        } else if (insp.totalServicesCount > 0) {
                            val svcColor = when {
                                insp.okServicesCount == insp.totalServicesCount -> AccentMint
                                insp.okServicesCount > 0 -> AccentAmber
                                else -> AccentRed
                            }
                            Text(
                                text = "${insp.okServicesCount}/${insp.totalServicesCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = svcColor
                            )
                        }
                    }
                }
            }

            if (isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ipText = insp?.exitIp.orEmpty()
                    val cc = entry.effectiveCountryCode
                    val fragSuffix = if (insp != null && insp.fragMode.isNotEmpty()) {
                        " ⚡[FRAG:${insp.fragMode.uppercase()}]"
                    } else ""
                    if (ipText.isNotEmpty() || cc.isNotEmpty() || fragSuffix.isNotEmpty()) {
                        val parts = mutableListOf<String>()
                        if (cc.isNotEmpty()) {
                            val flag = countryCodeToFlagEmoji(cc)
                            val cName = CountryNames.getCountryNameByCode(cc, appLanguage)
                            parts.add("$flag $cName".trim())
                        }
                        if (ipText.isNotEmpty()) parts.add(ipText)
                        Text(
                            text = "Exit IP: ${parts.joinToString(" • ")}$fragSuffix",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (endpointEnabled && insp != null && insp.alive) {
                        val (epText, epColor) = when (insp.endpointPassed) {
                            true -> strings.serverListEndpointOk to AccentMint
                            false -> strings.serverListEndpointFail to AccentRed
                            null -> strings.serverListEndpointUntested to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(text = epText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = epColor)
                    }

                    if (!endpointEnabled && insp != null && insp.services.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (svc in insp.services) {
                                val c = if (svc.success) AccentMint else AccentRed
                                val badgeText = if (svc.success && svc.latencyMs >= 0L) {
                                    "✔ ${svc.name} ${svc.latencyMs}ms"
                                } else if (svc.success) {
                                    "✔ ${svc.name}"
                                } else {
                                    "✖ ${svc.name}"
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = c.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, c.copy(alpha = 0.45f))
                                ) {
                                    Text(
                                        text = badgeText,
                                        color = c,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (entry.isDead && !insp?.error.isNullOrBlank()) {
                        Text(
                            text = insp?.error.orEmpty(),
                            fontSize = 11.sp,
                            color = AccentRed
                        )
                    }

                    when (speedState) {
                        is DesktopSpeedTestState.Idle -> Unit
                        is DesktopSpeedTestState.Testing -> Text(
                            text = strings.serverListSpeedTesting,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is DesktopSpeedTestState.Success -> Text(
                            text = String.format(Locale.US, "%.2f Mbps", speedState.mbps),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentMint
                        )
                        is DesktopSpeedTestState.Failed -> Text(
                            text = strings.serverListSpeedFailed,
                            fontSize = 12.sp,
                            color = AccentRed
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { if (isConnected) onDisconnect() else onConnect() },
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isConnected) {
                                ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = Color.White)
                            } else {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isConnected) strings.serverListBtnDisconnect else strings.serverListBtnConnect,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onSpeedTest,
                            enabled = speedState !is DesktopSpeedTestState.Testing,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (speedState is DesktopSpeedTestState.Testing) strings.serverListSpeedTesting else strings.serverListBtnSpeedTest,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = onToggleVip,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (entry.isVip) strings.serverListBtnRemoveVip else strings.serverListBtnAddVip,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = onRetest,
                            enabled = !isReinspecting,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isReinspecting) strings.serverListRetesting else strings.serverListBtnRetest,
                                fontSize = 12.sp
                            )
                        }

                        IconButton(onClick = onShowQr) {
                            Icon(Icons.Default.QrCode, contentDescription = "QR", modifier = Modifier.size(18.dp))
                        }

                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = strings.delete, tint = AccentRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerFiltersDialog(
    state: DesktopServerListUiState,
    appLanguage: String,
    strings: AppStrings,
    onApply: (ServerListFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(state.filter) { mutableStateOf(state.filter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.serverListBtnFilters, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 420.dp, max = 560.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(strings.serverListSortTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ServerSortOrder.SMART to strings.serverListSortSmart,
                        ServerSortOrder.PING_ASC to strings.serverListSortPing,
                        ServerSortOrder.SERVICES_DESC to strings.serverListSortServices,
                        ServerSortOrder.COUNTRY_ASC to strings.serverListSortCountry,
                        ServerSortOrder.NAME_ASC to strings.serverListSortName
                    ).forEach { (order, label) ->
                        FilterChip(
                            selected = draft.sortOrder == order,
                            onClick = { draft = draft.copy(sortOrder = order) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Text(strings.serverListStatusTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ServerStatusFilter.ALL to strings.serverListStatusAll,
                        ServerStatusFilter.ALIVE_ONLY to strings.serverListStatusAlive,
                        ServerStatusFilter.HIDE_DEAD to strings.serverListStatusHideDead
                    ).forEach { (status, label) ->
                        FilterChip(
                            selected = draft.statusFilter == status,
                            onClick = { draft = draft.copy(statusFilter = status) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                if (state.endpointEnabled) {
                    Text(strings.serverListEndpointFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            EndpointFilterMode.ANY to strings.serverListEndpointAny,
                            EndpointFilterMode.PASSED_ONLY to strings.serverListEndpointPassedOnly,
                            EndpointFilterMode.FAILED_ONLY to strings.serverListEndpointFailedOnly
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = draft.endpointFilter == mode,
                                onClick = { draft = draft.copy(endpointFilter = mode) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                } else if (state.viewResult.availableServices.isNotEmpty()) {
                    Text(strings.serverListServicesFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.viewResult.availableServices.forEach { svc ->
                            val selected = svc.id in draft.requiredServiceIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.requiredServiceIds - svc.id else draft.requiredServiceIds + svc.id
                                    draft = draft.copy(requiredServiceIds = next)
                                },
                                label = { Text("${svc.name} (${svc.aliveServerCount})", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (state.viewResult.availableCountryCounts.isNotEmpty()) {
                    Text(strings.serverListCountriesFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.viewResult.availableCountryCounts.forEach { (cc, count) ->
                            val selected = cc in draft.selectedCountryCodes
                            val label = "${countryCodeToFlagEmoji(cc)} ${CountryNames.getCountryNameByCode(cc, appLanguage)} ($count)".trim()
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.selectedCountryCodes - cc else draft.selectedCountryCodes + cc
                                    draft = draft.copy(selectedCountryCodes = next)
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (state.availableSubscriptions.isNotEmpty()) {
                    Text(strings.serverListSubsFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.availableSubscriptions.forEach { sub ->
                            val selected = sub.id in draft.selectedSubscriptionIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.selectedSubscriptionIds - sub.id else draft.selectedSubscriptionIds + sub.id
                                    draft = draft.copy(selectedSubscriptionIds = next)
                                },
                                label = { Text("${sub.name} (${sub.count})", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (state.availableProtocols.isNotEmpty()) {
                    Text(strings.serverListProtocolsFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.availableProtocols.forEach { proto ->
                            val selected = proto in draft.selectedProtocols
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) draft.selectedProtocols - proto else draft.selectedProtocols + proto
                                    draft = draft.copy(selectedProtocols = next)
                                },
                                label = { Text(proto.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(draft) }) {
                Text(strings.serverListApplyFilters)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onReset) {
                    Text(strings.serverListResetFilters)
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
            }
        }
    )
}

private fun formatScanTimestamp(ms: Long): String {
    val nowCal = Calendar.getInstance()
    val targetCal = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd.MM HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
