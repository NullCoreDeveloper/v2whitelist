package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.subscription.endpoint.CustomEndpointManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DesktopSettingsCategory(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String
) {
    SCANNER_CORE(
        title = "⚡ Сканер и ядро",
        icon = Icons.Default.Bolt,
        description = "Настройки распределения серверов SmartConnect и параметры ядра v2w-core"
    ),
    PROFILE_CHECK(
        title = "🛡️ Проверка",
        icon = Icons.Default.VerifiedUser,
        description = "Smart-проверка профилей перед подключением, доступность сервисов и замер скорости"
    ),
    FAILOVER(
        title = "🔄 Failover",
        icon = Icons.Default.Autorenew,
        description = "Авто-переключение серверов при сбоях соединения, Smart Failover и статус сети"
    ),
    UPDATES(
        title = "📦 Обновление",
        icon = Icons.Default.CloudDownload,
        description = "Фоновое обновление подписок и проверка новых версий ПроБел на GitHub"
    ),
    ROUTING(
        title = "🌐 Маршрутизация",
        icon = Icons.Default.AltRoute,
        description = "Обход блокировок РФ, сплит-туннелинг по доменам и конечный узел (цепочка)"
    ),
    VPN_CORE(
        title = "⚙️ VPN и ядро",
        icon = Icons.Default.Settings,
        description = "Сетевые параметры VPN, SOCKS5 прокси, DNS, Mux и фрагментация пакетов"
    ),
    BACKUP_DATA(
        title = "💾 Данные",
        icon = Icons.Default.Storage,
        description = "Системные параметры, сворачивание в трей и сброс настроек"
    )
}

@Composable
fun SettingsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(DesktopSettingsCategory.SCANNER_CORE) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // SmartConnect
    var smartBatchSize by remember { mutableStateOf(state.smartConnectBatchSize.value.toString()) }
    var smartPreset by remember { mutableStateOf(state.smartDistributionPreset.value) }

    // Ядро v2w-core
    var useV2w by remember { mutableStateOf(state.useV2WCore.value) }
    var useFallback by remember { mutableStateOf(state.useFallbackScanner.value) }
    var v2wSockets by remember { mutableStateOf(state.v2wCoreSockets.value.toString()) }
    var xrayPathInput by remember { mutableStateOf(state.xrayBinaryPath.value) }
    var v2wPathInput by remember { mutableStateOf(state.v2wScanPath.value) }

    // Проверка профилей
    var removeInsecure by remember { mutableStateOf(state.removeInsecureProfiles.value) }
    var testBeforeConnect by remember { mutableStateOf(state.testProfileBeforeConnect.value) }
    var serviceCheckEnabled by remember { mutableStateOf(state.serviceCheckEnabled.value) }
    var speedTestOnCheck by remember { mutableStateOf(state.speedTestOnCheck.value) }
    var speedTestBytes by remember { mutableStateOf(state.speedTestDownloadBytes.value.toString()) }
    var speedTestTimeout by remember { mutableStateOf(state.speedTestTimeoutMs.value.toString()) }
    var minSpeedThreshold by remember { mutableStateOf(state.minSpeedThresholdMbps.value.toString()) }
    var vipCacheLimitInput by remember { mutableStateOf(state.vipCacheLimit.value.toString()) }

    // Failover
    var autoFailover by remember { mutableStateOf(state.autoFailover.value) }
    var heartbeatSec by remember { mutableStateOf(state.heartbeatIntervalSeconds.value.toString()) }
    var smartFailover by remember { mutableStateOf(state.smartFailover.value) }
    var degradationKb by remember { mutableStateOf(state.failoverDegradationThresholdKb.value.toString()) }
    var stuckSec by remember { mutableStateOf(state.failoverStuckDurationSeconds.value.toString()) }

    // Проверка статуса сети
    var netCheckEnabled by remember { mutableStateOf(state.networkCheckEnabled.value) }
    var primaryDomain by remember { mutableStateOf(state.networkCheckPrimaryDomain.value) }
    var fallbackDomain by remember { mutableStateOf(state.networkCheckFallbackDomain.value) }

    // Группы
    var subAutoUpdate by remember { mutableStateOf(state.subscriptionAutoUpdate.value) }
    var subIntervalMins by remember { mutableStateOf(state.subscriptionUpdateIntervalMinutes.value.toString()) }
    var subUpdateOnStart by remember { mutableStateOf(state.subscriptionUpdateOnStartup.value) }
    var subOnlyProxy by remember { mutableStateOf(state.subscriptionUpdateOnlyViaProxy.value) }
    var subThreads by remember { mutableStateOf(state.subscriptionThreads.value.toString()) }
    var yandexUpdate by remember { mutableStateOf(state.yandexTranslateUpdateEnabled.value) }

    // Сплит-туннелинг
    var domainSplit by remember { mutableStateOf(state.domainSplitTunnelingEnabled.value) }
    var domainWhitelist by remember { mutableStateOf(state.domainWhitelistMode.value) }
    var customDirect by remember { mutableStateOf(state.customDirectDomains.value) }
    var customProxy by remember { mutableStateOf(state.customProxyDomains.value) }

    // Отдельные настройки
    val scope = rememberCoroutineScope()
    var bypassRu by remember { mutableStateOf(state.bypassRussianServices.value) }
    var autoUpdateCheck by remember { mutableStateOf(state.autoCheckUpdates.value) }
    var useCustomEp by remember { mutableStateOf(state.useCustomEndpoint.value) }
    var customEpConfig by remember { mutableStateOf(state.customEndpointUrl.value) }
    var customEpSubUrl by remember { mutableStateOf(state.customEndpointSubUrl.value) }
    var endpointE2eCheck by remember { mutableStateOf(state.endpointE2eCheckEnabled.value) }
    var epSubServers by remember { mutableStateOf<List<ProfileItem>>(emptyList()) }
    var showEpServerPicker by remember { mutableStateOf(false) }
    var epLoading by remember { mutableStateOf(false) }
    var epStatusMessage by remember { mutableStateOf<String?>(null) }
    var userAgent by remember { mutableStateOf(state.subscriptionUserAgent.value) }

    // Базовые VPN
    var preferIpv6 by remember { mutableStateOf(state.preferIpv6.value) }
    var localDns by remember { mutableStateOf(state.useLocalDns.value) }
    var fakeDns by remember { mutableStateOf(state.useFakeDns.value) }
    var addHttpProxy by remember { mutableStateOf(state.additionalHttpProxy.value) }
    var vpnDns by remember { mutableStateOf(state.vpnDns.value) }
    var bypassLan by remember { mutableStateOf(state.vpnBypassLan.value) }
    var vpnAddress by remember { mutableStateOf(state.vpnInterfaceAddress.value) }
    var vpnMtu by remember { mutableStateOf(state.vpnMtu.value.toString()) }

    // Базовые ядра
    var sniffing by remember { mutableStateOf(state.sniffingEnabled.value) }
    var routeOnly by remember { mutableStateOf(state.routeOnly.value) }
    var allowLan by remember { mutableStateOf(state.allowLan.value) }
    var allowInsecure by remember { mutableStateOf(state.allowInsecure.value) }
    var localPort by remember { mutableStateOf(state.localProxyPort.value.toString()) }
    var localLogin by remember { mutableStateOf(state.localProxyLogin.value) }
    var localPass by remember { mutableStateOf(state.localProxyPassword.value) }
    var remoteDns by remember { mutableStateOf(state.remoteDns.value) }
    var internalDns by remember { mutableStateOf(state.internalDns.value) }
    var dnsHosts by remember { mutableStateOf(state.dnsHosts.value) }

    // Mux
    var muxEnabled by remember { mutableStateOf(state.muxEnabled.value) }
    var muxTcp by remember { mutableStateOf(state.muxTcpConcurrency.value.toString()) }
    var muxXudp by remember { mutableStateOf(state.muxXudpConcurrency.value.toString()) }

    // Фрагментация
    var fragmentEnabled by remember { mutableStateOf(state.fragmentEnabled.value) }
    var fragmentLen by remember { mutableStateOf(state.fragmentLength.value) }
    var fragmentInt by remember { mutableStateOf(state.fragmentInterval.value) }
    var fragmentPack by remember { mutableStateOf(state.fragmentPackets.value) }

    // Прочее
    var connCheckUrl by remember { mutableStateOf(state.connectionCheckServiceUrl.value) }
    var betaUpdates by remember { mutableStateOf(state.receiveBetaUpdates.value) }
    var minimizeToTray by remember { mutableStateOf(state.minimizeToTray.value) }

    var isSavedNotice by remember { mutableStateOf(false) }

    val doSave = {
        state.saveSettings(
            subUrl = state.subscriptionUrl.value,
            xrayPath = xrayPathInput,
            v2wPath = v2wPathInput,
            httpP = state.httpPort.value,
            socksP = state.socksPort.value,
            autoSystemProxy = state.autoEnableSystemProxy.value,
            lan = allowLan,
            directDomains = customDirect,
            proxyDomains = customProxy,
            subUserAgent = userAgent,
            subThreads = subThreads.toIntOrNull() ?: 3,
            subAutoUpdate = subAutoUpdate,
            subIntervalMins = subIntervalMins.toIntOrNull() ?: 60,
            subUpdateOnStart = subUpdateOnStart,
            subOnlyViaProxy = subOnlyProxy,
            customEndpoint = useCustomEp,
            customEndpointConfig = customEpConfig,
            customEndpointSub = customEpSubUrl,
            endpointE2eCheck = endpointE2eCheck,
            bypassRu = bypassRu,
            checkUrl = connCheckUrl,
            smartBatch = smartBatchSize.toIntOrNull() ?: 20,
            smartPreset = smartPreset,
            v2wCore = useV2w,
            fallbackScanner = useFallback,
            v2wSockets = v2wSockets.toIntOrNull() ?: 35,
            removeInsecure = removeInsecure,
            testBeforeConnect = testBeforeConnect,
            serviceCheck = serviceCheckEnabled,
            speedTestCheck = speedTestOnCheck,
            speedTestBytes = speedTestBytes.toIntOrNull() ?: 2000000,
            speedTestTimeout = speedTestTimeout.toIntOrNull() ?: 3000,
            minSpeed = minSpeedThreshold.toDoubleOrNull() ?: 1.0,
            autoFailoverEnabled = autoFailover,
            heartbeatSec = heartbeatSec.toIntOrNull() ?: 30,
            smartFailoverEnabled = smartFailover,
            degradationKb = degradationKb.toIntOrNull() ?: 3,
            stuckSec = stuckSec.toIntOrNull() ?: 3,
            netCheckEnabled = netCheckEnabled,
            primaryDomain = primaryDomain,
            fallbackDomain = fallbackDomain,
            betaUpdates = betaUpdates,
            prefIpv6 = preferIpv6,
            localDnsVal = localDns,
            fakeDnsVal = fakeDns,
            addHttpProxyVal = addHttpProxy,
            vpnDnsVal = vpnDns,
            vpnBypassLanVal = bypassLan,
            vpnAddressVal = vpnAddress,
            vpnMtuVal = vpnMtu.toIntOrNull() ?: 1500,
            sniffingVal = sniffing,
            routeOnlyVal = routeOnly,
            allowInsecureVal = allowInsecure,
            localPortVal = localPort.toIntOrNull() ?: 35255,
            localLoginVal = localLogin,
            localPassVal = localPass,
            remoteDnsVal = remoteDns,
            internalDnsVal = internalDns,
            dnsHostsVal = dnsHosts,
            muxVal = muxEnabled,
            muxTcpVal = muxTcp.toIntOrNull() ?: 8,
            muxXudpVal = muxXudp.toIntOrNull() ?: 8,
            fragmentVal = fragmentEnabled,
            fragmentLenVal = fragmentLen,
            fragmentIntVal = fragmentInt,
            fragmentPackVal = fragmentPack,
            yandexUpdateVal = yandexUpdate,
            domainSplitVal = domainSplit,
            domainWhitelistVal = domainWhitelist,
            vipLimit = vipCacheLimitInput.toIntOrNull() ?: 5
        )
        state.setMinimizeToTray(minimizeToTray)
        state.setAllowLan(allowLan)
        isSavedNotice = true
    }

    val doDiscard = {
        smartBatchSize = state.smartConnectBatchSize.value.toString()
        smartPreset = state.smartDistributionPreset.value
        useV2w = state.useV2WCore.value
        useFallback = state.useFallbackScanner.value
        v2wSockets = state.v2wCoreSockets.value.toString()
        xrayPathInput = state.xrayBinaryPath.value
        v2wPathInput = state.v2wScanPath.value
        removeInsecure = state.removeInsecureProfiles.value
        testBeforeConnect = state.testProfileBeforeConnect.value
        serviceCheckEnabled = state.serviceCheckEnabled.value
        speedTestOnCheck = state.speedTestOnCheck.value
        speedTestBytes = state.speedTestDownloadBytes.value.toString()
        speedTestTimeout = state.speedTestTimeoutMs.value.toString()
        minSpeedThreshold = state.minSpeedThresholdMbps.value.toString()
        vipCacheLimitInput = state.vipCacheLimit.value.toString()
        autoFailover = state.autoFailover.value
        heartbeatSec = state.heartbeatIntervalSeconds.value.toString()
        smartFailover = state.smartFailover.value
        degradationKb = state.failoverDegradationThresholdKb.value.toString()
        stuckSec = state.failoverStuckDurationSeconds.value.toString()
        netCheckEnabled = state.networkCheckEnabled.value
        primaryDomain = state.networkCheckPrimaryDomain.value
        fallbackDomain = state.networkCheckFallbackDomain.value
        subAutoUpdate = state.subscriptionAutoUpdate.value
        subIntervalMins = state.subscriptionUpdateIntervalMinutes.value.toString()
        subUpdateOnStart = state.subscriptionUpdateOnStartup.value
        subOnlyProxy = state.subscriptionUpdateOnlyViaProxy.value
        subThreads = state.subscriptionThreads.value.toString()
        yandexUpdate = state.yandexTranslateUpdateEnabled.value
        domainSplit = state.domainSplitTunnelingEnabled.value
        domainWhitelist = state.domainWhitelistMode.value
        customDirect = state.customDirectDomains.value
        customProxy = state.customProxyDomains.value
        bypassRu = state.bypassRussianServices.value
        autoUpdateCheck = state.autoCheckUpdates.value
        useCustomEp = state.useCustomEndpoint.value
        customEpConfig = state.customEndpointUrl.value
        customEpSubUrl = state.customEndpointSubUrl.value
        endpointE2eCheck = state.endpointE2eCheckEnabled.value
        userAgent = state.subscriptionUserAgent.value
        preferIpv6 = state.preferIpv6.value
        localDns = state.useLocalDns.value
        fakeDns = state.useFakeDns.value
        addHttpProxy = state.additionalHttpProxy.value
        vpnDns = state.vpnDns.value
        bypassLan = state.vpnBypassLan.value
        vpnAddress = state.vpnInterfaceAddress.value
        vpnMtu = state.vpnMtu.value.toString()
        sniffing = state.sniffingEnabled.value
        routeOnly = state.routeOnly.value
        allowLan = state.allowLan.value
        allowInsecure = state.allowInsecure.value
        localPort = state.localProxyPort.value.toString()
        localLogin = state.localProxyLogin.value
        localPass = state.localProxyPassword.value
        remoteDns = state.remoteDns.value
        internalDns = state.internalDns.value
        dnsHosts = state.dnsHosts.value
        muxEnabled = state.muxEnabled.value
        muxTcp = state.muxTcpConcurrency.value.toString()
        muxXudp = state.muxXudpConcurrency.value.toString()
        fragmentEnabled = state.fragmentEnabled.value
        fragmentLen = state.fragmentLength.value
        fragmentInt = state.fragmentInterval.value
        fragmentPack = state.fragmentPackets.value
        minimizeToTray = state.minimizeToTray.value
        connCheckUrl = state.connectionCheckServiceUrl.value
        betaUpdates = state.receiveBetaUpdates.value
        isSavedNotice = false
    }

    val checkDirty: () -> Boolean = {
        smartBatchSize != state.smartConnectBatchSize.value.toString() ||
        smartPreset != state.smartDistributionPreset.value ||
        useV2w != state.useV2WCore.value ||
        useFallback != state.useFallbackScanner.value ||
        v2wSockets != state.v2wCoreSockets.value.toString() ||
        xrayPathInput != state.xrayBinaryPath.value ||
        v2wPathInput != state.v2wScanPath.value ||
        removeInsecure != state.removeInsecureProfiles.value ||
        testBeforeConnect != state.testProfileBeforeConnect.value ||
        serviceCheckEnabled != state.serviceCheckEnabled.value ||
        speedTestOnCheck != state.speedTestOnCheck.value ||
        speedTestBytes != state.speedTestDownloadBytes.value.toString() ||
        speedTestTimeout != state.speedTestTimeoutMs.value.toString() ||
        minSpeedThreshold != state.minSpeedThresholdMbps.value.toString() ||
        vipCacheLimitInput != state.vipCacheLimit.value.toString() ||
        autoFailover != state.autoFailover.value ||
        heartbeatSec != state.heartbeatIntervalSeconds.value.toString() ||
        smartFailover != state.smartFailover.value ||
        degradationKb != state.failoverDegradationThresholdKb.value.toString() ||
        stuckSec != state.failoverStuckDurationSeconds.value.toString() ||
        netCheckEnabled != state.networkCheckEnabled.value ||
        primaryDomain != state.networkCheckPrimaryDomain.value ||
        fallbackDomain != state.networkCheckFallbackDomain.value ||
        subAutoUpdate != state.subscriptionAutoUpdate.value ||
        subIntervalMins != state.subscriptionUpdateIntervalMinutes.value.toString() ||
        subUpdateOnStart != state.subscriptionUpdateOnStartup.value ||
        subOnlyProxy != state.subscriptionUpdateOnlyViaProxy.value ||
        subThreads != state.subscriptionThreads.value.toString() ||
        yandexUpdate != state.yandexTranslateUpdateEnabled.value ||
        domainSplit != state.domainSplitTunnelingEnabled.value ||
        domainWhitelist != state.domainWhitelistMode.value ||
        customDirect != state.customDirectDomains.value ||
        customProxy != state.customProxyDomains.value ||
        bypassRu != state.bypassRussianServices.value ||
        autoUpdateCheck != state.autoCheckUpdates.value ||
        useCustomEp != state.useCustomEndpoint.value ||
        customEpConfig != state.customEndpointUrl.value ||
        customEpSubUrl != state.customEndpointSubUrl.value ||
        endpointE2eCheck != state.endpointE2eCheckEnabled.value ||
        userAgent != state.subscriptionUserAgent.value ||
        preferIpv6 != state.preferIpv6.value ||
        localDns != state.useLocalDns.value ||
        fakeDns != state.useFakeDns.value ||
        addHttpProxy != state.additionalHttpProxy.value ||
        vpnDns != state.vpnDns.value ||
        bypassLan != state.vpnBypassLan.value ||
        vpnAddress != state.vpnInterfaceAddress.value ||
        vpnMtu != state.vpnMtu.value.toString() ||
        sniffing != state.sniffingEnabled.value ||
        routeOnly != state.routeOnly.value ||
        allowLan != state.allowLan.value ||
        allowInsecure != state.allowInsecure.value ||
        localPort != state.localProxyPort.value.toString() ||
        localLogin != state.localProxyLogin.value ||
        localPass != state.localProxyPassword.value ||
        remoteDns != state.remoteDns.value ||
        internalDns != state.internalDns.value ||
        dnsHosts != state.dnsHosts.value ||
        muxEnabled != state.muxEnabled.value ||
        muxTcp != state.muxTcpConcurrency.value.toString() ||
        muxXudp != state.muxXudpConcurrency.value.toString() ||
        fragmentEnabled != state.fragmentEnabled.value ||
        fragmentLen != state.fragmentLength.value ||
        fragmentInt != state.fragmentInterval.value ||
        fragmentPack != state.fragmentPackets.value ||
        minimizeToTray != state.minimizeToTray.value ||
        connCheckUrl != state.connectionCheckServiceUrl.value ||
        betaUpdates != state.receiveBetaUpdates.value
    }

    DisposableEffect(state) {
        state.isSettingsDirty = { checkDirty() }
        state.saveSettingsAction = { doSave() }
        state.discardSettingsAction = { doDiscard() }
        onDispose {
            state.isSettingsDirty = null
            state.saveSettingsAction = null
            state.discardSettingsAction = null
        }
    }

    val scrollState = rememberScrollState()

    val meshEnabled by state.meshGradientEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (meshEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        // Шапка с кнопкой сохранения
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Настройки",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Все параметры сетевого ядра v2w-core, VPN, маршрутизации и безопасности",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = { doSave() },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Сохранить", fontWeight = FontWeight.Bold)
            }
        }

        if (isSavedNotice) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AccentMint.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, AccentMint.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = AccentMint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Настройки успешно применены!", color = AccentMint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Горизонтальный переключатель категорий настроек
        val categoryScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(categoryScrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DesktopSettingsCategory.entries.forEach { category ->
                val isSelected = selectedCategory == category
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedCategory = category }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = category.title,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = selectedCategory.description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        when (selectedCategory) {
            DesktopSettingsCategory.SCANNER_CORE -> {
                // 1. Настройка распределения SmartConnect
                SettingsCard(
                    title = "Распределение серверов SmartConnect",
                    icon = Icons.Default.Bolt,
                    description = "Параметры разбивки пула серверов на пачки"
                ) {
                    OutlinedTextField(
                        value = smartBatchSize,
                        onValueChange = { smartBatchSize = it },
                        label = { Text("Размер пачки серверов (по умолчанию 20)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Пресет распределения по умолчанию:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChip(label = "Поровну", selected = smartPreset == "EQUAL", onClick = { smartPreset = "EQUAL" })
                        SettingsChip(label = "Случайный разброс", selected = smartPreset == "RANDOM", onClick = { smartPreset = "RANDOM" })
                        SettingsChip(label = "Больше серверов больше доля", selected = smartPreset == "MORE", onClick = { smartPreset = "MORE" })
                        SettingsChip(label = "Меньше серверов меньше доля", selected = smartPreset == "LESS", onClick = { smartPreset = "LESS" })
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2. Ядро v2w-core
                SettingsCard(
                    title = "Ядро v2w-core",
                    icon = Icons.Default.Memory,
                    description = "Высокоскоростное нативное ядро сканирования и пути к бинарным файлам"
                ) {
                    SettingSwitchRow(
                        title = "Использовать v2w-core",
                        desc = "Быстрое нативное сканирование и подключение (включено по умолчанию)",
                        checked = useV2w,
                        onCheckedChange = { useV2w = it }
                    )
                    SettingSwitchRow(
                        title = "Резервный классический сканер",
                        desc = "Использовать Kotlin fallback сканер при сбое бинарного ядра",
                        checked = useFallback,
                        onCheckedChange = { useFallback = it }
                    )
                    OutlinedTextField(
                        value = v2wSockets,
                        onValueChange = { v2wSockets = it },
                        label = { Text("Количество сокетов (по умолчанию 35)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = xrayPathInput,
                        onValueChange = { xrayPathInput = it },
                        label = { Text("Путь к исполняемому файлу Xray") },
                        placeholder = { Text("Оставьте пустым для встроенного ядра (автопоиск)") },
                        supportingText = {
                            val activePath = state.xrayBinaryPath.value
                            if (activePath.isNotBlank()) {
                                Text("Активное ядро: $activePath", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Оставьте пустым для встроенного ядра (автопоиск)")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = v2wPathInput,
                        onValueChange = { v2wPathInput = it },
                        label = { Text("Путь к ядру сканера (v2w-scan / v2w-core)") },
                        placeholder = { Text("Оставьте пустым для встроенного ядра (автопоиск)") },
                        supportingText = {
                            val activePath = state.v2wScanPath.value
                            if (activePath.isNotBlank()) {
                                Text("Активное ядро: $activePath", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("Оставьте пустым для встроенного ядра (автопоиск)")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.PROFILE_CHECK -> {
                // 3. Проверка профилей
                SettingsCard(
                    title = "Проверка профилей перед подключением",
                    icon = Icons.Default.VerifiedUser,
                    description = "Фильтрация небезопасных серверов и валидация доступности"
                ) {
                    SettingSwitchRow(
                        title = "Удалять небезопасные профили",
                        desc = "Не пропускать профили без TLS, Reality или allowInsecure",
                        checked = removeInsecure,
                        onCheckedChange = { removeInsecure = it }
                    )
                    SettingSwitchRow(
                        title = "Проверка профиля перед подключением",
                        desc = "Обеспечивает 100% стабильность. Без нее вы можете попасть на мертвый сервер.",
                        checked = testBeforeConnect,
                        onCheckedChange = { testBeforeConnect = it }
                    )
                    SettingSwitchRow(
                        title = "e2e проверка конечного узла",
                        desc = "Проверять сквозное HTTP 204 соединение через цепочку до конечного узла перед замером скорости",
                        checked = endpointE2eCheck,
                        onCheckedChange = { endpointE2eCheck = it }
                    )
                    SettingSwitchRow(
                        title = "Проверять доступность сервисов",
                        desc = "Проверять доступность выбранных сервисов (Google, YouTube, Telegram, WhatsApp, Instagram и др.) вместо теста 204. Игнорируется при использовании конечного узла.",
                        checked = serviceCheckEnabled,
                        onCheckedChange = { serviceCheckEnabled = it }
                    )
                    val currentServices by state.servicesList.collectAsState()
                    val enabledServicesCount = currentServices.count { it.enabled }
                    OutlinedButton(
                        onClick = { state.promptServiceCheckDialog() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Список проверяемых сервисов ($enabledServicesCount/${currentServices.size})", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Замер скорости
                SettingsCard(
                    title = "Замер скорости соединения (Speed Test)",
                    icon = Icons.Default.Speed,
                    description = "Проверка реальной пропускной способности узла"
                ) {
                    SettingSwitchRow(
                        title = "Тест скорости при проверке профиля",
                        desc = "Загружает небольшой объем данных через сервер для проверки реальной пропускной способности.",
                        checked = speedTestOnCheck,
                        onCheckedChange = { speedTestOnCheck = it }
                    )
                    OutlinedTextField(
                        value = speedTestBytes,
                        onValueChange = { speedTestBytes = it },
                        label = { Text("Объем загрузки (байт): 2000000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = speedTestTimeout,
                        onValueChange = { speedTestTimeout = it },
                        label = { Text("Таймаут замера скорости (мс): 3000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minSpeedThreshold,
                        onValueChange = { minSpeedThreshold = it },
                        label = { Text("Минимальный порог скорости (Мбит/с): 1.0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vipCacheLimitInput,
                        onValueChange = { vipCacheLimitInput = it },
                        label = { Text("Лимит VIP-кэша проверенных серверов: 5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.FAILOVER -> {
                // 4. Настройки авто-переключения (Failover)
                SettingsCard(
                    title = "Авто-переключение (Failover)",
                    icon = Icons.Default.Autorenew,
                    description = "Автоматический переход на следующий рабочий сервер при деградации соединения"
                ) {
                    SettingSwitchRow(
                        title = "Авто-переключение (Failover)",
                        desc = "Автоматически переключаться на следующий сервер, если текущий не отвечает",
                        checked = autoFailover,
                        onCheckedChange = { autoFailover = it }
                    )
                    OutlinedTextField(
                        value = heartbeatSec,
                        onValueChange = { heartbeatSec = it },
                        label = { Text("Интервал проверки Heartbeat (секунд): 30") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Smart Failover",
                        desc = "Быстрое обнаружение затыков по трафику и арбитраж сети (ya.ru vs google.com) без долгих таймаутов",
                        checked = smartFailover,
                        onCheckedChange = { smartFailover = it }
                    )
                    OutlinedTextField(
                        value = degradationKb,
                        onValueChange = { degradationKb = it },
                        label = { Text("Порог деградации трафика (КБ/с): 3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stuckSec,
                        onValueChange = { stuckSec = it },
                        label = { Text("Время фиксации затыка (секунд): 3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 5. Проверка статуса сети
                SettingsCard(
                    title = "Проверка статуса сети",
                    icon = Icons.Default.NetworkCheck,
                    description = "Домены арбитража сетевого статуса перед переключением"
                ) {
                    SettingSwitchRow(
                        title = "Проверка статуса сети",
                        desc = "Проверять наличие сети перед подключением и для авто-переключения",
                        checked = netCheckEnabled,
                        onCheckedChange = { netCheckEnabled = it }
                    )
                    OutlinedTextField(
                        value = primaryDomain,
                        onValueChange = { primaryDomain = it },
                        label = { Text("Основной домен для проверки (google.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fallbackDomain,
                        onValueChange = { fallbackDomain = it },
                        label = { Text("Резервный домен для проверки (ya.ru)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.UPDATES -> {
                // 6. Фоновое обновление подписок
                SettingsCard(
                    title = "Фоновое обновление подписок",
                    icon = Icons.Default.Groups,
                    description = "Автоматическая загрузка новых профилей и серверов"
                ) {
                    SettingSwitchRow(
                        title = "Автоматически обновлять подписки",
                        desc = "Автоматическое обновление подписок в фоновом режиме с указанным интервалом.",
                        checked = subAutoUpdate,
                        onCheckedChange = { subAutoUpdate = it }
                    )
                    SettingSwitchRow(
                        title = "Обновлять подписки при запуске",
                        desc = "Автоматически проверять и обновлять подписки при старте приложения (если база серверов пуста или устарела).",
                        checked = subUpdateOnStart,
                        onCheckedChange = { subUpdateOnStart = it }
                    )
                    OutlinedTextField(
                        value = subIntervalMins,
                        onValueChange = { subIntervalMins = it },
                        label = { Text("Интервал автообновления (минут, не менее 15): 60") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Обновлять подписки только через VPN",
                        desc = "Если включено, подписки будут обновляться только при наличии активного VPN-соединения, чтобы избежать утечек IP-адреса.",
                        checked = subOnlyProxy,
                        onCheckedChange = { subOnlyProxy = it }
                    )
                    OutlinedTextField(
                        value = subThreads,
                        onValueChange = { subThreads = it },
                        label = { Text("Параллельное обновление подписок (потоков): 3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { state.updateAllActiveSubscriptions() },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(Icons.Default.CloudSync, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Проверить обновление сейчас (вручную)")
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Обновление подписок через Яндекс Переводчик",
                        desc = "При блокировках (белые списки) и отключенном VPN обновлять подписки через прокси Яндекс Переводчика",
                        checked = yandexUpdate,
                        onCheckedChange = { yandexUpdate = it }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Обновления приложения и User-Agent
                SettingsCard(
                    title = "Обновления приложения и User-Agent",
                    icon = Icons.Default.CloudDownload,
                    description = "Проверка новых версий приложения ПроБел на GitHub"
                ) {
                    SettingSwitchRow(
                        title = "Автоматически проверять обновления",
                        desc = "Искать новые версии на GitHub при запуске приложения",
                        checked = autoUpdateCheck,
                        onCheckedChange = { autoUpdateCheck = it }
                    )

                    val isCheckingUpdate by state.isCheckingAppUpdate.collectAsState()
                    val updateMessage by state.appUpdateCheckMessage.collectAsState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Версия приложения: v${DesktopAppState.APP_VERSION}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!updateMessage.isNullOrBlank()) {
                                Text(
                                    text = updateMessage!!,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (updateMessage!!.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { state.checkAppUpdate(manual = true) },
                            enabled = !isCheckingUpdate,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Проверка...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Проверить сейчас", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Получать Pre-release (Beta) обновления",
                        desc = "Только для разработчиков и тестировщиков! Экспериментальные сборки.",
                        checked = betaUpdates,
                        onCheckedChange = { betaUpdates = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text("Пользовательский User-Agent (v2rayNG/1.10.x)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.ROUTING -> {
                // 7. Сплит-туннелинг и обход
                SettingsCard(
                    title = "Обход российских сервисов",
                    icon = Icons.Default.Security,
                    description = "Исключение критических сервисов РФ из туннеля"
                ) {
                    SettingSwitchRow(
                        title = "Банки, Госуслуги, Ozon и WB вне VPN",
                        desc = "Сбер, Тинькофф, Госуслуги, Озон, Wildberries, Авито и др. не будут маршрутизироваться через VPN. Включено по умолчанию.",
                        checked = bypassRu,
                        onCheckedChange = { bypassRu = it }
                    )
                }

                Spacer(Modifier.height(14.dp))

                SettingsCard(
                    title = "Сплит-туннелинг по доменам",
                    icon = Icons.Default.AltRoute,
                    description = "Маршрутизация трафика на основе доменных имен"
                ) {
                    SettingSwitchRow(
                        title = "Сплит-туннелинг по доменам",
                        desc = "Раздельная маршрутизация доменов (в обход или через VPN). Включено по умолчанию.",
                        checked = domainSplit,
                        onCheckedChange = { domainSplit = it }
                    )
                    SettingSwitchRow(
                        title = "Только указанные домены через VPN",
                        desc = "Режим белого списка: через VPN направляются только домены из списка «Домены через VPN», весь остальной трафик идет напрямую в обход VPN",
                        checked = domainWhitelist,
                        onCheckedChange = { domainWhitelist = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customProxy,
                        onValueChange = { customProxy = it },
                        label = { Text("Домены через VPN (направляются в VPN, через запятую или перенос строки)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDirect,
                        onValueChange = { customDirect = it },
                        label = { Text("Домены в обход VPN (прямой доступ в обход VPN, через запятую или перенос строки)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Geo файлы: настроены по умолчанию (geoip:ru и geosite:category-ru напрямую, остальное через VPN)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Конечный узел
                SettingsCard(
                    title = "Конечный узел (Proxy Chaining)",
                    icon = Icons.Default.Share,
                    description = "Каскадное проксирование через второй сервер"
                ) {
                    SettingSwitchRow(
                        title = "Использовать свой прокси как конечный узел",
                        desc = "Маршрутизировать трафик через ваш личный прокси после подключения к серверу Smart Connect (цепочка)",
                        checked = useCustomEp,
                        onCheckedChange = { useCustomEp = it }
                    )
                    if (useCustomEp) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customEpSubUrl,
                            onValueChange = { customEpSubUrl = it },
                            label = { Text("URL подписки конечного узла") },
                            placeholder = { Text("https://example.com/sub/...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (customEpSubUrl.isNotBlank()) {
                                        epLoading = true
                                        epStatusMessage = "Загрузка подписки..."
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                CustomEndpointManager.fetchServersFromSubscription(customEpSubUrl)
                                            }
                                            epLoading = false
                                            res.fold(
                                                onSuccess = { servers ->
                                                    if (servers.isEmpty()) {
                                                        epStatusMessage = "В подписке не найдено серверов"
                                                    } else {
                                                        epSubServers = servers
                                                        showEpServerPicker = true
                                                        epStatusMessage = null
                                                    }
                                                },
                                                onFailure = { err ->
                                                    epStatusMessage = "Ошибка: ${err.message}"
                                                }
                                            )
                                        }
                                    } else {
                                        epStatusMessage = "Сначала укажите URL подписки"
                                    }
                                },
                                enabled = !epLoading
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (epLoading) "Загрузка…" else "Выбрать из подписки", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val allServers = state.servers.value.map { it.second }
                                    if (allServers.isNotEmpty()) {
                                        epSubServers = allServers
                                        showEpServerPicker = true
                                        epStatusMessage = null
                                    } else {
                                        epStatusMessage = "Список серверов приложения пуст"
                                    }
                                }
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Из серверов приложения", fontSize = 12.sp)
                            }
                        }

                        if (epStatusMessage != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = epStatusMessage!!,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customEpConfig,
                            onValueChange = { customEpConfig = it },
                            label = { Text("Конфиг конечного узла (vless://, vmess://, shadowsocks://, trojan://)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            DesktopSettingsCategory.VPN_CORE -> {
                // 9. Базовые настройки VPN
                SettingsCard(
                    title = "Базовые настройки VPN",
                    icon = Icons.Default.VpnKey,
                    description = "Параметры виртуального сетевого адаптера"
                ) {
                    SettingSwitchRow(title = "Предпочитать IPv6", desc = "Использовать маршрутизацию IPv6-адресов", checked = preferIpv6, onCheckedChange = { preferIpv6 = it })
                    SettingSwitchRow(title = "Использовать локальную DNS", desc = "Обслуживание выполняется DNS-модулем ядра", checked = localDns, onCheckedChange = { localDns = it })
                    SettingSwitchRow(title = "Использовать поддельную DNS (FakeDNS)", desc = "Локальная DNS возвращает поддельные IP-адреса", checked = fakeDns, onCheckedChange = { fakeDns = it })
                    SettingSwitchRow(title = "Дополнительный HTTP-прокси", desc = "HTTP-прокси будет использоваться напрямую", checked = addHttpProxy, onCheckedChange = { addHttpProxy = it })
                    OutlinedTextField(value = vpnDns, onValueChange = { vpnDns = it }, label = { Text("VPN DNS (1.1.1.1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(title = "VPN обходит LAN", desc = "Локальная сеть напрямую в обход VPN адаптера", checked = bypassLan, onCheckedChange = { bypassLan = it })
                    OutlinedTextField(value = vpnAddress, onValueChange = { vpnAddress = it }, label = { Text("Адрес интерфейса VPN (10.10.14.x)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = vpnMtu, onValueChange = { vpnMtu = it }, label = { Text("VPN MTU (1500)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(14.dp))

                // 10. Базовые настройки ядра
                SettingsCard(
                    title = "Базовые настройки ядра и прокси",
                    icon = Icons.Default.DeveloperMode,
                    description = "Локальные порты, авторизация и системный DNS"
                ) {
                    SettingSwitchRow(title = "Анализировать пакеты (Sniffing)", desc = "Пытаться определять доменные имена в пакетах", checked = sniffing, onCheckedChange = { sniffing = it })
                    SettingSwitchRow(title = "Домен только для маршрутизации", desc = "Использовать доменное имя только для маршрутизации", checked = routeOnly, onCheckedChange = { routeOnly = it })
                    SettingSwitchRow(title = "Разрешать соединения из LAN", desc = "Другие устройства в домашней сети могут использовать ваш прокси", checked = allowLan, onCheckedChange = { allowLan = it })
                    SettingSwitchRow(title = "Разрешать небезопасные соединения (Insecure)", desc = "Отключить строгую проверку TLS сертификатов", checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { localPort = it },
                        label = { Text("Порт SOCKS5 прокси (по умолчанию 10808)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val socksPortInt = localPort.toIntOrNull() ?: 10808
                    Text(
                        text = "HTTP порт прокси: ${socksPortInt + 1} (автоматически SOCKS5 + 1)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = localLogin, onValueChange = { localLogin = it }, label = { Text("Логин локального прокси") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = localPass, onValueChange = { localPass = it }, label = { Text("Пароль локального прокси") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = remoteDns, onValueChange = { remoteDns = it }, label = { Text("Удалённая DNS (1.1.1.1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = internalDns, onValueChange = { internalDns = it }, label = { Text("Внутренняя DNS (223.5.5.5)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = dnsHosts, onValueChange = { dnsHosts = it }, label = { Text("Узлы DNS (формат: домен:адрес,...)") }, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(14.dp))

                // 11. Мультиплексирование
                SettingsCard(
                    title = "Мультиплексирование (Mux)",
                    icon = Icons.Default.Layers,
                    description = "Объединение множества соединений в один туннель"
                ) {
                    SettingSwitchRow(title = "Использовать мультиплексирование", desc = "Объединение соединений в один туннель (Mux)", checked = muxEnabled, onCheckedChange = { muxEnabled = it })
                    OutlinedTextField(value = muxTcp, onValueChange = { muxTcp = it }, label = { Text("TCP-соединения (1-1024, по умолчанию 8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = muxXudp, onValueChange = { muxXudp = it }, label = { Text("XUDP-соединения (1-1024, по умолчанию 8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Обработка QUIC в мультиплексном туннеле: Отклонять (Reject)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(14.dp))

                // 12. Фрагментирование
                SettingsCard(
                    title = "Фрагментирование пакетов (DPI evasion)",
                    icon = Icons.Default.CallSplit,
                    description = "Разбивка TLS пакетов для обхода глубокого анализа пакетов"
                ) {
                    SettingSwitchRow(title = "Использовать фрагментирование", desc = "Разбивка TLS ClientHello пакетов на мелкие части", checked = fragmentEnabled, onCheckedChange = { fragmentEnabled = it })
                    OutlinedTextField(value = fragmentLen, onValueChange = { fragmentLen = it }, label = { Text("Длина фрагмента (от - до): 50-100") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = fragmentInt, onValueChange = { fragmentInt = it }, label = { Text("Интервал фрагментов (от - до): 10-20") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = fragmentPack, onValueChange = { fragmentPack = it }, label = { Text("Фрагментирование пакетов: tlshello") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            DesktopSettingsCategory.BACKUP_DATA -> {
                // 13. Прочее / Системные параметры
                SettingsCard(
                    title = "Системные параметры",
                    icon = Icons.Default.MoreHoriz,
                    description = "Поведение окна и фоновые проверки"
                ) {
                    OutlinedTextField(value = connCheckUrl, onValueChange = { connCheckUrl = it }, label = { Text("Сервис проверки соединения (https://api.ip.sb/geoip)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(title = "Сворачивать в системный трей", desc = "При закрытии окна программа продолжает работать в фоне", checked = minimizeToTray, onCheckedChange = { minimizeToTray = it })
                }

                Spacer(Modifier.height(14.dp))

                // 14. Сброс всех настроек и данных
                SettingsCard(
                    title = "Сброс и очистка приложения",
                    icon = Icons.Default.DeleteForever,
                    description = "Возврат всех настроек и базы серверов к заводскому состоянию"
                ) {
                    Text(
                        text = "Сбросить все настройки, подписки, серверы и VIP-кэш к начальному заводскому состоянию.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { showResetConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сбросить все настройки и серверы")
                    }
                }
            }
        }

        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text("Сбросить все данные?") },
                text = {
                    Text("Это действие остановит ядро, очистит все добавленные серверы, подписки, историю VIP-кэша и вернет все настройки приложения к значениям по умолчанию. Продолжить?")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetConfirmDialog = false
                            state.resetAllSettingsAndData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Да, сбросить всё")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showResetConfirmDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        if (showEpServerPicker && epSubServers.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showEpServerPicker = false },
                title = { Text("Выберите конечный узел") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 350.dp).fillMaxWidth()) {
                        items(epSubServers) { server ->
                            val name = CustomEndpointManager.getDisplayName(server)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = CustomEndpointManager.profileToUri(server)
                                        if (uri != null) {
                                            customEpConfig = uri
                                        }
                                        showEpServerPicker = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 14.sp)
                            }
                            HorizontalDivider()
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEpServerPicker = false }) {
                        Text("Закрыть")
                    }
                }
            )
        }

        com.kiktor.v2whitelist.desktop.ui.components.ServiceAvailabilityDialog(state = state)

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
