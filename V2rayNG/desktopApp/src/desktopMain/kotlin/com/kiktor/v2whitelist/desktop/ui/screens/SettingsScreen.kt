package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun SettingsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
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
    var subOnlyProxy by remember { mutableStateOf(state.subscriptionUpdateOnlyViaProxy.value) }
    var subThreads by remember { mutableStateOf(state.subscriptionThreads.value.toString()) }
    var yandexUpdate by remember { mutableStateOf(state.yandexTranslateUpdateEnabled.value) }

    // Сплит-туннелинг
    var domainSplit by remember { mutableStateOf(state.domainSplitTunnelingEnabled.value) }
    var customDirect by remember { mutableStateOf(state.customDirectDomains.value) }
    var customProxy by remember { mutableStateOf(state.customProxyDomains.value) }

    // Отдельные настройки
    var bypassRu by remember { mutableStateOf(state.bypassRussianServices.value) }
    var autoUpdateCheck by remember { mutableStateOf(state.autoCheckUpdates.value) }
    var useCustomEp by remember { mutableStateOf(state.useCustomEndpoint.value) }
    var customEpConfig by remember { mutableStateOf(state.customEndpointUrl.value) }
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
                onClick = {
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
                        subOnlyViaProxy = subOnlyProxy,
                        customEndpoint = useCustomEp,
                        customEndpointConfig = customEpConfig,
                        bypassRu = bypassRu,
                        checkUrl = connCheckUrl,
                        smartBatch = smartBatchSize.toIntOrNull() ?: 20,
                        smartPreset = smartPreset,
                        v2wCore = useV2w,
                        fallbackScanner = useFallback,
                        v2wSockets = v2wSockets.toIntOrNull() ?: 35,
                        removeInsecure = removeInsecure,
                        testBeforeConnect = testBeforeConnect,
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
                        vipLimit = vipCacheLimitInput.toIntOrNull() ?: 5
                    )
                    state.setMinimizeToTray(minimizeToTray)
                    state.setAllowLan(allowLan)
                    isSavedNotice = true
                },
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

        // 1. Настройка SmartConnect
        AccordionSection(title = "Настройка SmartConnect (старый сканер)", icon = Icons.Default.Bolt) {
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

        Spacer(Modifier.height(12.dp))

        // 2. Ядро v2w-core
        AccordionSection(title = "Ядро v2w-core", icon = Icons.Default.Memory) {
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
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = v2wPathInput,
                onValueChange = { v2wPathInput = it },
                label = { Text("Путь к бинарнику v2w-scan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        // 3. Проверка профилей
        AccordionSection(title = "Проверка профилей", icon = Icons.Default.VerifiedUser) {
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

        Spacer(Modifier.height(12.dp))

        // 4. Настройки авто-переключения (Failover)
        AccordionSection(title = "Настройки авто-переключения (Failover)", icon = Icons.Default.Autorenew) {
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

        Spacer(Modifier.height(12.dp))

        // 5. Проверка статуса сети
        AccordionSection(title = "Проверка статуса сети", icon = Icons.Default.NetworkCheck) {
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

        Spacer(Modifier.height(12.dp))

        // 6. Группы
        AccordionSection(title = "Группы (фоновое обновление подписок)", icon = Icons.Default.Groups) {
            SettingSwitchRow(
                title = "Автоматически обновлять подписки",
                desc = "Автоматическое обновление подписок в фоновом режиме с указанным интервалом.",
                checked = subAutoUpdate,
                onCheckedChange = { subAutoUpdate = it }
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

        Spacer(Modifier.height(12.dp))

        // 7. Сплит-туннелинг
        AccordionSection(title = "Сплит-туннелинг", icon = Icons.Default.AltRoute) {
            SettingSwitchRow(
                title = "Сплит-туннелинг по доменам",
                desc = "Исключить отдельные домены из туннеля VPN (прямой доступ). Включено по умолчанию.",
                checked = domainSplit,
                onCheckedChange = { domainSplit = it }
            )
            OutlinedTextField(
                value = customDirect,
                onValueChange = { customDirect = it },
                label = { Text("Управление исключениями (прямой доступ в обход VPN, через запятую)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customProxy,
                onValueChange = { customProxy = it },
                label = { Text("Домены, принудительно направляемые в VPN (через запятую)") },
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

        Spacer(Modifier.height(16.dp))

        // 8. Отдельные важные настройки
        Text(
            text = "Специальные параметры",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingSwitchRow(
                    title = "Банки, Госуслуги, Ozon и WB вне VPN",
                    desc = "Сбер, Тинькофф, Госуслуги, Озон, Wildberries, Авито и др. не будут маршрутизироваться через VPN. Включено по умолчанию.",
                    checked = bypassRu,
                    onCheckedChange = { bypassRu = it }
                )
                SettingSwitchRow(
                    title = "Автоматически проверять обновления",
                    desc = "Искать новые версии на GitHub при запуске приложения",
                    checked = autoUpdateCheck,
                    onCheckedChange = { autoUpdateCheck = it }
                )
                SettingSwitchRow(
                    title = "Использовать свой прокси как конечный узел",
                    desc = "Маршрутизировать трафик через ваш личный прокси после подключения к серверу Smart Connect (цепочка прокси)",
                    checked = useCustomEp,
                    onCheckedChange = { useCustomEp = it }
                )
                if (useCustomEp) {
                    OutlinedTextField(
                        value = customEpConfig,
                        onValueChange = { customEpConfig = it },
                        label = { Text("Конфиг конечного узла (vless://, hysteria2://, vmess://, shadowsocks://, trojan://)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text("Пользовательский User-Agent (v2rayNG/1.10.x)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 9. Базовые настройки VPN
        AccordionSection(title = "Базовые настройки VPN", icon = Icons.Default.VpnKey) {
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

        Spacer(Modifier.height(12.dp))

        // 10. Базовые настройки ядра
        AccordionSection(title = "Базовые настройки ядра", icon = Icons.Default.DeveloperMode) {
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

        Spacer(Modifier.height(12.dp))

        // 11. Мультиплексирование
        AccordionSection(title = "Настройки мультиплексирования (Mux)", icon = Icons.Default.Layers) {
            SettingSwitchRow(title = "Использовать мультиплексирование", desc = "Объединение соединений в один туннель (Mux)", checked = muxEnabled, onCheckedChange = { muxEnabled = it })
            OutlinedTextField(value = muxTcp, onValueChange = { muxTcp = it }, label = { Text("TCP-соединения (1-1024, по умолчанию 8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = muxXudp, onValueChange = { muxXudp = it }, label = { Text("XUDP-соединения (1-1024, по умолчанию 8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Обработка QUIC в мультиплексном туннеле: Отклонять (Reject)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))

        // 12. Фрагментирование
        AccordionSection(title = "Настройки фрагментирования (DPI evasion)", icon = Icons.Default.CallSplit) {
            SettingSwitchRow(title = "Использовать фрагментирование", desc = "Разбивка TLS ClientHello пакетов на мелкие части", checked = fragmentEnabled, onCheckedChange = { fragmentEnabled = it })
            OutlinedTextField(value = fragmentLen, onValueChange = { fragmentLen = it }, label = { Text("Длина фрагмента (от - до): 50-100") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = fragmentInt, onValueChange = { fragmentInt = it }, label = { Text("Интервал фрагментов (от - до): 10-20") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = fragmentPack, onValueChange = { fragmentPack = it }, label = { Text("Фрагментирование пакетов: tlshello") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))

        // 13. Прочее
        AccordionSection(title = "Прочее", icon = Icons.Default.MoreHoriz) {
            OutlinedTextField(value = connCheckUrl, onValueChange = { connCheckUrl = it }, label = { Text("Сервис проверки соединения (https://api.ip.sb/geoip)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SettingSwitchRow(title = "Получать Pre-release (Beta) обновления", desc = "Только для разработчиков и тестировщиков! Экспериментальные сборки.", checked = betaUpdates, onCheckedChange = { betaUpdates = it })
            SettingSwitchRow(title = "Сворачивать в системный трей", desc = "При закрытии окна программа продолжает работать в фоне", checked = minimizeToTray, onCheckedChange = { minimizeToTray = it })
        }

        Spacer(Modifier.height(12.dp))

        // 14. Сброс всех настроек и данных
        var showResetConfirmDialog by remember { mutableStateOf(false) }

        AccordionSection(title = "Сброс и очистка приложения", icon = Icons.Default.DeleteForever) {
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

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AccordionSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 12.dp))
                    content()
                }
            }
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
