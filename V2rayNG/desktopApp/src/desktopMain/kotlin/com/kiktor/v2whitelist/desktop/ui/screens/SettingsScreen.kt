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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.kiktor.v2whitelist.desktop.engine.AppSplitMode
import com.kiktor.v2whitelist.desktop.engine.DesktopTunManager
import com.kiktor.v2whitelist.desktop.engine.TunEnginePreference
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.components.DesktopStorageEncryptionCard
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.i18n.AppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.NetworkBindingRule
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.config.xray.DomainRouteTestResult
import com.kiktor.v2whitelist.core.config.xray.DomainRouteTester
import com.kiktor.v2whitelist.core.config.xray.RouteVerdict
import com.kiktor.v2whitelist.core.config.xray.XrayGeneratorOptions
import com.kiktor.v2whitelist.core.subscription.endpoint.CustomEndpointManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

enum class DesktopSettingsCategory(
    val getTitle: (AppStrings) -> String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val getDesc: (AppStrings) -> String
) {
    SCANNER_CORE(
        getTitle = { it.catScannerCore },
        icon = Icons.Default.Bolt,
        getDesc = { it.catScannerCoreDesc }
    ),
    PROFILE_CHECK(
        getTitle = { it.catProfileCheck },
        icon = Icons.Default.VerifiedUser,
        getDesc = { it.catProfileCheckDesc }
    ),
    FAILOVER(
        getTitle = { it.catFailover },
        icon = Icons.Default.Autorenew,
        getDesc = { it.catFailoverDesc }
    ),
    UPDATES(
        getTitle = { it.catUpdates },
        icon = Icons.Default.CloudDownload,
        getDesc = { it.catUpdatesDesc }
    ),
    ROUTING(
        getTitle = { it.catRouting },
        icon = Icons.Default.AltRoute,
        getDesc = { it.catRoutingDesc }
    ),
    PERSONAL_SERVERS(
        getTitle = { it.catPersonalServers },
        icon = Icons.Default.Dns,
        getDesc = { it.catPersonalServersDesc }
    ),
    VPN_CORE(
        getTitle = { it.catVpnCore },
        icon = Icons.Default.Settings,
        getDesc = { it.catVpnCoreDesc }
    ),
    BACKUP_DATA(
        getTitle = { it.catBackupData },
        icon = Icons.Default.Storage,
        getDesc = { it.catBackupDataDesc }
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    var selectedCategory by remember { mutableStateOf(DesktopSettingsCategory.SCANNER_CORE) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // SmartConnect
    var smartBatchSize by remember { mutableStateOf(state.smartConnectBatchSize.value.toString()) }
    var verifyConcurrencyInput by remember { mutableStateOf(state.verifyConcurrency.value.toString()) }
    var smartPreset by remember { mutableStateOf(state.smartDistributionPreset.value) }

    // Ядро v2w-core
    var useV2w by remember { mutableStateOf(state.useV2WCore.value) }
    var useFallback by remember { mutableStateOf(state.useFallbackScanner.value) }
    var tcpPrecheckInput by remember { mutableStateOf(state.tcpPrecheckConcurrency.value.toString()) }
    var v2wSockets by remember { mutableStateOf(state.v2wCoreSockets.value.toString()) }
    var v2wFullVerify by remember { mutableStateOf(state.v2wFullVerifyMode.value) }
    var xrayPathInput by remember { mutableStateOf(state.xrayBinaryPath.value) }
    var v2wPathInput by remember { mutableStateOf(state.v2wScanPath.value) }

    // Проверка профилей
    var removeInsecure by remember { mutableStateOf(state.removeInsecureProfiles.value) }
    var testBeforeConnect by remember { mutableStateOf(state.testProfileBeforeConnect.value) }
    var speedTestBytes by remember { mutableStateOf(state.speedTestDownloadBytes.value.toString()) }
    var speedTestTimeout by remember { mutableStateOf(state.speedTestTimeoutMs.value.toString()) }
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
    var tunEnginePref by remember { mutableStateOf(state.tunEnginePreference.value) }
    var appSplitEnabled by remember { mutableStateOf(state.appSplitTunnelingEnabled.value) }
    var appSplitMode by remember { mutableStateOf(state.appSplitTunnelingMode.value) }

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

    // Личные сервера
    var personalEnabled by remember { mutableStateOf(state.personalServersEnabled.value) }
    var personalSelectedGuid by remember { mutableStateOf(state.personalSelectedGuid.value) }
    var personalIgnoreExit by remember { mutableStateOf(state.personalIgnoreExitNode.value) }
    var personalAutoConnect by remember { mutableStateOf(state.personalAutoConnectOnStartup.value) }
    var personalFallbackPub by remember { mutableStateOf(state.personalFallbackPublic.value) }
    var personalBlacklistRule by remember { mutableStateOf(state.personalBlacklistRule.value) }
    var personalWhitelistRule by remember { mutableStateOf(state.personalWhitelistRule.value) }
    var newPersonalLink by remember { mutableStateOf("") }

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
    var tunSelfTestUrl by remember { mutableStateOf(state.tunSelfTestUrl.value) }
    var betaUpdates by remember { mutableStateOf(state.receiveBetaUpdates.value) }
    var minimizeToTray by remember { mutableStateOf(state.minimizeToTray.value) }
    var stopServiceOnExit by remember { mutableStateOf(state.stopServiceOnExit.value) }

    var isSavedNotice by remember { mutableStateOf(false) }

    val doSave = {
        val parsedLocalPort = localPort.toIntOrNull()?.coerceIn(1024, 65534) ?: 10808
        val parsedHttpPort = parsedLocalPort + 1
        state.saveSettings(
            subUrl = state.subscriptionUrl.value,
            xrayPath = xrayPathInput,
            v2wPath = v2wPathInput,
            httpP = parsedHttpPort,
            socksP = parsedLocalPort,
            autoSystemProxy = state.autoEnableSystemProxy.value,
            lan = allowLan,
            directDomains = customDirect,
            proxyDomains = customProxy,
            subUserAgent = userAgent,
            subThreads = (subThreads.toIntOrNull() ?: 15).coerceIn(1, 20),
            subAutoUpdate = subAutoUpdate,
            subIntervalMins = subIntervalMins.toIntOrNull() ?: 30,
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
            tcpPrecheck = (tcpPrecheckInput.toIntOrNull() ?: 100).coerceIn(1, 200),
            v2wSockets = (v2wSockets.toIntOrNull() ?: 35).coerceIn(1, 100),
            v2wFullVerify = v2wFullVerify,
            removeInsecure = removeInsecure,
            testBeforeConnect = testBeforeConnect,
            serviceCheck = state.serviceCheckEnabled.value,
            speedTestCheck = state.speedTestOnCheck.value,
            speedTestBytes = (speedTestBytes.toIntOrNull() ?: 2000000).let { if (it < 10000) 2000000 else it },
            speedTestTimeout = (speedTestTimeout.toIntOrNull() ?: 3000).let { if (it < 500) 3000 else it },
            minSpeed = state.minSpeedThresholdMbps.value,
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
            localPortVal = parsedLocalPort,
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
            vipLimit = (vipCacheLimitInput.toIntOrNull() ?: 5).coerceIn(1, 100),
            autoCheckUpdatesVal = autoUpdateCheck,
            verifyConcurrencyVal = (verifyConcurrencyInput.toIntOrNull() ?: 4).coerceIn(1, 10),
            tunEnginePrefVal = tunEnginePref,
            appSplitEnabledVal = appSplitEnabled,
            appSplitModeVal = appSplitMode,
            tunSelfTestUrlVal = tunSelfTestUrl
        )
        state.settingsRepo.runBatch {
            state.setMinimizeToTray(minimizeToTray)
            state.setStopServiceOnExit(stopServiceOnExit)
            state.setAllowLan(allowLan)
            state.personalServersEnabled.value = personalEnabled
            state.settingsRepo.setBoolean("pref_personal_servers_enabled", personalEnabled)
            state.personalIgnoreExitNode.value = personalIgnoreExit
            state.settingsRepo.setBoolean("pref_personal_ignore_exit_node", personalIgnoreExit)
            state.personalAutoConnectOnStartup.value = personalAutoConnect
            state.settingsRepo.setBoolean("pref_personal_auto_connect", personalAutoConnect)
            state.personalFallbackPublic.value = personalFallbackPub
            state.settingsRepo.setBoolean("pref_personal_fallback_public", personalFallbackPub)
            state.personalSelectedGuid.value = personalSelectedGuid
            state.settingsRepo.setString("pref_personal_selected_guid", personalSelectedGuid)
            state.personalBlacklistRule.value = personalBlacklistRule
            state.settingsRepo.setString("pref_personal_blacklist_rule", personalBlacklistRule.name)
            state.personalWhitelistRule.value = personalWhitelistRule
            state.settingsRepo.setString("pref_personal_whitelist_rule", personalWhitelistRule.name)
        }
        isSavedNotice = true
    }

    val syncFromState = {
        personalEnabled = state.personalServersEnabled.value
        personalIgnoreExit = state.personalIgnoreExitNode.value
        personalAutoConnect = state.personalAutoConnectOnStartup.value
        personalFallbackPub = state.personalFallbackPublic.value
        personalSelectedGuid = state.personalSelectedGuid.value
        personalBlacklistRule = state.personalBlacklistRule.value
        personalWhitelistRule = state.personalWhitelistRule.value
        smartBatchSize = state.smartConnectBatchSize.value.toString()
        verifyConcurrencyInput = state.verifyConcurrency.value.toString()
        smartPreset = state.smartDistributionPreset.value
        useV2w = state.useV2WCore.value
        useFallback = state.useFallbackScanner.value
        tcpPrecheckInput = state.tcpPrecheckConcurrency.value.toString()
        v2wSockets = state.v2wCoreSockets.value.toString()
        v2wFullVerify = state.v2wFullVerifyMode.value
        xrayPathInput = state.xrayBinaryPath.value
        v2wPathInput = state.v2wScanPath.value
        removeInsecure = state.removeInsecureProfiles.value
        testBeforeConnect = state.testProfileBeforeConnect.value
        speedTestBytes = state.speedTestDownloadBytes.value.toString()
        speedTestTimeout = state.speedTestTimeoutMs.value.toString()
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
        tunEnginePref = state.tunEnginePreference.value
        appSplitEnabled = state.appSplitTunnelingEnabled.value
        appSplitMode = state.appSplitTunnelingMode.value
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
        stopServiceOnExit = state.stopServiceOnExit.value
        connCheckUrl = state.connectionCheckServiceUrl.value
        tunSelfTestUrl = state.tunSelfTestUrl.value
        betaUpdates = state.receiveBetaUpdates.value
    }

    val doDiscard = {
        syncFromState()
        isSavedNotice = false
    }

    val settingsRev by state.settingsRevision.collectAsState()
    LaunchedEffect(settingsRev) {
        if (settingsRev > 0L) {
            syncFromState()
        }
    }

    LaunchedEffect(isSavedNotice) {
        if (isSavedNotice) {
            kotlinx.coroutines.delay(3500L)
            isSavedNotice = false
        }
    }

    val checkDirty: () -> Boolean = {
        smartBatchSize != state.smartConnectBatchSize.value.toString() ||
        verifyConcurrencyInput != state.verifyConcurrency.value.toString() ||
        smartPreset != state.smartDistributionPreset.value ||
        useV2w != state.useV2WCore.value ||
        useFallback != state.useFallbackScanner.value ||
        tcpPrecheckInput != state.tcpPrecheckConcurrency.value.toString() ||
        v2wSockets != state.v2wCoreSockets.value.toString() ||
        v2wFullVerify != state.v2wFullVerifyMode.value ||
        xrayPathInput != state.xrayBinaryPath.value ||
        v2wPathInput != state.v2wScanPath.value ||
        removeInsecure != state.removeInsecureProfiles.value ||
        testBeforeConnect != state.testProfileBeforeConnect.value ||
        speedTestBytes != state.speedTestDownloadBytes.value.toString() ||
        speedTestTimeout != state.speedTestTimeoutMs.value.toString() ||
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
        tunEnginePref != state.tunEnginePreference.value ||
        appSplitEnabled != state.appSplitTunnelingEnabled.value ||
        appSplitMode != state.appSplitTunnelingMode.value ||
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
        stopServiceOnExit != state.stopServiceOnExit.value ||
        connCheckUrl != state.connectionCheckServiceUrl.value ||
        tunSelfTestUrl != state.tunSelfTestUrl.value ||
        betaUpdates != state.receiveBetaUpdates.value ||
        personalEnabled != state.personalServersEnabled.value ||
        personalIgnoreExit != state.personalIgnoreExitNode.value ||
        personalAutoConnect != state.personalAutoConnectOnStartup.value ||
        personalFallbackPub != state.personalFallbackPublic.value ||
        personalSelectedGuid != state.personalSelectedGuid.value ||
        personalBlacklistRule != state.personalBlacklistRule.value ||
        personalWhitelistRule != state.personalWhitelistRule.value
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
                        text = strings.settings,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = strings.catVpnCoreDesc,
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
                Text(strings.save, fontWeight = FontWeight.Bold)
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
                    Text(strings.saved, color = AccentMint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
        // Горизонтальный переключатель категорий настроек
        val categoryScrollState = rememberScrollState()
        val categoryScope = rememberCoroutineScope()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (categoryScrollState.maxValue > 0) {
            IconButton(
                onClick = { categoryScope.launch { categoryScrollState.animateScrollBy(-300f) } },
                enabled = categoryScrollState.value > 0
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Прокрутить влево",
                    tint = if (categoryScrollState.value > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) {
                        categoryScope.launch { categoryScrollState.scrollBy(dy * 60f) }
                        ev.changes.forEach { it.consume() }
                    }
                }
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
                        val title = category.getTitle(strings).replace(Regex("^[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\x{1F000}-\\x{1FAFF}\\x{2190}-\\x{2BFF}\\u200D\\uFE0F\\s]+"), "").trim()
                        Text(
                            text = if (title.isNotEmpty()) title else category.getTitle(strings),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        if (categoryScrollState.maxValue > 0) {
            IconButton(
                onClick = { categoryScope.launch { categoryScrollState.animateScrollBy(300f) } },
                enabled = categoryScrollState.value < categoryScrollState.maxValue
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Прокрутить вправо",
                    tint = if (categoryScrollState.value < categoryScrollState.maxValue) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = selectedCategory.getDesc(strings),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        when (selectedCategory) {
            DesktopSettingsCategory.SCANNER_CORE -> {
                // 1. Настройка распределения SmartConnect
                SettingsCard(
                    title = strings.cardSmartConnect,
                    icon = Icons.Default.Bolt,
                    description = strings.cardSmartConnectDesc
                ) {
                    OutlinedTextField(
                        value = smartBatchSize,
                        onValueChange = { smartBatchSize = it },
                        label = { Text(strings.smartBatchLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = verifyConcurrencyInput,
                        onValueChange = { verifyConcurrencyInput = it },
                        label = { Text("Одновременные потоки проверки (1–10, по умолч. 4)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(strings.smartPresetLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChip(label = strings.presetEqual, selected = smartPreset == "EQUAL", onClick = { smartPreset = "EQUAL" })
                        SettingsChip(label = strings.presetRandom, selected = smartPreset == "RANDOM", onClick = { smartPreset = "RANDOM" })
                        SettingsChip(label = strings.presetMore, selected = smartPreset == "MORE", onClick = { smartPreset = "MORE" })
                        SettingsChip(label = strings.presetLess, selected = smartPreset == "LESS", onClick = { smartPreset = "LESS" })
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2. Ядро v2w-core
                SettingsCard(
                    title = strings.cardV2wCore,
                    icon = Icons.Default.Memory,
                    description = strings.cardV2wCoreDesc
                ) {
                    SettingSwitchRow(
                        title = strings.v2wEnable,
                        desc = strings.v2wEnableDesc,
                        checked = useV2w,
                        onCheckedChange = { useV2w = it }
                    )
                    SettingSwitchRow(
                        title = strings.v2wFallback,
                        desc = strings.v2wFallbackDesc,
                        checked = useFallback,
                        onCheckedChange = { useFallback = it }
                    )
                    SettingSwitchRow(
                        title = strings.v2wFullVerifyTitle,
                        desc = strings.v2wFullVerifyDesc,
                        checked = v2wFullVerify,
                        onCheckedChange = { v2wFullVerify = it }
                    )
                    OutlinedTextField(
                        value = tcpPrecheckInput,
                        onValueChange = { tcpPrecheckInput = it },
                        label = { Text(strings.tcpPrecheckLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = v2wSockets,
                        onValueChange = { v2wSockets = it },
                        label = { Text(strings.v2wSocketsLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = xrayPathInput,
                        onValueChange = { xrayPathInput = it },
                        label = { Text(strings.xrayPathLabel) },
                        placeholder = { Text(strings.xrayPathLabel) },
                        supportingText = {
                            val activePath = state.xrayBinaryPath.value
                            if (activePath.isNotBlank()) {
                                Text("${strings.cardV2wCore}: $activePath", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(strings.xrayPathLabel)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = v2wPathInput,
                        onValueChange = { v2wPathInput = it },
                        label = { Text(strings.v2wPathLabel) },
                        placeholder = { Text(strings.xrayPathLabel) },
                        supportingText = {
                            val activePath = state.v2wScanPath.value
                            if (activePath.isNotBlank()) {
                                Text("${strings.cardV2wCore}: $activePath", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(strings.xrayPathLabel)
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
                    title = strings.cardProfileCheck,
                    icon = Icons.Default.VerifiedUser,
                    description = strings.cardProfileCheckDesc
                ) {
                    SettingSwitchRow(
                        title = strings.removeInsecure,
                        desc = strings.removeInsecureDesc,
                        checked = removeInsecure,
                        onCheckedChange = { removeInsecure = it }
                    )
                    SettingSwitchRow(
                        title = strings.testBeforeConnect,
                        desc = strings.testBeforeConnectDesc,
                        checked = testBeforeConnect,
                        onCheckedChange = { testBeforeConnect = it }
                    )
                    SettingSwitchRow(
                        title = strings.endpointE2eEnable,
                        desc = strings.endpointE2eEnableDesc,
                        checked = endpointE2eCheck,
                        onCheckedChange = { endpointE2eCheck = it }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Замер скорости
                SettingsCard(
                    title = strings.cardSpeedTest,
                    icon = Icons.Default.Speed,
                    description = strings.cardSpeedTestDesc
                ) {
                    OutlinedTextField(
                        value = speedTestBytes,
                        onValueChange = { speedTestBytes = it },
                        label = { Text(strings.speedTestBytesLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = speedTestTimeout,
                        onValueChange = { speedTestTimeout = it },
                        label = { Text(strings.speedTestTimeoutLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vipCacheLimitInput,
                        onValueChange = { vipCacheLimitInput = it },
                        label = { Text(strings.vipLimitLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.FAILOVER -> {
                // 4. Настройки авто-переключения (Failover)
                SettingsCard(
                    title = strings.cardFailover,
                    icon = Icons.Default.Autorenew,
                    description = strings.cardFailoverDesc
                ) {
                    SettingSwitchRow(
                        title = strings.autoFailoverEnable,
                        desc = strings.autoFailoverEnableDesc,
                        checked = autoFailover,
                        onCheckedChange = { autoFailover = it }
                    )
                    OutlinedTextField(
                        value = heartbeatSec,
                        onValueChange = { heartbeatSec = it },
                        label = { Text(strings.heartbeatLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Smart Failover",
                        desc = strings.smartFailoverEnableDesc,
                        checked = smartFailover,
                        onCheckedChange = { smartFailover = it }
                    )
                    OutlinedTextField(
                        value = degradationKb,
                        onValueChange = { degradationKb = it },
                        label = { Text(strings.failoverDegradationLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stuckSec,
                        onValueChange = { stuckSec = it },
                        label = { Text(strings.failoverStuckLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 5. Проверка статуса сети
                SettingsCard(
                    title = strings.cardNetCheck,
                    icon = Icons.Default.NetworkCheck,
                    description = strings.cardNetCheckDesc
                ) {
                    SettingSwitchRow(
                        title = strings.netCheckEnable,
                        desc = strings.netCheckEnableDesc,
                        checked = netCheckEnabled,
                        onCheckedChange = { netCheckEnabled = it }
                    )
                    OutlinedTextField(
                        value = primaryDomain,
                        onValueChange = { primaryDomain = it },
                        label = { Text(strings.primaryDomainLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fallbackDomain,
                        onValueChange = { fallbackDomain = it },
                        label = { Text(strings.fallbackDomainLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.UPDATES -> {
                // 6. Фоновое обновление подписок
                SettingsCard(
                    title = strings.cardSubUpdate,
                    icon = Icons.Default.Groups,
                    description = strings.cardSubUpdateDesc
                ) {
                    SettingSwitchRow(
                        title = strings.subAutoUpdateEnable,
                        desc = strings.subAutoUpdateEnableDesc,
                        checked = subAutoUpdate,
                        onCheckedChange = { subAutoUpdate = it }
                    )
                    SettingSwitchRow(
                        title = strings.subOnStartEnable,
                        desc = strings.subOnStartEnableDesc,
                        checked = subUpdateOnStart,
                        onCheckedChange = { subUpdateOnStart = it }
                    )
                    OutlinedTextField(
                        value = subIntervalMins,
                        onValueChange = { subIntervalMins = it },
                        label = { Text(strings.subIntervalLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = strings.subOnlyProxyEnable,
                        desc = strings.subOnlyProxyEnableDesc,
                        checked = subOnlyProxy,
                        onCheckedChange = { subOnlyProxy = it }
                    )
                    OutlinedTextField(
                        value = subThreads,
                        onValueChange = { subThreads = it },
                        label = { Text(strings.subThreadsLabel) },
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
                        Text(strings.updateAll)
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = strings.yandexTranslateEnable,
                        desc = strings.yandexTranslateEnableDesc,
                        checked = yandexUpdate,
                        onCheckedChange = { yandexUpdate = it }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Обновления приложения и User-Agent
                SettingsCard(
                    title = strings.cardAppUpdates,
                    icon = Icons.Default.CloudDownload,
                    description = strings.cardAppUpdatesDesc
                ) {
                    SettingSwitchRow(
                        title = strings.autoCheckUpdates,
                        desc = strings.autoCheckUpdatesDesc,
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
                                text = "ProBel v${DesktopAppState.APP_VERSION}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!updateMessage.isNullOrBlank()) {
                                Text(
                                    text = updateMessage!!,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (updateMessage!!.startsWith("Ошибка") || updateMessage!!.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                                Text(strings.statusTesting, fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.btnUpdate, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = strings.betaUpdates,
                        desc = strings.betaUpdatesDesc,
                        checked = betaUpdates,
                        onCheckedChange = { betaUpdates = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text(strings.userAgentLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            DesktopSettingsCategory.ROUTING -> {
                // 7. Сплит-туннелинг и обход
                SettingsCard(
                    title = strings.cardRoutingBypass,
                    icon = Icons.Default.Security,
                    description = strings.cardRoutingBypassDesc
                ) {
                    SettingSwitchRow(
                        title = strings.bypassRuEnable,
                        desc = strings.bypassRuEnableDesc,
                        checked = bypassRu,
                        onCheckedChange = { bypassRu = it }
                    )
                    SettingSwitchRow(
                        title = strings.vpnBypassLan,
                        desc = strings.vpnBypassLanDesc,
                        checked = bypassLan,
                        onCheckedChange = { bypassLan = it }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Раздельное туннелирование по приложениям (TUN + v2w-tun)
                val directAppsCount = state.appSplitDirectApps.collectAsState().value.size
                val proxyAppsCount = state.appSplitProxyApps.collectAsState().value.size
                val effectiveTunDriver = DesktopTunManager.resolveEffectiveTunEngine(tunEnginePref, appSplitEnabled)
                SettingsCard(
                    title = strings.appSplitCardTitle,
                    icon = Icons.Default.Apps,
                    description = strings.appSplitCardDesc
                ) {
                    SettingSwitchRow(
                        title = strings.appSplitEnableTitle,
                        desc = strings.appSplitEnableDesc,
                        checked = appSplitEnabled,
                        onCheckedChange = { enabled ->
                            appSplitEnabled = enabled
                            state.setAppSplitEnabled(enabled)
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.appSplitDefaultPolicyLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsChip(
                            label = strings.appSplitModeBypassLabel,
                            selected = appSplitMode == AppSplitMode.BYPASS,
                            onClick = {
                                appSplitMode = AppSplitMode.BYPASS
                                state.setAppSplitMode(AppSplitMode.BYPASS)
                            }
                        )
                        SettingsChip(
                            label = strings.appSplitModeOnlyProxyLabel,
                            selected = appSplitMode == AppSplitMode.ONLY_PROXY,
                            onClick = {
                                appSplitMode = AppSplitMode.ONLY_PROXY
                                state.setAppSplitMode(AppSplitMode.ONLY_PROXY)
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { state.promptAppSplitDialog() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${strings.appSplitSelectAppsPrefix} (${strings.appSplitBypassTag}: $directAppsCount • ${strings.appSplitVpnTag}: $proxyAppsCount)", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${strings.appSplitActiveDriverPrefix} ${if (effectiveTunDriver == TunEnginePreference.V2W_TUN) strings.appSplitDriverCustomName else "tun2socks"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (appSplitEnabled && tunEnginePref == TunEnginePreference.TUN2SOCKS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strings.appSplitTun2socksWarning,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                SettingsCard(
                    title = strings.cardSplitTunneling,
                    icon = Icons.Default.AltRoute,
                    description = strings.cardSplitTunnelingDesc
                ) {
                    SettingSwitchRow(
                        title = strings.cardSplitTunneling,
                        desc = strings.domainSplitEnableDesc,
                        checked = domainSplit,
                        onCheckedChange = { domainSplit = it }
                    )
                    SettingSwitchRow(
                        title = strings.domainWhitelistMode,
                        desc = strings.domainWhitelistMode,
                        checked = domainWhitelist,
                        onCheckedChange = { domainWhitelist = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customProxy,
                        onValueChange = { customProxy = it },
                        label = { Text(strings.proxyDomainsLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDirect,
                        onValueChange = { customDirect = it },
                        label = { Text("${strings.directDomainsLabel} (домены, IP/CIDR, geoip:)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Быстрые шаблоны маршрутизации (добавляют правила в список выше):",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ROUTING_DOMAIN_PRESETS.forEach { preset ->
                            OutlinedButton(
                                onClick = {
                                    if (preset.target == RoutingPresetTarget.DIRECT) {
                                        customDirect = mergeDomainRules(customDirect, preset.rules)
                                    } else {
                                        customProxy = mergeDomainRules(customProxy, preset.rules)
                                    }
                                    domainSplit = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(preset.label, fontSize = 11.sp)
                            }
                        }
                    }
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
                                text = "${strings.bypassRuEnable}: geoip:ru / geosite:category-ru",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Проверка маршрутизации конкретного домена / IP
                var domainTestQuery by remember { mutableStateOf("") }
                var domainTestResult by remember { mutableStateOf<DomainRouteTestResult?>(null) }
                var domainTestResolvedIps by remember { mutableStateOf<List<String>>(emptyList()) }
                var isTestingDomain by remember { mutableStateOf(false) }

                SettingsCard(
                    title = "Проверка домена: куда пойдёт сайт?",
                    icon = Icons.Default.TravelExplore,
                    description = "Показывает вердикт (Direct / Proxy) и правило, которое сработает при текущих настройках"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = domainTestQuery,
                            onValueChange = { domainTestQuery = it },
                            label = { Text("Домен, URL или IP (например: youtube.com, sber.ru, 192.168.1.1)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val query = domainTestQuery.trim()
                                if (query.isNotBlank() && !isTestingDomain) {
                                    isTestingDomain = true
                                    scope.launch {
                                        val normalized = DomainRouteTester.normalizeInput(query)
                                        val ips = withContext(Dispatchers.IO) {
                                            runCatching {
                                                InetAddress.getAllByName(normalized)
                                                    .mapNotNull { it.hostAddress }
                                                    .distinct()
                                                    .take(4)
                                            }.getOrDefault(emptyList())
                                        }
                                        val opts = XrayGeneratorOptions(
                                            bypassRussianServices = bypassRu,
                                            bypassPrivateIps = bypassLan,
                                            domainWhitelistMode = domainSplit && domainWhitelist,
                                            customProxyDomains = if (domainSplit) customProxy.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() } else emptyList(),
                                            customDirectDomains = if (domainSplit) customDirect.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
                                        )
                                        domainTestResolvedIps = ips
                                        domainTestResult = DomainRouteTester.test(query, opts, ips)
                                        isTestingDomain = false
                                    }
                                }
                            },
                            enabled = domainTestQuery.isNotBlank() && !isTestingDomain,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (isTestingDomain) "..." else "Проверить")
                        }
                    }

                    domainTestResult?.let { res ->
                        Spacer(Modifier.height(10.dp))
                        val isDirect = res.verdict == RouteVerdict.DIRECT
                        val badgeColor = if (isDirect) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = badgeColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = badgeColor) {
                                        Text(
                                            text = if (isDirect) "DIRECT (Напрямую)" else "PROXY (Через VPN)",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        text = res.normalizedHost,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = "Правило: ${res.matchedRule}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (domainTestResolvedIps.isNotEmpty()) {
                                    Text(
                                        text = "DNS: ${domainTestResolvedIps.joinToString(", ")}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                res.note?.let { note ->
                                    Text(
                                        text = note,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Автообновление geoip.dat / geosite.dat
                val geoIntervalDays by state.geoAutoUpdateIntervalDays.collectAsState()
                val geoLastUpdatedMs by state.geoAssetsLastUpdatedMs.collectAsState()
                val isUpdatingGeo by state.isUpdatingGeoAssets.collectAsState()
                val geoMessage by state.geoUpdateMessage.collectAsState()
                val formattedGeoDate = remember(geoLastUpdatedMs) {
                    if (geoLastUpdatedMs <= 0L) {
                        "встроенная база (не обновлялась вручную)"
                    } else {
                        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(geoLastUpdatedMs))
                    }
                }

                SettingsCard(
                    title = "Базы маршрутизации (geoip.dat / geosite.dat)",
                    icon = Icons.Default.Update,
                    description = "Используются для Bypass RU (geoip:ru, geosite:category-ru) и правил доменов"
                ) {
                    Text(
                        text = "Последнее обновление: $formattedGeoDate",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Автообновление по расписанию:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "Выкл", 1 to "1 день", 3 to "3 дня", 7 to "7 дней").forEach { (days, label) ->
                            SettingsChip(
                                label = label,
                                selected = geoIntervalDays == days,
                                onClick = { state.setGeoAutoUpdateIntervalDays(days) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { state.updateGeoAssetsNow(manual = true) },
                            enabled = !isUpdatingGeo,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isUpdatingGeo) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isUpdatingGeo) "Обновление…" else "Обновить geoip / geosite сейчас", fontSize = 12.sp)
                        }
                        geoMessage?.let { msg ->
                            Text(
                                text = msg,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            DesktopSettingsCategory.PERSONAL_SERVERS -> {
                val currentEpEnabled by state.useCustomEndpoint.collectAsState()
                val currentEpUrl by state.customEndpointUrl.collectAsState()
                val assignedEpProfile = remember(currentEpUrl) {
                    if (currentEpUrl.isNotBlank()) UniversalConfigParser.parse(currentEpUrl) else null
                }
                val exitNodeDesc = when {
                    assignedEpProfile != null && currentEpEnabled -> "Конечный узел: ${assignedEpProfile.remarks.ifBlank { "${assignedEpProfile.server}:${assignedEpProfile.serverPort}" }}"
                    assignedEpProfile != null -> "Отключен (назначен: ${assignedEpProfile.remarks.ifBlank { "${assignedEpProfile.server}:${assignedEpProfile.serverPort}" }})"
                    else -> "Конечный узел: не назначен (выберите «Сделать Exit» у доверенного сервера ниже)"
                }

                SettingsCard(
                    title = "Управление доверенными серверами",
                    icon = Icons.Default.Dns,
                    description = "Использование персональных VPS узлов, приватных подписок и автопоиска"
                ) {
                    SettingSwitchRow(
                        title = "Включить доверенные сервера",
                        desc = "Приоритетно использовать доверенные узлы вместо сторонних подписок",
                        checked = personalEnabled,
                        onCheckedChange = { personalEnabled = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Конечный узел (Proxy Chaining)",
                        desc = exitNodeDesc,
                        checked = currentEpEnabled && assignedEpProfile != null,
                        enabled = assignedEpProfile != null,
                        onCheckedChange = { enabled ->
                            state.setExitNodeEnabled(enabled)
                            useCustomEp = state.useCustomEndpoint.value
                            customEpConfig = state.customEndpointUrl.value
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Игнорировать конечный узел при использовании",
                        desc = "Не пропускать доверенный сервер через цепочку конечного узла (рекомендуется)",
                        checked = personalIgnoreExit,
                        onCheckedChange = { personalIgnoreExit = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Автоподключение при старте",
                        desc = "Автоматически подключать доверенный сервер при нажатии подключения",
                        checked = personalAutoConnect,
                        onCheckedChange = { personalAutoConnect = it }
                    )
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Фоллбек на сторонние подписки при неудаче",
                        desc = "Если доверенные серверы недоступны, бесшовно переключаться на сторонние",
                        checked = personalFallbackPub,
                        onCheckedChange = { personalFallbackPub = it }
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = strings.personalRulesHeader,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = strings.personalRulesHeaderDesc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    // Правило для ЧС / YouTube
                    Text(
                        text = strings.personalBlacklistRuleTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = strings.personalBlacklistRuleDesc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsChip(
                            label = strings.personalRuleAuto,
                            selected = personalBlacklistRule == NetworkBindingRule.USE_PERSONAL_AUTO,
                            onClick = { personalBlacklistRule = NetworkBindingRule.USE_PERSONAL_AUTO }
                        )
                        SettingsChip(
                            label = strings.personalRuleSelected,
                            selected = personalBlacklistRule == NetworkBindingRule.USE_PERSONAL_SELECTED,
                            onClick = { personalBlacklistRule = NetworkBindingRule.USE_PERSONAL_SELECTED }
                        )
                        SettingsChip(
                            label = strings.personalRulePublic,
                            selected = personalBlacklistRule == NetworkBindingRule.USE_PUBLIC_SUBS,
                            onClick = { personalBlacklistRule = NetworkBindingRule.USE_PUBLIC_SUBS }
                        )
                        SettingsChip(
                            label = strings.personalRuleDirect,
                            selected = personalBlacklistRule == NetworkBindingRule.DIRECT_NO_VPN,
                            onClick = { personalBlacklistRule = NetworkBindingRule.DIRECT_NO_VPN }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Правило для БС
                    Text(
                        text = strings.personalWhitelistRuleTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = strings.personalWhitelistRuleDesc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsChip(
                            label = strings.personalRulePublic,
                            selected = personalWhitelistRule == NetworkBindingRule.USE_PUBLIC_SUBS,
                            onClick = { personalWhitelistRule = NetworkBindingRule.USE_PUBLIC_SUBS }
                        )
                        SettingsChip(
                            label = strings.personalRuleAuto,
                            selected = personalWhitelistRule == NetworkBindingRule.USE_PERSONAL_AUTO,
                            onClick = { personalWhitelistRule = NetworkBindingRule.USE_PERSONAL_AUTO }
                        )
                        SettingsChip(
                            label = strings.personalRuleSelected,
                            selected = personalWhitelistRule == NetworkBindingRule.USE_PERSONAL_SELECTED,
                            onClick = { personalWhitelistRule = NetworkBindingRule.USE_PERSONAL_SELECTED }
                        )
                        SettingsChip(
                            label = strings.personalRuleDirect,
                            selected = personalWhitelistRule == NetworkBindingRule.DIRECT_NO_VPN,
                            onClick = { personalWhitelistRule = NetworkBindingRule.DIRECT_NO_VPN }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                SettingsCard(
                    title = "Добавить доверенный узел или подписку",
                    icon = Icons.Default.Add,
                    description = "Вставьте ссылку на сервер (vless://, vmess://, ...) или URL подписки (http://, https://)"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPersonalLink,
                            onValueChange = { newPersonalLink = it },
                            label = { Text("Ссылка на конфигурацию или подписку") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                if (newPersonalLink.isNotBlank()) {
                                    val trimmed = newPersonalLink.trim()
                                    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                                        val subId = "${PersonalServersConfig.SUB_ID_PERSONAL_PREFIX}${java.util.UUID.randomUUID().toString().take(8)}"
                                        val newSub = com.kiktor.v2whitelist.core.subscription.model.CustomSubData(
                                            id = subId,
                                            name = "Доверенная подписка",
                                            url = trimmed,
                                            enabled = true
                                        )
                                        state.saveSubscription(newSub)
                                        state.updateAllActiveSubscriptions()
                                    } else {
                                        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
                                        for (line in lines) {
                                            val parsed = UniversalConfigParser.parse(line)
                                            if (parsed != null) {
                                                parsed.subscriptionId = PersonalServersConfig.SUB_ID_PERSONAL_DIRECT
                                                val guid = java.util.UUID.randomUUID().toString()
                                                state.serverRepo.saveServerConfig(guid, parsed)
                                            }
                                        }
                                    }
                                    newPersonalLink = ""
                                }
                            }
                        ) {
                            Text("Добавить")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                val personalServers = state.servers.collectAsState().value.filter {
                    PersonalServersConfig.isPersonalServer(it.second.subscriptionId)
                }
                val personalSubs = state.subscriptions.collectAsState().value.filter {
                    it.id.startsWith(PersonalServersConfig.SUB_ID_PERSONAL_PREFIX)
                }
                val collapsedPersonalSubs = remember { mutableStateListOf<String>() }

                SettingsCard(
                    title = "Список доверенных серверов (${personalServers.size})",
                    icon = Icons.Default.List,
                    description = "Выберите сервер для прямого подключения или оставьте автовыбор"
                ) {
                    // Опция автовыбора
                    val selectAuto = {
                        personalSelectedGuid = ""
                        if (personalBlacklistRule == NetworkBindingRule.USE_PERSONAL_SELECTED) {
                            personalBlacklistRule = NetworkBindingRule.USE_PERSONAL_AUTO
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectAuto() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = personalSelectedGuid.isBlank(),
                            onClick = { selectAuto() }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("⚡ Автовыбор среди доверенных", fontWeight = FontWeight.Bold)
                            Text("Автоматический поиск наилучшего доверенного узла через v2w-core", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (personalServers.isEmpty() && personalSubs.isEmpty()) {
                        Text(
                            text = "Нет добавленных доверенных серверов. Добавьте свой VPS или подписку выше.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        // 1. Группы по доверенным подпискам
                        personalSubs.forEach { sub ->
                            val subServers = personalServers.filter { it.second.subscriptionId == sub.id }
                            val isExpanded = !collapsedPersonalSubs.contains(sub.id)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isExpanded) collapsedPersonalSubs.add(sub.id) else collapsedPersonalSubs.remove(sub.id)
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.name.ifBlank { "Доверенная подписка" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("${subServers.size} серв.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(
                                        onClick = { state.updateSingleSubscription(sub.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Обновить подписку", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { state.deleteSubscription(sub.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить подписку", tint = AccentRed, modifier = Modifier.size(16.dp))
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (isExpanded) {
                                subServers.forEach { (guid, profile) ->
                                    val isSel = personalSelectedGuid == guid
                                    val isExitNode = currentEpEnabled && (
                                        currentEpUrl == UniversalConfigParser.toUri(profile) ||
                                        (assignedEpProfile != null && !profile.server.isNullOrBlank() &&
                                         profile.server.equals(assignedEpProfile.server, ignoreCase = true) &&
                                         profile.serverPort == assignedEpProfile.serverPort)
                                    )
                                    val serverPing = state.pingMap.collectAsState().value[guid]
                                    val onSelectThis = {
                                        personalSelectedGuid = guid
                                        if (personalBlacklistRule == NetworkBindingRule.USE_PERSONAL_AUTO) {
                                            personalBlacklistRule = NetworkBindingRule.USE_PERSONAL_SELECTED
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp)
                                            .clickable { onSelectThis() }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSel,
                                            onClick = { onSelectThis() }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(profile.remarks.ifBlank { "Сервер" }, fontWeight = FontWeight.SemiBold)
                                                if (isExitNode) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            "Конечный узел",
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            fontSize = 10.sp,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${profile.configType.name} • ${profile.server}:${profile.serverPort}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (serverPing != null) {
                                                    Text(
                                                        if (serverPing > 0) " • ${serverPing}ms" else " • Таймаут",
                                                        fontSize = 12.sp,
                                                        color = if (serverPing > 0) AccentMint else AccentRed,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                        IconButton(onClick = { state.pingServer(guid) }) {
                                            Icon(Icons.Default.Speed, contentDescription = "Пинг", modifier = Modifier.size(16.dp))
                                        }
                                        TextButton(
                                            onClick = {
                                                state.setExitNode(if (isExitNode) null else guid)
                                                useCustomEp = state.useCustomEndpoint.value
                                                customEpConfig = state.customEndpointUrl.value
                                            }
                                        ) {
                                            Text(if (isExitNode) "Убрать Exit" else "Сделать Exit")
                                        }
                                        IconButton(onClick = { state.deleteServer(guid) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Отдельные серверы
                        val directServers = personalServers.filter { s -> personalSubs.none { it.id == s.second.subscriptionId } }
                        if (directServers.isNotEmpty()) {
                            if (personalSubs.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Отдельные серверы (${directServers.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            directServers.forEach { (guid, profile) ->
                                val isSel = personalSelectedGuid == guid
                                val isExitNode = currentEpEnabled && (
                                    currentEpUrl == UniversalConfigParser.toUri(profile) ||
                                    (assignedEpProfile != null && !profile.server.isNullOrBlank() &&
                                     profile.server.equals(assignedEpProfile.server, ignoreCase = true) &&
                                     profile.serverPort == assignedEpProfile.serverPort)
                                )
                                val serverPing = state.pingMap.collectAsState().value[guid]
                                val onSelectDirect = {
                                    personalSelectedGuid = guid
                                    if (personalBlacklistRule == NetworkBindingRule.USE_PERSONAL_AUTO) {
                                        personalBlacklistRule = NetworkBindingRule.USE_PERSONAL_SELECTED
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDirect() }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = { onSelectDirect() }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(profile.remarks.ifBlank { "Сервер" }, fontWeight = FontWeight.SemiBold)
                                            if (isExitNode) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "Конечный узел",
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        fontSize = 10.sp,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${profile.configType.name} • ${profile.server}:${profile.serverPort}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (serverPing != null) {
                                                Text(
                                                    if (serverPing > 0) " • ${serverPing}ms" else " • Таймаут",
                                                    fontSize = 12.sp,
                                                    color = if (serverPing > 0) AccentMint else AccentRed,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                    IconButton(onClick = { state.pingServer(guid) }) {
                                        Icon(Icons.Default.Speed, contentDescription = "Пинг", modifier = Modifier.size(16.dp))
                                    }
                                    TextButton(
                                        onClick = {
                                            state.setExitNode(if (isExitNode) null else guid)
                                            useCustomEp = state.useCustomEndpoint.value
                                            customEpConfig = state.customEndpointUrl.value
                                        }
                                    ) {
                                        Text(if (isExitNode) "Убрать Exit" else "Сделать Exit")
                                    }
                                    IconButton(onClick = { state.deleteServer(guid) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DesktopSettingsCategory.VPN_CORE -> {
                if (DesktopTunManager.isWindows) {
                    val isServiceActive by state.isWindowsServiceAvailable.collectAsState()
                    SettingsCard(
                        title = "Служба Windows (v2w-service)",
                        icon = Icons.Default.Security,
                        description = "Фоновая системная служба для работы TUN-режима без запроса прав администратора (UAC)"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isServiceActive) "Статус: Служба активна" else "Статус: Служба не установлена / остановлена",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (isServiceActive)
                                        "TUN работает прозрачно от имени SYSTEM без UAC окон при запуске."
                                    else
                                        "Установите службу один раз, чтобы TUN запускался без прав администратора.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            if (!isServiceActive) {
                                Button(
                                    onClick = { state.installWindowsService() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Установить службу")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { state.uninstallWindowsService() }
                                ) {
                                    Text("Удалить службу")
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SettingSwitchRow(
                            title = strings.stopServiceOnExitTitle,
                            desc = strings.stopServiceOnExitDesc,
                            checked = stopServiceOnExit,
                            onCheckedChange = { stopServiceOnExit = it }
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = tunSelfTestUrl,
                            onValueChange = { tunSelfTestUrl = it },
                            label = { Text(strings.tunSelfTestUrlLabel) },
                            placeholder = { Text("https://ya.ru") },
                            supportingText = { Text(strings.tunSelfTestUrlDesc, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Выбор TUN-драйвера (tun2socks / кастомный v2w-tun)
                SettingsCard(
                    title = strings.tunEngineCardTitle,
                    icon = Icons.Default.SettingsInputComponent,
                    description = strings.tunEngineCardDesc
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsChip(
                            label = strings.tunEngineAuto,
                            selected = tunEnginePref == TunEnginePreference.AUTO,
                            onClick = { tunEnginePref = TunEnginePreference.AUTO }
                        )
                        SettingsChip(
                            label = "tun2socks",
                            selected = tunEnginePref == TunEnginePreference.TUN2SOCKS,
                            onClick = { tunEnginePref = TunEnginePreference.TUN2SOCKS }
                        )
                        SettingsChip(
                            label = strings.tunEngineCustom,
                            selected = tunEnginePref == TunEnginePreference.V2W_TUN,
                            onClick = { tunEnginePref = TunEnginePreference.V2W_TUN }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val effectiveNow = DesktopTunManager.resolveEffectiveTunEngine(tunEnginePref, appSplitEnabled)
                    Text(
                        text = when (effectiveNow) {
                            TunEnginePreference.V2W_TUN -> strings.tunEngineEffectiveCustom
                            else -> strings.tunEngineEffectiveTun2socks
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 9. Базовые настройки VPN
                SettingsCard(
                    title = strings.cardVpnDns,
                    icon = Icons.Default.VpnKey,
                    description = strings.cardVpnDnsDesc
                ) {
                    SettingSwitchRow(title = strings.preferIpv6, desc = strings.preferIpv6Desc, checked = preferIpv6, onCheckedChange = { preferIpv6 = it })
                    SettingSwitchRow(title = strings.localDns, desc = strings.localDnsDesc, checked = localDns, onCheckedChange = { localDns = it })
                    SettingSwitchRow(title = strings.fakeDns, desc = strings.fakeDnsDesc, checked = fakeDns, onCheckedChange = { fakeDns = it })
                    SettingSwitchRow(title = strings.additionalHttpProxy, desc = strings.additionalHttpProxyDesc, checked = addHttpProxy, onCheckedChange = { addHttpProxy = it })
                    OutlinedTextField(value = vpnDns, onValueChange = { vpnDns = it }, label = { Text(strings.vpnDnsLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(title = strings.vpnBypassLan, desc = strings.vpnBypassLanDesc, checked = bypassLan, onCheckedChange = { bypassLan = it })
                    OutlinedTextField(value = vpnAddress, onValueChange = { vpnAddress = it }, label = { Text(strings.vpnAddressLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = vpnMtu, onValueChange = { vpnMtu = it }, label = { Text(strings.vpnMtuLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(14.dp))

                // 10. Базовые настройки ядра
                SettingsCard(
                    title = strings.cardCoreSocks,
                    icon = Icons.Default.DeveloperMode,
                    description = strings.cardCoreSocksDesc
                ) {
                    SettingSwitchRow(title = strings.sniffingEnable, desc = strings.sniffingEnableDesc, checked = sniffing, onCheckedChange = { sniffing = it })
                    SettingSwitchRow(title = strings.routeOnlyEnable, desc = strings.routeOnlyEnableDesc, checked = routeOnly, onCheckedChange = { routeOnly = it })
                    SettingSwitchRow(title = strings.allowLanEnable, desc = strings.allowLanEnableDesc, checked = allowLan, onCheckedChange = { allowLan = it })
                    SettingSwitchRow(title = strings.allowInsecureEnable, desc = strings.allowInsecureEnableDesc, checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { localPort = it },
                        label = { Text(strings.localProxyPortLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val socksPortInt = localPort.toIntOrNull() ?: 10808
                    Text(
                        text = "HTTP: ${socksPortInt + 1}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = localLogin, onValueChange = { localLogin = it }, label = { Text(strings.localProxyLoginLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = localPass, onValueChange = { localPass = it }, label = { Text(strings.localProxyPasswordLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = remoteDns, onValueChange = { remoteDns = it }, label = { Text(strings.remoteDnsLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = internalDns, onValueChange = { internalDns = it }, label = { Text(strings.internalDnsLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = dnsHosts, onValueChange = { dnsHosts = it }, label = { Text(strings.dnsHostsLabel) }, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(14.dp))

                // 11. Мультиплексирование
                SettingsCard(
                    title = strings.cardMux,
                    icon = Icons.Default.Layers,
                    description = strings.cardMuxDesc
                ) {
                    SettingSwitchRow(title = strings.muxEnable, desc = strings.muxEnableDesc, checked = muxEnabled, onCheckedChange = { muxEnabled = it })
                    OutlinedTextField(value = muxTcp, onValueChange = { muxTcp = it }, label = { Text(strings.muxTcpLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = muxXudp, onValueChange = { muxXudp = it }, label = { Text(strings.muxXudpLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("QUIC: Reject", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(14.dp))

                // 12. Фрагментирование
                SettingsCard(
                    title = strings.cardFragmentation,
                    icon = Icons.Default.CallSplit,
                    description = strings.cardFragmentationDesc
                ) {
                    SettingSwitchRow(title = strings.fragmentEnable, desc = strings.fragmentEnableDesc, checked = fragmentEnabled, onCheckedChange = { fragmentEnabled = it })
                    OutlinedTextField(value = fragmentLen, onValueChange = { fragmentLen = it }, label = { Text(strings.fragmentLengthLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = fragmentInt, onValueChange = { fragmentInt = it }, label = { Text(strings.fragmentIntervalLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = fragmentPack, onValueChange = { fragmentPack = it }, label = { Text(strings.fragmentPacketsLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            DesktopSettingsCategory.BACKUP_DATA -> {
                var backupFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

                SettingsCard(
                    title = strings.cardBackup,
                    icon = Icons.Default.Backup,
                    description = strings.cardBackupDesc
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val dialog = java.awt.FileDialog(null as java.awt.Frame?, strings.backupExportBtn, java.awt.FileDialog.SAVE)
                                dialog.file = "probel-backup.json"
                                dialog.isVisible = true
                                val dir = dialog.directory
                                val file = dialog.file
                                if (dir != null && file != null) {
                                    val target = java.io.File(dir, if (file.endsWith(".json", ignoreCase = true)) file else "$file.json")
                                    state.exportBackupToFile(target).fold(
                                        onSuccess = { backupFeedback = true to "Бэкап сохранён: ${target.name}" },
                                        onFailure = { e -> backupFeedback = false to "Ошибка экспорта: ${e.message}" }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.backupExportBtn)
                        }

                        OutlinedButton(
                            onClick = {
                                val dialog = java.awt.FileDialog(null as java.awt.Frame?, strings.backupImportBtn, java.awt.FileDialog.LOAD)
                                dialog.isVisible = true
                                val dir = dialog.directory
                                val file = dialog.file
                                if (dir != null && file != null) {
                                    val target = java.io.File(dir, file)
                                    state.importBackupFromFile(target).fold(
                                        onSuccess = { count -> backupFeedback = true to "Бэкап восстановлен (серверов: $count)" },
                                        onFailure = { e -> backupFeedback = false to "Ошибка импорта: ${e.message}" }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.backupImportBtn)
                        }
                    }
                    backupFeedback?.let { (ok, msg) ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = if (ok) AccentMint else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 12.1. Опциональное шифрование локального хранилища (Argon2id + AES-GCM)
                DesktopStorageEncryptionCard()

                Spacer(Modifier.height(14.dp))

                // 13. Прочее / Системные параметры
                SettingsCard(
                    title = strings.cardSystemTray,
                    icon = Icons.Default.MoreHoriz,
                    description = strings.cardSystemTrayDesc
                ) {
                    OutlinedTextField(value = connCheckUrl, onValueChange = { connCheckUrl = it }, label = { Text(strings.connCheckUrlLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SettingSwitchRow(title = strings.minimizeToTrayEnable, desc = strings.minimizeToTrayEnableDesc, checked = minimizeToTray, onCheckedChange = { minimizeToTray = it })
                }

                Spacer(Modifier.height(14.dp))

                // 14. Сброс всех настроек и данных
                SettingsCard(
                    title = strings.cardReset,
                    icon = Icons.Default.DeleteForever,
                    description = strings.cardResetDesc
                ) {
                    Text(
                        text = strings.cardResetDesc,
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
                        Text(strings.resetAllSettings)
                    }
                }
            }
        }

        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text(strings.resetDialogTitle) },
                text = {
                    Text(strings.resetDialogConfirm)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetConfirmDialog = false
                            state.resetAllSettingsAndData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strings.done)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showResetConfirmDialog = false }) {
                        Text(strings.cancel)
                    }
                }
            )
        }

        if (showEpServerPicker && epSubServers.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showEpServerPicker = false },
                title = { Text(strings.endpointSelectServerTitle) },
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
                        Text(strings.close)
                    }
                }
            )
        }

        com.kiktor.v2whitelist.desktop.ui.components.ServiceAvailabilityDialog(state = state)
        com.kiktor.v2whitelist.desktop.ui.components.AppSplitTunnelingDialog(state = state)

        Spacer(Modifier.height(40.dp))
        }
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
    enabled: Boolean = true,
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
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
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

private enum class RoutingPresetTarget { DIRECT, PROXY }

private data class DomainRoutingPreset(
    val label: String,
    val target: RoutingPresetTarget,
    val rules: List<String>
)

private val ROUTING_DOMAIN_PRESETS = listOf(
    DomainRoutingPreset("+ Сайты РФ напрямую", RoutingPresetTarget.DIRECT, listOf("geosite:category-ru", "domain:ru", "geoip:ru")),
    DomainRoutingPreset("+ Банки и Госуслуги РФ", RoutingPresetTarget.DIRECT, listOf("geosite:category-gov-ru", "gosuslugi.ru", "sberbank.ru", "sber.ru", "tbank.ru", "vtb.ru", "alfabank.ru", "nalog.gov.ru")),
    DomainRoutingPreset("+ Маркетплейсы РФ", RoutingPresetTarget.DIRECT, listOf("ozon.ru", "wildberries.ru", "wb.ru", "market.yandex.ru", "avito.ru", "megamarket.ru")),
    DomainRoutingPreset("+ Steam напрямую", RoutingPresetTarget.DIRECT, listOf("geosite:steam", "steampowered.com", "steamcommunity.com", "steamstatic.com")),
    DomainRoutingPreset("+ YouTube в VPN", RoutingPresetTarget.PROXY, listOf("geosite:youtube", "googlevideo.com", "ytimg.com", "youtu.be")),
    DomainRoutingPreset("+ Telegram в VPN", RoutingPresetTarget.PROXY, listOf("geosite:telegram", "t.me", "telegram.org", "telegram.me", "tdesktop.com")),
    DomainRoutingPreset("+ Discord в VPN", RoutingPresetTarget.PROXY, listOf("geosite:discord", "discord.com", "discord.gg", "discordapp.com", "discord.media")),
    DomainRoutingPreset("+ ChatGPT / AI в VPN", RoutingPresetTarget.PROXY, listOf("geosite:openai", "openai.com", "chatgpt.com", "claude.ai", "anthropic.com", "gemini.google.com"))
)

private fun mergeDomainRules(existingRaw: String, additions: List<String>): String {
    val existing = existingRaw.split(",", "\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toMutableSet()
    existing.addAll(additions)
    return existing.joinToString("\n")
}
