package com.kiktor.v2whitelist.desktop.ui.i18n
import androidx.compose.runtime.compositionLocalOf
interface AppStrings {
    val appName: String
    val appTitle: String
    val menu: String
    val subscriptions: String
    val geekMode: String
    val appearance: String
    val settings: String
    val exit: String
    val save: String
    val saved: String
    val cancel: String
    val done: String
    val delete: String
    val edit: String
    val retry: String
    val copy: String
    val clear: String
    val close: String
    val source: String
    val status: String
    val protocol: String
    val address: String
    val unknown: String
    val all: String
    val statusConnected: String
    val statusTesting: String
    val statusError: String
    val statusDisconnected: String
    val statusTestingServers: String
    val manualSelectionPrefix: String
    val tunnelActive: String
    val pressStartToConnect: String
    val btnStart: String
    val btnStop: String
    val btnRetry: String
    val btnCancel: String
    val btnSwitchServer: String
    val btnSwitchingServer: String
    val routingModeTitle: String
    val routingModeTun: String
    val routingModeProxy: String
    val routingModeTunDesc: String
    val routingModeProxyDesc: String
    val tunEnabled: String
    val proxyEnabled: String
    val scenarioManual: String
    val scenarioAuto: String
    val scenarioAutoTitle: String
    val scenarioAutoSubtitle: String
    val scenarioWhitelistTitle: String
    val scenarioWhitelistSubtitle: String
    val scenarioBlacklistTitle: String
    val scenarioBlacklistSubtitle: String
    val scenarioYoutubeTitle: String
    val scenarioYoutubeSubtitle: String
    val showServerList: String
    val hideServerList: String
    val searchNodesPlaceholder: String
    val noServersFoundCriteria: String
    val updateAvailableTitle: String
    val updateAvailableSubtitle: String
    val btnUpdate: String
    val btnDismiss: String
    val noSubsText: String
    val subFailureBanner: String
    val qrShowCode: String
    val noActiveServer: String
    val pingAsc: String
    val pingTest: String
    val ping: String
    val findVip: String
    val pingVip: String
    val dpiBypass: String
    val copyLogs: String
    val addSub: String
    val updateAll: String
    val editSub: String
    val deleteSub: String
    val theme: String
    val language: String
    val turnOnVpn: String
    val turnOffVpn: String
    val systemProxyOs: String
    val quickSwitch: String
    val routingModeCardTitle: String
    val serversNotFound: String
    val searchServers: String
    val serverList: String
    val locationsFilter: String
    val locationsFilterExclude: String
    val locationsFilterWhitelist: String
    val locationsFilterDisabled: String
    val proxyStandaloneTitle: String
    val proxyStandaloneDescLan: String
    val proxyStandaloneDescNoRoot: String
    val proxySystemProxyOn: String
    val proxySystemProxyOff: String
    val proxyQuickExportTitle: String
    val proxyTgLinkCopied: String
    val proxyTerminalCopied: String
    val proxyPsCopied: String
    val locFilterTitle: String
    val locFilterDesc: String
    val locExcludeRadio: String
    val locExcludeDesc: String
    val locWhitelistRadio: String
    val locWhitelistDesc: String
    val locDisabledRadio: String
    val locDisabledDesc: String
    val locResetAll: String
    val servCheckTitle: String
    val servCheckDesc: String
    val servCheckEndpointWarning: String
    val servCustomBadge: String
    val servAddBtn: String
    val servDefaultBtn: String
    val servAddDialogTitle: String
    val servAddNameLabel: String
    val servAddUrlLabel: String
    val servAddErrorEmpty: String
    val qrDialogTitle: String
    val qrCopyLink: String
    val qrFailGenerate: String
    val appearanceTitle: String
    val appearanceSubtitle: String
    val themeCardTitle: String
    val themeCardDesc: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val paletteCardTitle: String
    val paletteCardDesc: String
    val amoledCardTitle: String
    val amoledSwitchTitle: String
    val amoledSwitchDesc: String
    val languageCardTitle: String
    val languageCardDesc: String
    val meshCardTitle: String
    val meshSwitchTitle: String
    val meshSwitchDesc: String
    val meshModeLabel: String
    val meshModeStatic: String
    val meshModeBreathing: String
    val meshModeReactive: String
    val subManageTitle: String
    val subManageSubtitle: String
    val subAddBtn: String
    val subUpdateAllBtn: String
    val subUpdateSingleBtn: String
    val personalRulesHeader: String
    val personalRulesHeaderDesc: String
    val personalBlacklistRuleTitle: String
    val personalBlacklistRuleDesc: String
    val personalWhitelistRuleTitle: String
    val personalWhitelistRuleDesc: String
    val personalRuleAuto: String
    val personalRuleSelected: String
    val personalRulePublic: String
    val personalRuleDirect: String
    val subUpdatingBtn: String
    val subFailBannerTitle: String
    val subRetryBtn: String
    val subEmptyText: String
    val subMirrorsCount: String
    val subServersCount: String
    val subSharePercent: String
    val subDialogAddTitle: String
    val subDialogEditTitle: String
    val subNameLabel: String
    val subUrlLabel: String
    val subFilterLabel: String
    val subGroupRegexLabel: String
    val subSharePercentLabel: String
    val subScenariosLabel: String
    val subCatWhitelist: String
    val subCatBlacklist: String
    val subCatYoutube: String
    val geekConsoleTitle: String
    val geekEvents: String
    val geekCopyLogs: String
    val geekClearLogs: String
    val geekNoServerSelected: String
    val geekProtocolLabel: String
    val geekAddressLabel: String
    val geekPingLabel: String
    val geekExitIpLabel: String
    val geekExitIpUndefined: String
    val geekPingTestBtn: String
    val geekFindVipBtn: String
    val geekPingVipBtn: String
    val geekCheckServicesBtn: String
    val geekCheckingServicesBtn: String
    val geekTuneServicesDesc: String
    val geekServicesAvailabilityTitle: String
    val geekServicesAvailableCount: String
    val geekVipCacheTitle: String
    val geekStopVipBtn: String
    val geekRemoveVip: String
    val geekDpiBypassTitle: String
    val geekGlobalSniLabel: String
    val geekCloudflareIpLabel: String
    val geekCustomUaLabel: String
    val geekLogFilterPlaceholder: String
    val catScannerCore: String
    val catScannerCoreDesc: String
    val catProfileCheck: String
    val catProfileCheckDesc: String
    val catFailover: String
    val catFailoverDesc: String
    val catUpdates: String
    val catUpdatesDesc: String
    val catRouting: String
    val catRoutingDesc: String
    val catPersonalServers: String
    val catPersonalServersDesc: String
    val catVpnCore: String
    val catVpnCoreDesc: String
    val catBackupData: String
    val catBackupDataDesc: String
    val cardSmartConnect: String
    val cardSmartConnectDesc: String
    val smartBatchLabel: String
    val smartPresetLabel: String
    val presetEqual: String
    val presetRandom: String
    val presetMore: String
    val presetLess: String
    val cardV2wCore: String
    val cardV2wCoreDesc: String
    val v2wEnable: String
    val v2wEnableDesc: String
    val v2wFallback: String
    val v2wFallbackDesc: String
    val v2wSocketsLabel: String
    val xrayPathLabel: String
    val v2wPathLabel: String
    val cardProfileCheck: String
    val cardProfileCheckDesc: String
    val testBeforeConnect: String
    val testBeforeConnectDesc: String
    val removeInsecure: String
    val removeInsecureDesc: String
    val serviceCheckEnable: String
    val serviceCheckEnableDesc: String
    val vipLimitLabel: String
    val cardSpeedTest: String
    val cardSpeedTestDesc: String
    val speedTestEnable: String
    val speedTestEnableDesc: String
    val speedTestBytesLabel: String
    val speedTestTimeoutLabel: String
    val minSpeedThresholdLabel: String
    val cardFailover: String
    val cardFailoverDesc: String
    val autoFailoverEnable: String
    val autoFailoverEnableDesc: String
    val heartbeatLabel: String
    val smartFailoverEnable: String
    val smartFailoverEnableDesc: String
    val failoverDegradationLabel: String
    val failoverStuckLabel: String
    val cardNetCheck: String
    val cardNetCheckDesc: String
    val netCheckEnable: String
    val netCheckEnableDesc: String
    val primaryDomainLabel: String
    val fallbackDomainLabel: String
    val connCheckUrlLabel: String
    val cardSubUpdate: String
    val cardSubUpdateDesc: String
    val subAutoUpdateEnable: String
    val subAutoUpdateEnableDesc: String
    val subIntervalLabel: String
    val subOnStartEnable: String
    val subOnStartEnableDesc: String
    val subOnlyProxyEnable: String
    val subOnlyProxyEnableDesc: String
    val subThreadsLabel: String
    val yandexTranslateEnable: String
    val yandexTranslateEnableDesc: String
    val userAgentLabel: String
    val cardAppUpdates: String
    val cardAppUpdatesDesc: String
    val autoCheckUpdates: String
    val autoCheckUpdatesDesc: String
    val betaUpdates: String
    val betaUpdatesDesc: String
    val cardRoutingBypass: String
    val cardRoutingBypassDesc: String
    val bypassRuEnable: String
    val bypassRuEnableDesc: String
    val customEndpointEnable: String
    val customEndpointEnableDesc: String
    val endpointConfigLabel: String
    val endpointSubUrlLabel: String
    val endpointSelectServer: String
    val endpointSelectServerTitle: String
    val endpointE2eEnable: String
    val endpointE2eEnableDesc: String
    val cardSplitTunneling: String
    val cardSplitTunnelingDesc: String
    val domainSplitEnable: String
    val domainSplitEnableDesc: String
    val domainWhitelistMode: String
    val domainBlacklistMode: String
    val directDomainsLabel: String
    val proxyDomainsLabel: String
    val cardVpnDns: String
    val cardVpnDnsDesc: String
    val preferIpv6: String
    val preferIpv6Desc: String
    val localDns: String
    val localDnsDesc: String
    val fakeDns: String
    val fakeDnsDesc: String
    val additionalHttpProxy: String
    val additionalHttpProxyDesc: String
    val vpnDnsLabel: String
    val vpnBypassLan: String
    val vpnBypassLanDesc: String
    val vpnAddressLabel: String
    val vpnMtuLabel: String
    val cardCoreSocks: String
    val cardCoreSocksDesc: String
    val sniffingEnable: String
    val sniffingEnableDesc: String
    val routeOnlyEnable: String
    val routeOnlyEnableDesc: String
    val allowLanEnable: String
    val allowLanEnableDesc: String
    val allowInsecureEnable: String
    val allowInsecureEnableDesc: String
    val localProxyPortLabel: String
    val localProxyLoginLabel: String
    val localProxyPasswordLabel: String
    val remoteDnsLabel: String
    val internalDnsLabel: String
    val dnsHostsLabel: String
    val cardMux: String
    val cardMuxDesc: String
    val muxEnable: String
    val muxEnableDesc: String
    val muxTcpLabel: String
    val muxXudpLabel: String
    val cardFragmentation: String
    val cardFragmentationDesc: String
    val fragmentEnable: String
    val fragmentEnableDesc: String
    val fragmentLengthLabel: String
    val fragmentIntervalLabel: String
    val fragmentPacketsLabel: String
    val cardSystemTray: String
    val cardSystemTrayDesc: String
    val minimizeToTrayEnable: String
    val minimizeToTrayEnableDesc: String
    val cardReset: String
    val cardResetDesc: String
    val resetAllSettings: String
    val resetDialogTitle: String
    val resetDialogConfirm: String
    val subStatusActual: String
    val subStatusOutdated: String
    val subStatusFailed: String
    val error: String
    val langSystem: String
    val resetSuccess: String

    // Status
    val statusConnecting: String

    // Sudo dialog
    val sudoTitle: String
    val sudoDesc: String
    val sudoPasswordLabel: String
    val sudoPasswordPlaceholder: String
    val sudoShowPassword: String
    val sudoHidePassword: String
    val sudoVerifying: String
    val sudoConfirm: String
    val sudoCancel: String
    val sudoEmptyError: String
    val sudoInvalidError: String

    // Unsaved settings dialog
    val unsavedTitle: String
    val unsavedDesc: String
    val unsavedSave: String
    val unsavedDiscard: String
    val unsavedCancel: String

    // Update dialog
    val updateDialogTitle: String
    val updateCurrentVersion: String
    val updateNewVersion: String
    val updateChangelog: String
    val updateFile: String
    val updateDownload: String
    val updateViewRelease: String
    val updateAllReleases: String
    val updateLater: String

    // Tray Menu
    val trayOpen: String
    val trayDisconnect: String
    val trayConnecting: String
    val trayRetry: String
    val trayConnect: String
    val traySwitchToProxy: String
    val traySwitchToTun: String
    val traySystemProxyOn: String
    val traySystemProxyOff: String
    val trayExit: String
}

object StringsRu : AppStrings {
    override val appName: String = "ПроБел"
    override val appTitle: String = "ПроБел — v2whitelist"
    override val menu: String = "Меню"
    override val subscriptions: String = "Подписки"
    override val geekMode: String = "Geek Mode"
    override val appearance: String = "Оформление"
    override val settings: String = "Настройки"
    override val exit: String = "Выход"
    override val save: String = "Сохранить"
    override val saved: String = "Настройки успешно применены!"
    override val cancel: String = "Отмена"
    override val done: String = "Готово"
    override val delete: String = "Удалить"
    override val edit: String = "Редактировать"
    override val retry: String = "Повторить"
    override val copy: String = "Копировать"
    override val clear: String = "Очистить"
    override val close: String = "Закрыть"
    override val source: String = "Источник: "
    override val status: String = "Статус"
    override val protocol: String = "Протокол: "
    override val address: String = "Адрес: "
    override val unknown: String = "Неизвестные"
    override val all: String = "Все"
    override val statusConnected: String = "Подключено"
    override val statusTesting: String = "Тестирование соединения…"
    override val statusError: String = "Ошибка соединения"
    override val statusDisconnected: String = "Не подключено"
    override val statusTestingServers: String = "Тестирование серверов…"
    override val manualSelectionPrefix: String = "Ручной выбор: "
    override val tunnelActive: String = "Защищённый туннель активен"
    override val pressStartToConnect: String = "Нажмите Старт для безопасного подключения"
    override val btnStart: String = "Старт"
    override val btnStop: String = "Стоп"
    override val btnRetry: String = "Повтор"
    override val btnCancel: String = "Отмена"
    override val btnSwitchServer: String = "Сменить сервер"
    override val btnSwitchingServer: String = "Смена сервера…"
    override val routingModeTitle: String = "Режим работы: "
    override val routingModeTun: String = "TUN (Виртуальный адаптер)"
    override val routingModeProxy: String = "Прокси (SOCKS5 / HTTP)"
    override val routingModeTunDesc: String = "Весь сетевой трафик системы идет через TUN туннель"
    override val routingModeProxyDesc: String = "Системный прокси на 127.0.0.1:10808 / 10809"
    override val tunEnabled: String = "Включен TUN"
    override val proxyEnabled: String = "Включен Прокси"
    override val scenarioManual: String = "Сценарий: Ручной выбор узла"
    override val scenarioAuto: String = "Сценарий: Автопоиск"
    override val scenarioAutoTitle: String = "Авто"
    override val scenarioAutoSubtitle: String = "Баланс БС и ЧС"
    override val scenarioWhitelistTitle: String = "Белый список"
    override val scenarioWhitelistSubtitle: String = "БС (Только РФ)"
    override val scenarioBlacklistTitle: String = "Чёрный список"
    override val scenarioBlacklistSubtitle: String = "ЧС (Обход блоков)"
    override val scenarioYoutubeTitle: String = "YouTube"
    override val scenarioYoutubeSubtitle: String = "YouTube и Музыка"
    override val showServerList: String = "Показать список серверов"
    override val hideServerList: String = "Скрыть список серверов"
    override val searchNodesPlaceholder: String = "Поиск узлов по названию или хосту…"
    override val noServersFoundCriteria: String = "Нет серверов по выбранным критериям"
    override val updateAvailableTitle: String = "Доступна новая версия ПроБел: v"
    override val updateAvailableSubtitle: String = "Нажмите «Обновить» для просмотра изменений или скачивания"
    override val btnUpdate: String = "Обновить"
    override val btnDismiss: String = "Скрыть"
    override val noSubsText: String = "Нет подписок"
    override val subFailureBanner: String = "Сбой обновления: "
    override val qrShowCode: String = "Показать QR код узла"
    override val noActiveServer: String = "Нет активного сервера"
    override val pingAsc: String = "⚡ Пинг ▲"
    override val pingTest: String = "Пинг-тест"
    override val ping: String = "Пинг"
    override val findVip: String = "Найти VIP сервер"
    override val pingVip: String = "Пинг VIP"
    override val dpiBypass: String = "DPI Bypass (Подмена параметров для обхода блокировок):"
    override val copyLogs: String = "Копировать логи"
    override val addSub: String = "Добавить подписку"
    override val updateAll: String = "Обновить все"
    override val editSub: String = "Редактировать"
    override val deleteSub: String = "Удалить"
    override val theme: String = "Тема интерфейса"
    override val language: String = "Язык приложения"
    override val turnOnVpn: String = "▶ Подключить VPN"
    override val turnOffVpn: String = "⏹ Отключить VPN"
    override val systemProxyOs: String = "Системный прокси ОС"
    override val quickSwitch: String = "Быстрое переключение"
    override val routingModeCardTitle: String = "Режим работы (Прокси / TUN)"
    override val serversNotFound: String = "Серверы не найдены"
    override val searchServers: String = "Поиск по серверам..."
    override val serverList: String = "Список серверов"
    override val locationsFilter: String = "Фильтр стран"
    override val locationsFilterExclude: String = "Исключать выбранные"
    override val locationsFilterWhitelist: String = "Только выбранные"
    override val locationsFilterDisabled: String = "Отключен"
    override val proxyStandaloneTitle: String = "Режим чистого прокси (Standalone Proxy)"
    override val proxyStandaloneDescLan: String = "Работает без TUN: локальные порты открыты для LAN"
    override val proxyStandaloneDescNoRoot: String = "Работает без TUN адаптера и прав root"
    override val proxySystemProxyOn: String = "Системный прокси ОС: ВКЛ"
    override val proxySystemProxyOff: String = "Системный прокси ОС: ВЫКЛ"
    override val proxyQuickExportTitle: String = "Быстрая настройка приложений в 1 клик:"
    override val proxyTgLinkCopied: String = "Ссылка Telegram Proxy"
    override val proxyTerminalCopied: String = "Команда экспорта для терминала"
    override val proxyPsCopied: String = "Команда экспорта для PowerShell"
    override val locFilterTitle: String = "Фильтр стран и локаций"
    override val locFilterDesc: String = "Используется при интеллектуальном подборе серверов (SmartConnect) и в авто-режиме."
    override val locExcludeRadio: String = "Исключать выбранные"
    override val locExcludeDesc: String = "Чёрный список стран (серверы этих стран будут пропущены)"
    override val locWhitelistRadio: String = "Только выбранные"
    override val locWhitelistDesc: String = "Белый список стран (подключение только к отмеченным странам)"
    override val locDisabledRadio: String = "Отключить фильтр"
    override val locDisabledDesc: String = "Использовать серверы из всех доступных локаций"
    override val locResetAll: String = "Сбросить выбор"
    override val servCheckTitle: String = "Проверка доступности сервисов"
    override val servCheckDesc: String = "Выберите сервисы, доступность которых будет проверяться вместо стандартного теста 204. Если хотя бы один выбранный сервис недоступен, сервер отклоняется."
    override val servCheckEndpointWarning: String = "Внимание: при включенном конечном узле проверка сервисов игнорируется в пользу сквозного теста цепочки."
    override val servCustomBadge: String = "Кастомный"
    override val servAddBtn: String = "+ Добавить сервис"
    override val servDefaultBtn: String = "По умолчанию"
    override val servAddDialogTitle: String = "Добавить сервис для проверки"
    override val servAddNameLabel: String = "Название (напр. GitHub)"
    override val servAddUrlLabel: String = "URL (напр. https://github.com)"
    override val servAddErrorEmpty: String = "Заполните все поля"
    override val qrDialogTitle: String = "QR-код текущего сервера"
    override val qrCopyLink: String = "Скопировать ссылку"
    override val qrFailGenerate: String = "Не удалось сгенерировать QR"
    override val appearanceTitle: String = "Оформление"
    override val appearanceSubtitle: String = "Настройка визуального стиля, темы, палитры цветов и градиентных фонов"
    override val themeCardTitle: String = "Тема интерфейса"
    override val themeCardDesc: String = "Выберите режим отображения интерфейса:"
    override val themeSystem: String = "Как в системе (авто)"
    override val themeLight: String = "Светлая"
    override val themeDark: String = "Тёмная"
    override val paletteCardTitle: String = "Цветовая палитра"
    override val paletteCardDesc: String = "Настройка акцентного цвета элементов интерфейса и кнопок:"
    override val amoledCardTitle: String = "Чистый чёрный (AMOLED)"
    override val amoledSwitchTitle: String = "Включить истинный чёрный фон #000000"
    override val amoledSwitchDesc: String = "Истинный глубокий черный фон #000000 в темной теме для OLED экранов"
    override val languageCardTitle: String = "Язык приложения"
    override val languageCardDesc: String = "Выберите язык интерфейса:"
    override val meshCardTitle: String = "Градиентные фоны (Mesh)"
    override val meshSwitchTitle: String = "Медленно переливающийся цветной фон"
    override val meshSwitchDesc: String = "По умолчанию выключено. Добавляет кинематографический анимированный фон."
    override val meshModeLabel: String = "Режим анимации Mesh Gradient:"
    override val meshModeStatic: String = "Статичный"
    override val meshModeBreathing: String = "Дыхание (плавное перетекание)"
    override val meshModeReactive: String = "Реактивный (стягивание при старте)"
    override val subManageTitle: String = "Управление подписками"
    override val subManageSubtitle: String = "Единая система подписок, зеркал и категорий маршрутизации"
    override val subAddBtn: String = "Добавить"
    override val subUpdateAllBtn: String = "Обновить все"
    override val subUpdateSingleBtn: String = "Обновить подписку"
    override val personalRulesHeader: String = "Правила сценариев для доверенных узлов"
    override val personalRulesHeaderDesc: String = "Настройте правила использования личных узлов или публичных подписок под разные сценарии"
    override val personalBlacklistRuleTitle: String = "При сценарии ЧС / YouTube (обычный интернет)"
    override val personalBlacklistRuleDesc: String = "Поведение подключения при доступном обычном интернете"
    override val personalWhitelistRuleTitle: String = "При сценарии БС (Белый список / глушение)"
    override val personalWhitelistRuleDesc: String = "Поведение подключения при активных белых списках / глушении"
    override val personalRuleAuto: String = "⚡ Автовыбор (Личные)"
    override val personalRuleSelected: String = "🎯 Выбранный сервер"
    override val personalRulePublic: String = "🌐 Подписки ПроБел"
    override val personalRuleDirect: String = "🚫 Без VPN (Прямой)"
    override val subUpdatingBtn: String = "Обновление…"
    override val subFailBannerTitle: String = "Не удалось обновить подписки"
    override val subRetryBtn: String = "Повторить"
    override val subEmptyText: String = "Нет добавленных подписок. Нажмите «Добавить», чтобы подключить источник."
    override val subMirrorsCount: String = "Зеркал: "
    override val subServersCount: String = "Серверов: "
    override val subSharePercent: String = "Доля: "
    override val subDialogAddTitle: String = "Добавить подписку"
    override val subDialogEditTitle: String = "Редактирование подписки"
    override val subNameLabel: String = "Название подписки"
    override val subUrlLabel: String = "Ссылки на подписку (зеркала через | )"
    override val subFilterLabel: String = "Фильтр Regex (исключать узлы до появления)"
    override val subGroupRegexLabel: String = "Regex для групп"
    override val subSharePercentLabel: String = "Процент доли серверов (%)"
    override val subScenariosLabel: String = "Категории авто-режима:"
    override val subCatWhitelist: String = "Белые списки"
    override val subCatBlacklist: String = "Чёрные списки"
    override val subCatYoutube: String = "YouTube и музыка"
    override val geekConsoleTitle: String = "Geek Mode — Инженерная консоль"
    override val geekEvents: String = "событий"
    override val geekCopyLogs: String = "Копировать"
    override val geekClearLogs: String = "Очистить"
    override val geekNoServerSelected: String = "Сервер не выбран"
    override val geekProtocolLabel: String = "Протокол: "
    override val geekAddressLabel: String = "Адрес: "
    override val geekPingLabel: String = "Пинг: "
    override val geekExitIpLabel: String = "Exit IP:"
    override val geekExitIpUndefined: String = "Не определен"
    override val geekPingTestBtn: String = "Пинг-тест"
    override val geekFindVipBtn: String = "Найти VIP"
    override val geekPingVipBtn: String = "Пинг VIP"
    override val geekCheckServicesBtn: String = "Проверить сервисы"
    override val geekCheckingServicesBtn: String = "Параллельная проверка сервисов…"
    override val geekTuneServicesDesc: String = "Настройка сервисов"
    override val geekServicesAvailabilityTitle: String = "Доступность сервисов:"
    override val geekServicesAvailableCount: String = "доступны"
    override val geekVipCacheTitle: String = "VIP-кэш быстрых узлов"
    override val geekStopVipBtn: String = "⏹ Остановить VIP"
    override val geekRemoveVip: String = "Удалить из VIP"
    override val geekDpiBypassTitle: String = "DPI Bypass (Подмена параметров для обхода блокировок):"
    override val geekGlobalSniLabel: String = "Global SNI"
    override val geekCloudflareIpLabel: String = "Cloudflare Clean IP"
    override val geekCustomUaLabel: String = "Кастомный User-Agent"
    override val geekLogFilterPlaceholder: String = "Фильтр логов ядра (поиск по ключевым словам)…"
    override val catScannerCore: String = "Сканер и ядро"
    override val catScannerCoreDesc: String = "Настройки распределения серверов SmartConnect и параметры ядра v2w-core"
    override val catProfileCheck: String = "Проверка"
    override val catProfileCheckDesc: String = "Smart-проверка профилей перед подключением, доступность сервисов и замер скорости"
    override val catFailover: String = "Failover"
    override val catFailoverDesc: String = "Авто-переключение серверов при сбоях соединения, Smart Failover и статус сети"
    override val catUpdates: String = "Обновление"
    override val catUpdatesDesc: String = "Фоновое обновление подписок и проверка новых версий ПроБел на GitHub"
    override val catRouting: String = "Маршрутизация"
    override val catRoutingDesc: String = "Обход блокировок РФ, сплит-туннелинг по доменам и конечный узел (цепочка)"
    override val catPersonalServers: String = "Доверенные сервера"
    override val catPersonalServersDesc: String = "Свои VPS-узлы, приватные подписки, автопоиск и управление конечным узлом"
    override val catVpnCore: String = "VPN и ядро"
    override val catVpnCoreDesc: String = "Сетевые параметры VPN, SOCKS5 прокси, DNS, Mux и фрагментация пакетов"
    override val catBackupData: String = "Данные"
    override val catBackupDataDesc: String = "Системные параметры, сворачивание в трей и сброс настроек"
    override val cardSmartConnect: String = "Распределение серверов SmartConnect"
    override val cardSmartConnectDesc: String = "Параметры разбивки пула серверов на пачки"
    override val smartBatchLabel: String = "Размер пачки серверов (по умолчанию 20)"
    override val smartPresetLabel: String = "Пресет распределения по умолчанию:"
    override val presetEqual: String = "Поровну"
    override val presetRandom: String = "Случайно"
    override val presetMore: String = "Больше"
    override val presetLess: String = "Меньше"
    override val cardV2wCore: String = "Ядро v2w-core и сканер"
    override val cardV2wCoreDesc: String = "Высокопроизводительное нативное сканирование узлов"
    override val v2wEnable: String = "Использовать ядро v2w-core"
    override val v2wEnableDesc: String = "Быстрое нативное тестирование через отдельный процесс"
    override val v2wFallback: String = "Fallback на старый сканер"
    override val v2wFallbackDesc: String = "Резервный механизм при сбоях нативного бинарника"
    override val v2wSocketsLabel: String = "Количество сокетов v2w-core (параллелизм)"
    override val xrayPathLabel: String = "Путь к бинарнику Xray (оставьте пустым для автопоиска)"
    override val v2wPathLabel: String = "Путь к бинарнику v2w-scan (оставьте пустым для автопоиска)"
    override val cardProfileCheck: String = "Smart-проверка профилей"
    override val cardProfileCheckDesc: String = "Предварительная валидация перед коннектом"
    override val testBeforeConnect: String = "Проверять профиль перед подключением"
    override val testBeforeConnectDesc: String = "Отсеивает нерабочие серверы без разрыва соединения"
    override val removeInsecure: String = "Удалять небезопасные профили"
    override val removeInsecureDesc: String = "Автоматически удаляет узлы со слабыми шифрами"
    override val serviceCheckEnable: String = "Проверка доступности сервисов"
    override val serviceCheckEnableDesc: String = "Проверяет Google, YouTube, Telegram, WhatsApp, Instagram вместо пинга 204"
    override val vipLimitLabel: String = "Лимит кэша быстрых серверов (VIP)"
    override val cardSpeedTest: String = "Тест скорости при проверке"
    override val cardSpeedTestDesc: String = "Замер реальной скорости загрузки данных"
    override val speedTestEnable: String = "Замерять скорость при проверке"
    override val speedTestEnableDesc: String = "Скачивает тестовый блок для замера Mbps"
    override val speedTestBytesLabel: String = "Размер тестового блока данных (байт)"
    override val speedTestTimeoutLabel: String = "Таймаут замера скорости (мс)"
    override val minSpeedThresholdLabel: String = "Минимальный порог скорости (Мбит/с)"
    override val cardFailover: String = "Smart Failover (Авто-переключение серверов)"
    override val cardFailoverDesc: String = "Защита от внезапного падения и блокировок"
    override val autoFailoverEnable: String = "Включить авто-переключение серверов"
    override val autoFailoverEnableDesc: String = "Автоматически выбирает рабочий сервер при сбое"
    override val heartbeatLabel: String = "Интервал проверки Heartbeat (сек)"
    override val smartFailoverEnable: String = "Smart Failover по скорости трафика"
    override val smartFailoverEnableDesc: String = "Реагирует на резкое падение скорости при блокировке"
    override val failoverDegradationLabel: String = "Порог деградации трафика (КБ)"
    override val failoverStuckLabel: String = "Длительность простоя для переключения (сек)"
    override val cardNetCheck: String = "Проверка статуса сети"
    override val cardNetCheckDesc: String = "Мониторинг глобального доступа к интернету"
    override val netCheckEnable: String = "Включить проверку статуса сети"
    override val netCheckEnableDesc: String = "Пингует домены для проверки доступности сети"
    override val primaryDomainLabel: String = "Основной домен проверки (напр. google.com)"
    override val fallbackDomainLabel: String = "Резервный домен проверки (напр. cloudflare.com)"
    override val connCheckUrlLabel: String = "URL проверки статуса VPN соединения"
    override val cardSubUpdate: String = "Фоновое обновление подписок"
    override val cardSubUpdateDesc: String = "Автоматическая актуализация серверов по расписанию"
    override val subAutoUpdateEnable: String = "Автоматическое обновление подписок"
    override val subAutoUpdateEnableDesc: String = "Обновляет список серверов в фоне"
    override val subIntervalLabel: String = "Интервал обновления (в минутах)"
    override val subOnStartEnable: String = "Обновлять подписки при запуске приложения"
    override val subOnStartEnableDesc: String = "Запрашивает свежие узлы при каждом старте"
    override val subOnlyProxyEnable: String = "Обновлять только через VPN/Прокси"
    override val subOnlyProxyEnableDesc: String = "Защищает от блокировки ссылок подписок провайдером"
    override val subThreadsLabel: String = "Параллельные потоки загрузки подписок"
    override val yandexTranslateEnable: String = "Обход капчи через Яндекс.Переводчик"
    override val yandexTranslateEnableDesc: String = "Позволяет скачивать подписки за Cloudflare защитой"
    override val userAgentLabel: String = "Пользовательский User-Agent подписок"
    override val cardAppUpdates: String = "Обновления приложения"
    override val cardAppUpdatesDesc: String = "Контроль версий и проверка релизов на GitHub"
    override val autoCheckUpdates: String = "Автоматически проверять обновления"
    override val autoCheckUpdatesDesc: String = "Уведомлять о выходе новых релизов ПроБел"
    override val betaUpdates: String = "Получать бета-версии"
    override val betaUpdatesDesc: String = "Ранний доступ к новым функциям и оптимизациям"
    override val cardRoutingBypass: String = "Обход блокировок и Proxy Chaining"
    override val cardRoutingBypassDesc: String = "Прямой доступ к локальным сервисам и цепочки прокси"
    override val bypassRuEnable: String = "Прямой доступ к сервисам РФ (Bypass RU)"
    override val bypassRuEnableDesc: String = "Госуслуги, банки, маркетплейсы идут напрямую в обход VPN"
    override val customEndpointEnable: String = "Использовать конечный узел (Proxy Chaining)"
    override val customEndpointEnableDesc: String = "Пропускает трафик через цепочку: Сервер -> Конечный узел"
    override val endpointConfigLabel: String = "Конфигурация конечного узла (URI или JSON)"
    override val endpointSubUrlLabel: String = "Ссылка на подписку для выбора конечного узла"
    override val endpointSelectServer: String = "Выбрать сервер из подписки"
    override val endpointSelectServerTitle: String = "Выбор конечного узла из подписки"
    override val endpointE2eEnable: String = "Сквозная проверка цепочки (E2E Check)"
    override val endpointE2eEnableDesc: String = "Тестирует доступность через оба узла перед коннектом"
    override val cardSplitTunneling: String = "Сплит-туннелинг по доменам"
    override val cardSplitTunnelingDesc: String = "Пользовательские правила прямого и проксированного трафика"
    override val domainSplitEnable: String = "Включить сплит-туннелинг по доменам"
    override val domainSplitEnableDesc: String = "Разделяет трафик по заданным спискам доменов"
    override val domainWhitelistMode: String = "Режим белого списка (только указанные домены в прокси)"
    override val domainBlacklistMode: String = "Режим черного списка (все в прокси, кроме указанных)"
    override val directDomainsLabel: String = "Прямые домены (Direct) через запятую"
    override val proxyDomainsLabel: String = "Проксируемые домены (Proxy) через запятую"
    override val cardVpnDns: String = "Настройки VPN и DNS"
    override val cardVpnDnsDesc: String = "Параметры виртуального сетевого адаптера и резолвинга"
    override val preferIpv6: String = "Предпочитать IPv6 соединения"
    override val preferIpv6Desc: String = "Использовать IPv6 при наличии AAAA записей"
    override val localDns: String = "Использовать локальный системный DNS"
    override val localDnsDesc: String = "Резолвить имена через DNS провайдера"
    override val fakeDns: String = "Включить FakeDNS"
    override val fakeDnsDesc: String = "Ускоряет резолвинг и предотвращает DNS утечки"
    override val additionalHttpProxy: String = "Дополнительный HTTP прокси порт"
    override val additionalHttpProxyDesc: String = "Создает HTTP порт параллельно с SOCKS5"
    override val vpnDnsLabel: String = "DNS серверы для VPN (через запятую)"
    override val vpnBypassLan: String = "Исключать локальную сеть (Bypass LAN)"
    override val vpnBypassLanDesc: String = "Прямой доступ к роутеру, принтерам и устройствам LAN"
    override val vpnAddressLabel: String = "IP адрес интерфейса VPN"
    override val vpnMtuLabel: String = "Размер MTU пакета VPN"
    override val cardCoreSocks: String = "Настройки ядра и SOCKS5"
    override val cardCoreSocksDesc: String = "Низкоуровневые параметры маршрутизации и входящих портов"
    override val sniffingEnable: String = "Включить сниффинг трафика (Sniffing)"
    override val sniffingEnableDesc: String = "Определяет реальный протокол и домен внутри TLS туннелей"
    override val routeOnlyEnable: String = "Только маршрутизация (Route Only)"
    override val routeOnlyEnableDesc: String = "Использовать сниффинг только для правил, не подменяя адрес"
    override val allowLanEnable: String = "Разрешить подключения из локальной сети (LAN)"
    override val allowLanEnableDesc: String = "Открывает SOCKS5 и HTTP порты для других устройств"
    override val allowInsecureEnable: String = "Разрешить небезопасные соединения (Allow Insecure)"
    override val allowInsecureEnableDesc: String = "Отключает проверку валидности SSL/TLS сертификатов серверов"
    override val localProxyPortLabel: String = "Порт локального SOCKS5 прокси"
    override val localProxyLoginLabel: String = "Логин для авторизации на локальном прокси"
    override val localProxyPasswordLabel: String = "Пароль для авторизации на локальном прокси"
    override val remoteDnsLabel: String = "Удаленный DNS (Remote DNS)"
    override val internalDnsLabel: String = "Внутренний прямой DNS (Direct DNS)"
    override val dnsHostsLabel: String = "Статические DNS сопоставления (IP domain, ...)"
    override val cardMux: String = "Multiplexing (Mux)"
    override val cardMuxDesc: String = "Объединение множества соединений в единый TCP поток"
    override val muxEnable: String = "Включить Multiplexing (Mux)"
    override val muxEnableDesc: String = "Снижает задержки рукопожатий при частых запросах"
    override val muxTcpLabel: String = "Максимальное число параллельных потоков TCP Mux"
    override val muxXudpLabel: String = "Максимальное число потоков xUDP Mux"
    override val cardFragmentation: String = "Фрагментация пакетов (Anti-DPI)"
    override val cardFragmentationDesc: String = "Разрезание TLS Client Hello для обхода блокировок ТСПУ"
    override val fragmentEnable: String = "Включить фрагментацию пакетов"
    override val fragmentEnableDesc: String = "Разделяет начальные пакеты соединения на фрагменты"
    override val fragmentLengthLabel: String = "Длина фрагментов (байт, напр. 10-50)"
    override val fragmentIntervalLabel: String = "Интервал между фрагментами (мс, напр. 10-20)"
    override val fragmentPacketsLabel: String = "Фрагментируемые пакеты (напр. 1-3 или tlshello)"
    override val cardSystemTray: String = "Системные параметры"
    override val cardSystemTrayDesc: String = "Поведение окна приложения и интеграция с ОС"
    override val minimizeToTrayEnable: String = "Сворачивать в системный трей при закрытии окна"
    override val minimizeToTrayEnableDesc: String = "Приложение продолжит работать в фоне"
    override val cardReset: String = "Сброс настроек и данных"
    override val cardResetDesc: String = "Возврат всех параметров к исходным значениям по умолчанию"
    override val resetAllSettings: String = "Сбросить все настройки к заводским"
    override val resetDialogTitle: String = "Сброс всех настроек"
    override val resetDialogConfirm: String = "Вы уверены, что хотите сбросить все параметры приложения к исходным? Пользовательские подписки будут сохранены."
    override val subStatusActual: String = "Актуальна"
    override val subStatusOutdated: String = "Устарела"
    override val subStatusFailed: String = "Сбой обновления"
    override val error: String = "Ошибка"
    override val langSystem: String = "Как в системе"
    override val resetSuccess: String = "Настройки успешно сброшены к значениям по умолчанию!"
    override val statusConnecting: String = "Подключение..."
    override val sudoTitle: String = "Активация режима TUN"
    override val sudoDesc: String = "Для работы режима TUN (полная переадресация системного трафика) на Linux требуются привилегии суперпользователя (sudo).\n\nПароль вводится один раз и сохраняется только в оперативной памяти до закрытия приложения."
    override val sudoPasswordLabel: String = "Пароль sudo"
    override val sudoPasswordPlaceholder: String = "Введите пароль суперпользователя..."
    override val sudoShowPassword: String = "Показать пароль"
    override val sudoHidePassword: String = "Скрыть пароль"
    override val sudoVerifying: String = "Проверка прав доступа sudo..."
    override val sudoConfirm: String = "Подтвердить"
    override val sudoCancel: String = "Отмена"
    override val sudoEmptyError: String = "Пароль не может быть пустым"
    override val sudoInvalidError: String = "Неверный пароль sudo. Пожалуйста, попробуйте снова."
    override val unsavedTitle: String = "Несохранённые изменения"
    override val unsavedDesc: String = "В настройках есть несохранённые изменения. Сохранить их перед переходом в другой раздел?"
    override val unsavedSave: String = "Сохранить"
    override val unsavedDiscard: String = "Не сохранять"
    override val unsavedCancel: String = "Отмена"
    override val updateDialogTitle: String = "Доступно обновление"
    override val updateCurrentVersion: String = "Текущая версия"
    override val updateNewVersion: String = "Новая версия"
    override val updateChangelog: String = "Что нового:"
    override val updateFile: String = "Файл"
    override val updateDownload: String = "Скачать обновление"
    override val updateViewRelease: String = "Перейти к релизу"
    override val updateAllReleases: String = "Все релизы"
    override val updateLater: String = "Позже"
    override val trayOpen: String = "Открыть ПроБел"
    override val trayDisconnect: String = "⏹ Отключить VPN"
    override val trayConnecting: String = "⏳ Подключение..."
    override val trayRetry: String = "🔄 Повторить подключение"
    override val trayConnect: String = "▶ Подключить VPN"
    override val traySwitchToProxy: String = "Режим: TUN → переключить на Прокси"
    override val traySwitchToTun: String = "Режим: Прокси → переключить на TUN"
    override val traySystemProxyOn: String = "✔ Системный прокси ОС: ВКЛ (нажать — выкл)"
    override val traySystemProxyOff: String = "Системный прокси ОС: ВЫКЛ (нажать — вкл)"
    override val trayExit: String = "Выход"
}

object StringsEn : AppStrings {
    override val appName: String = "ProBel"
    override val appTitle: String = "ProBel — v2whitelist"
    override val menu: String = "Menu"
    override val subscriptions: String = "Subscriptions"
    override val geekMode: String = "Geek Mode"
    override val appearance: String = "Appearance"
    override val settings: String = "Settings"
    override val exit: String = "Exit"
    override val save: String = "Save"
    override val saved: String = "Settings saved successfully!"
    override val cancel: String = "Cancel"
    override val done: String = "Done"
    override val delete: String = "Delete"
    override val edit: String = "Edit"
    override val retry: String = "Retry"
    override val copy: String = "Copy"
    override val clear: String = "Clear"
    override val close: String = "Close"
    override val source: String = "Source: "
    override val status: String = "Status"
    override val protocol: String = "Protocol: "
    override val address: String = "Address: "
    override val unknown: String = "Unknown"
    override val all: String = "All"
    override val statusConnected: String = "Connected"
    override val statusTesting: String = "Testing connection…"
    override val statusError: String = "Connection Error"
    override val statusDisconnected: String = "Disconnected"
    override val statusTestingServers: String = "Testing servers…"
    override val manualSelectionPrefix: String = "Manual selection: "
    override val tunnelActive: String = "Secure tunnel is active"
    override val pressStartToConnect: String = "Click Start to connect securely"
    override val btnStart: String = "Start"
    override val btnStop: String = "Stop"
    override val btnRetry: String = "Retry"
    override val btnCancel: String = "Cancel"
    override val btnSwitchServer: String = "Switch Server"
    override val btnSwitchingServer: String = "Switching server…"
    override val routingModeTitle: String = "Operation Mode: "
    override val routingModeTun: String = "TUN (Virtual Adapter)"
    override val routingModeProxy: String = "Proxy (SOCKS5 / HTTP)"
    override val routingModeTunDesc: String = "All system traffic is routed through the TUN tunnel"
    override val routingModeProxyDesc: String = "System proxy at 127.0.0.1:10808 / 10809"
    override val tunEnabled: String = "TUN Enabled"
    override val proxyEnabled: String = "Proxy Enabled"
    override val scenarioManual: String = "Scenario: Manual node selection"
    override val scenarioAuto: String = "Scenario: Auto-selection"
    override val scenarioAutoTitle: String = "Auto"
    override val scenarioAutoSubtitle: String = "Balance WL & BL"
    override val scenarioWhitelistTitle: String = "Whitelist"
    override val scenarioWhitelistSubtitle: String = "WL (Direct RU)"
    override val scenarioBlacklistTitle: String = "Blacklist"
    override val scenarioBlacklistSubtitle: String = "BL (Bypass Blocks)"
    override val scenarioYoutubeTitle: String = "YouTube"
    override val scenarioYoutubeSubtitle: String = "YouTube & Music"
    override val showServerList: String = "Show servers list"
    override val hideServerList: String = "Hide servers list"
    override val searchNodesPlaceholder: String = "Search nodes by name or host…"
    override val noServersFoundCriteria: String = "No servers match criteria"
    override val updateAvailableTitle: String = "New ProBel version available: v"
    override val updateAvailableSubtitle: String = "Click 'Update' to view changelog or download"
    override val btnUpdate: String = "Update"
    override val btnDismiss: String = "Dismiss"
    override val noSubsText: String = "No subscriptions"
    override val subFailureBanner: String = "Update failed: "
    override val qrShowCode: String = "Show node QR code"
    override val noActiveServer: String = "No active server"
    override val pingAsc: String = "⚡ Ping ▲"
    override val pingTest: String = "Ping Test"
    override val ping: String = "Ping"
    override val findVip: String = "Find VIP Server"
    override val pingVip: String = "Ping VIP"
    override val dpiBypass: String = "DPI Bypass (Anti-censorship parameters):"
    override val copyLogs: String = "Copy Logs"
    override val addSub: String = "Add Subscription"
    override val updateAll: String = "Update All"
    override val editSub: String = "Edit"
    override val deleteSub: String = "Delete"
    override val theme: String = "Interface Theme"
    override val language: String = "Application Language"
    override val turnOnVpn: String = "▶ Connect VPN"
    override val turnOffVpn: String = "⏹ Disconnect VPN"
    override val systemProxyOs: String = "System OS Proxy"
    override val quickSwitch: String = "Quick Switch"
    override val routingModeCardTitle: String = "Routing Mode (Proxy / TUN)"
    override val serversNotFound: String = "No servers found"
    override val searchServers: String = "Search servers..."
    override val serverList: String = "Servers List"
    override val locationsFilter: String = "Country Filter"
    override val locationsFilterExclude: String = "Exclude Selected"
    override val locationsFilterWhitelist: String = "Only Selected"
    override val locationsFilterDisabled: String = "Disabled"
    override val proxyStandaloneTitle: String = "Pure Proxy Mode (Standalone Proxy)"
    override val proxyStandaloneDescLan: String = "Runs without TUN: local ports open to LAN"
    override val proxyStandaloneDescNoRoot: String = "Runs without TUN adapter or root privileges"
    override val proxySystemProxyOn: String = "System OS Proxy: ON"
    override val proxySystemProxyOff: String = "System OS Proxy: OFF"
    override val proxyQuickExportTitle: String = "1-Click App Configuration:"
    override val proxyTgLinkCopied: String = "Telegram Proxy link"
    override val proxyTerminalCopied: String = "Terminal export command"
    override val proxyPsCopied: String = "PowerShell export command"
    override val locFilterTitle: String = "Country & Location Filter"
    override val locFilterDesc: String = "Used by SmartConnect automated server matching and auto scenarios."
    override val locExcludeRadio: String = "Exclude selected"
    override val locExcludeDesc: String = "Blacklist countries (servers in these countries will be skipped)"
    override val locWhitelistRadio: String = "Only selected"
    override val locWhitelistDesc: String = "Whitelist countries (connect only to checked countries)"
    override val locDisabledRadio: String = "Disable filter"
    override val locDisabledDesc: String = "Use servers from all available locations"
    override val locResetAll: String = "Reset selection"
    override val servCheckTitle: String = "Service Availability Check"
    override val servCheckDesc: String = "Select services to check instead of standard 204 test. If any selected service is unavailable, node is rejected."
    override val servCheckEndpointWarning: String = "Warning: custom endpoint overrides service checks in favor of end-to-end chain testing."
    override val servCustomBadge: String = "Custom"
    override val servAddBtn: String = "+ Add Service"
    override val servDefaultBtn: String = "Reset to Default"
    override val servAddDialogTitle: String = "Add Service for Verification"
    override val servAddNameLabel: String = "Name (e.g. GitHub)"
    override val servAddUrlLabel: String = "URL (e.g. https://github.com)"
    override val servAddErrorEmpty: String = "Fill in all fields"
    override val qrDialogTitle: String = "Current Server QR Code"
    override val qrCopyLink: String = "Copy Link"
    override val qrFailGenerate: String = "Failed to generate QR code"
    override val appearanceTitle: String = "Appearance"
    override val appearanceSubtitle: String = "Customize theme, color palette, AMOLED pure black, and animated mesh gradients"
    override val themeCardTitle: String = "Interface Theme"
    override val themeCardDesc: String = "Choose interface appearance mode:"
    override val themeSystem: String = "System Default (Auto)"
    override val themeLight: String = "Light"
    override val themeDark: String = "Dark"
    override val paletteCardTitle: String = "Color Palette"
    override val paletteCardDesc: String = "Select primary accent color for UI elements and buttons:"
    override val amoledCardTitle: String = "Pure Black (AMOLED)"
    override val amoledSwitchTitle: String = "Enable true black background #000000"
    override val amoledSwitchDesc: String = "True deep black #000000 in dark theme for OLED displays to save power"
    override val languageCardTitle: String = "Application Language"
    override val languageCardDesc: String = "Select interface language:"
    override val meshCardTitle: String = "Mesh Gradient Backgrounds"
    override val meshSwitchTitle: String = "Slowly shimmering colored background"
    override val meshSwitchDesc: String = "Disabled by default. Adds a cinematic animated backdrop."
    override val meshModeLabel: String = "Mesh Gradient animation mode:"
    override val meshModeStatic: String = "Static"
    override val meshModeBreathing: String = "Breathing (Smooth Flow)"
    override val meshModeReactive: String = "Reactive (Pulse on Start)"
    override val subManageTitle: String = "Subscription Management"
    override val subManageSubtitle: String = "Unified subscription system, mirror nodes, and scenario categorization"
    override val subAddBtn: String = "Add"
    override val subUpdateAllBtn: String = "Update All"
    override val subUpdateSingleBtn: String = "Update subscription"
    override val personalRulesHeader: String = "Scenario Rules for Trusted Servers"
    override val personalRulesHeaderDesc: String = "Configure usage of personal nodes vs public subscriptions per scenario"
    override val personalBlacklistRuleTitle: String = "In Blacklist / YouTube scenario (open internet)"
    override val personalBlacklistRuleDesc: String = "Connection behavior when open internet is available"
    override val personalWhitelistRuleTitle: String = "In Whitelist scenario (jamming mode)"
    override val personalWhitelistRuleDesc: String = "Connection behavior when whitelists or jamming are detected"
    override val personalRuleAuto: String = "⚡ Auto (Personal)"
    override val personalRuleSelected: String = "🎯 Selected server"
    override val personalRulePublic: String = "🌐 ProBel subscriptions"
    override val personalRuleDirect: String = "🚫 Direct (No VPN)"
    override val subUpdatingBtn: String = "Updating…"
    override val subFailBannerTitle: String = "Failed to update subscriptions"
    override val subRetryBtn: String = "Retry"
    override val subEmptyText: String = "No subscriptions added. Click 'Add' to connect a source."
    override val subMirrorsCount: String = "Mirrors: "
    override val subServersCount: String = "Servers: "
    override val subSharePercent: String = "Share: "
    override val subDialogAddTitle: String = "Add Subscription"
    override val subDialogEditTitle: String = "Edit Subscription"
    override val subNameLabel: String = "Subscription Name"
    override val subUrlLabel: String = "Subscription URLs (mirrors separated by | )"
    override val subFilterLabel: String = "Regex Filter (exclude nodes before import)"
    override val subGroupRegexLabel: String = "Group Regex"
    override val subSharePercentLabel: String = "Server share percentage (%)"
    override val subScenariosLabel: String = "Auto-mode Scenarios:"
    override val subCatWhitelist: String = "Whitelists"
    override val subCatBlacklist: String = "Blacklists"
    override val subCatYoutube: String = "YouTube & Music"
    override val geekConsoleTitle: String = "Geek Mode — Engineering Console"
    override val geekEvents: String = "events"
    override val geekCopyLogs: String = "Copy"
    override val geekClearLogs: String = "Clear"
    override val geekNoServerSelected: String = "No server selected"
    override val geekProtocolLabel: String = "Protocol: "
    override val geekAddressLabel: String = "Address: "
    override val geekPingLabel: String = "Ping: "
    override val geekExitIpLabel: String = "Exit IP:"
    override val geekExitIpUndefined: String = "Not detected"
    override val geekPingTestBtn: String = "Ping Test"
    override val geekFindVipBtn: String = "Find VIP"
    override val geekPingVipBtn: String = "Ping VIP"
    override val geekCheckServicesBtn: String = "Check Services"
    override val geekCheckingServicesBtn: String = "Parallel service checking…"
    override val geekTuneServicesDesc: String = "Configure services"
    override val geekServicesAvailabilityTitle: String = "Service Availability:"
    override val geekServicesAvailableCount: String = "available"
    override val geekVipCacheTitle: String = "VIP Fast Nodes Cache"
    override val geekStopVipBtn: String = "⏹ Stop VIP"
    override val geekRemoveVip: String = "Remove from VIP"
    override val geekDpiBypassTitle: String = "DPI Bypass (Anti-censorship parameter spoofing):"
    override val geekGlobalSniLabel: String = "Global SNI"
    override val geekCloudflareIpLabel: String = "Cloudflare Clean IP"
    override val geekCustomUaLabel: String = "Custom User-Agent"
    override val geekLogFilterPlaceholder: String = "Core log filter (search by keyword)…"
    override val catScannerCore: String = "Scanner & Core"
    override val catScannerCoreDesc: String = "SmartConnect distribution settings and native v2w-core scanner parameters"
    override val catProfileCheck: String = "Verification"
    override val catProfileCheckDesc: String = "Smart profile validation before connect, service availability, and speed testing"
    override val catFailover: String = "Failover"
    override val catFailoverDesc: String = "Automatic server switching on failure, traffic-based Smart Failover, and network health checks"
    override val catUpdates: String = "Updates"
    override val catUpdatesDesc: String = "Background subscription updates and ProBel application release checks on GitHub"
    override val catRouting: String = "Routing"
    override val catRoutingDesc: String = "Direct national routing bypass, domain split-tunneling, and proxy chaining (endpoint)"
    override val catPersonalServers: String = "Trusted Servers"
    override val catPersonalServersDesc: String = "Custom VPS nodes, private subscriptions, auto-search and exit node management"
    override val catVpnCore: String = "VPN & Core"
    override val catVpnCoreDesc: String = "VPN networking, local SOCKS5 proxy, DNS resolution, Mux, and TCP/TLS fragmentation"
    override val catBackupData: String = "System & Data"
    override val catBackupDataDesc: String = "System preferences, tray minimization, and resetting app configuration"
    override val cardSmartConnect: String = "SmartConnect Server Distribution"
    override val cardSmartConnectDesc: String = "Batch size and allocation presets for candidate servers"
    override val smartBatchLabel: String = "Batch size of servers (default 20)"
    override val smartPresetLabel: String = "Default distribution preset:"
    override val presetEqual: String = "Equal"
    override val presetRandom: String = "Random"
    override val presetMore: String = "More"
    override val presetLess: String = "Less"
    override val cardV2wCore: String = "v2w-core Engine & Scanner"
    override val cardV2wCoreDesc: String = "High-performance native profile probing and Xray runner"
    override val v2wEnable: String = "Use v2w-core engine"
    override val v2wEnableDesc: String = "Ultra-fast native probing through a dedicated background process"
    override val v2wFallback: String = "Fallback to legacy scanner"
    override val v2wFallbackDesc: String = "Fallback probing mechanism if the native binary encounters errors"
    override val v2wSocketsLabel: String = "v2w-core socket concurrency limit"
    override val xrayPathLabel: String = "Xray binary path (leave empty for auto-detection)"
    override val v2wPathLabel: String = "v2w-scan binary path (leave empty for auto-detection)"
    override val cardProfileCheck: String = "Smart Profile Validation"
    override val cardProfileCheckDesc: String = "Pre-flight validation before initiating connection"
    override val testBeforeConnect: String = "Test profile before connecting"
    override val testBeforeConnectDesc: String = "Filters out unresponsive servers before switching"
    override val removeInsecure: String = "Remove insecure profiles"
    override val removeInsecureDesc: String = "Automatically discards nodes with vulnerable cipher suites"
    override val serviceCheckEnable: String = "Service availability check"
    override val serviceCheckEnableDesc: String = "Checks Google, YouTube, Telegram, WhatsApp, Instagram instead of basic 204 ping"
    override val vipLimitLabel: String = "Fast server VIP cache limit"
    override val cardSpeedTest: String = "Speed Test on Validation"
    override val cardSpeedTestDesc: String = "Measure actual throughput and download bandwidth"
    override val speedTestEnable: String = "Enable speed testing during check"
    override val speedTestEnableDesc: String = "Downloads a small payload chunk to determine true Mbps"
    override val speedTestBytesLabel: String = "Test download payload size (bytes)"
    override val speedTestTimeoutLabel: String = "Speed test timeout (ms)"
    override val minSpeedThresholdLabel: String = "Minimum speed threshold (Mbps)"
    override val cardFailover: String = "Smart Failover (Auto-switching)"
    override val cardFailoverDesc: String = "Protection against abrupt node downtime and active DPI blocking"
    override val autoFailoverEnable: String = "Enable auto server failover"
    override val autoFailoverEnableDesc: String = "Automatically switches to an active candidate upon failure"
    override val heartbeatLabel: String = "Heartbeat probe interval (seconds)"
    override val smartFailoverEnable: String = "Traffic-aware Smart Failover"
    override val smartFailoverEnableDesc: String = "Triggers when bandwidth drops abruptly due to DPI throttling"
    override val failoverDegradationLabel: String = "Traffic degradation threshold (KB)"
    override val failoverStuckLabel: String = "Stuck duration before failover (seconds)"
    override val cardNetCheck: String = "Network Health Check"
    override val cardNetCheckDesc: String = "Global internet reachability probing"
    override val netCheckEnable: String = "Enable network health checks"
    override val netCheckEnableDesc: String = "Pings designated domains to verify internet presence"
    override val primaryDomainLabel: String = "Primary probe domain (e.g. google.com)"
    override val fallbackDomainLabel: String = "Fallback probe domain (e.g. cloudflare.com)"
    override val connCheckUrlLabel: String = "VPN connection check URL"
    override val cardSubUpdate: String = "Background Subscription Updates"
    override val cardSubUpdateDesc: String = "Scheduled automatic retrieval of new node links"
    override val subAutoUpdateEnable: String = "Automatic subscription update"
    override val subAutoUpdateEnableDesc: String = "Refreshes server list periodically in the background"
    override val subIntervalLabel: String = "Update interval (minutes)"
    override val subOnStartEnable: String = "Update subscriptions on app launch"
    override val subOnStartEnableDesc: String = "Pulls fresh node configurations whenever the app opens"
    override val subOnlyProxyEnable: String = "Update only via VPN/Proxy"
    override val subOnlyProxyEnableDesc: String = "Bypasses local ISP blocks on raw subscription hosts"
    override val subThreadsLabel: String = "Parallel subscription download threads"
    override val yandexTranslateEnable: String = "Bypass Cloudflare CAPTCHA via Yandex"
    override val yandexTranslateEnableDesc: String = "Retrieves subscription text protected by aggressive anti-bot screens"
    override val userAgentLabel: String = "Custom Subscription User-Agent"
    override val cardAppUpdates: String = "Application Updates"
    override val cardAppUpdatesDesc: String = "Release tracking and automatic notifications"
    override val autoCheckUpdates: String = "Automatically check for updates"
    override val autoCheckUpdatesDesc: String = "Notifies when a new ProBel desktop release is published"
    override val betaUpdates: String = "Receive pre-release/beta updates"
    override val betaUpdatesDesc: String = "Early access to experimental features and improvements"
    override val cardRoutingBypass: String = "Routing Rules & Proxy Chaining"
    override val cardRoutingBypassDesc: String = "Direct local service access and multi-hop outbound relaying"
    override val bypassRuEnable: String = "Direct access to national services (Bypass RU)"
    override val bypassRuEnableDesc: String = "Public services, online banking, and local sites route directly without VPN"
    override val customEndpointEnable: String = "Use Custom Endpoint (Proxy Chaining)"
    override val customEndpointEnableDesc: String = "Routes traffic in a multi-hop chain: Selected Server -> Custom Endpoint"
    override val endpointConfigLabel: String = "Endpoint configuration (URI or JSON)"
    override val endpointSubUrlLabel: String = "Subscription URL for endpoint selection"
    override val endpointSelectServer: String = "Select server from subscription"
    override val endpointSelectServerTitle: String = "Select Endpoint Node from Subscription"
    override val endpointE2eEnable: String = "End-to-End Chain Validation (E2E)"
    override val endpointE2eEnableDesc: String = "Verifies external internet reachability through the full relay chain"
    override val cardSplitTunneling: String = "Domain-based Split Tunneling"
    override val cardSplitTunnelingDesc: String = "Custom rules for forced direct routing or forced proxy tunnels"
    override val domainSplitEnable: String = "Enable domain split tunneling"
    override val domainSplitEnableDesc: String = "Routes requests based on specific domain matching rules"
    override val domainWhitelistMode: String = "Whitelist Mode (only listed domains go through proxy)"
    override val domainBlacklistMode: String = "Blacklist Mode (all in proxy except listed domains)"
    override val directDomainsLabel: String = "Direct domains (comma-separated)"
    override val proxyDomainsLabel: String = "Proxy domains (comma-separated)"
    override val cardVpnDns: String = "VPN & DNS Configuration"
    override val cardVpnDnsDesc: String = "Virtual network adapter settings and upstream DNS resolvers"
    override val preferIpv6: String = "Prefer IPv6 connections"
    override val preferIpv6Desc: String = "Resolve and establish IPv6 routes whenever available"
    override val localDns: String = "Use local system DNS"
    override val localDnsDesc: String = "Resolve domain names using primary OS network interface"
    override val fakeDns: String = "Enable FakeDNS"
    override val fakeDnsDesc: String = "Mitigates DNS poisoning and accelerates domain dispatching"
    override val additionalHttpProxy: String = "Additional local HTTP proxy inbound"
    override val additionalHttpProxyDesc: String = "Exposes an HTTP proxy endpoint alongside native SOCKS5"
    override val vpnDnsLabel: String = "VPN upstream DNS servers (comma-separated)"
    override val vpnBypassLan: String = "Bypass local subnet traffic (Bypass LAN)"
    override val vpnBypassLanDesc: String = "Direct access to home routers, printers, and LAN devices"
    override val vpnAddressLabel: String = "VPN interface IP address"
    override val vpnMtuLabel: String = "VPN MTU packet size"
    override val cardCoreSocks: String = "Core Engine & SOCKS5 Settings"
    override val cardCoreSocksDesc: String = "Low-level protocol sniffing and inbound proxy bindings"
    override val sniffingEnable: String = "Enable protocol sniffing"
    override val sniffingEnableDesc: String = "Inspects metadata to discover true domains inside TLS packets"
    override val routeOnlyEnable: String = "Route Only mode"
    override val routeOnlyEnableDesc: String = "Use sniffed destination solely for routing rules without rewrites"
    override val allowLanEnable: String = "Allow inbound connections from LAN"
    override val allowLanEnableDesc: String = "Binds local inbound ports to 0.0.0.0 for home network sharing"
    override val allowInsecureEnable: String = "Allow insecure TLS certificates"
    override val allowInsecureEnableDesc: String = "Skips certificate chain validation (Use with caution)"
    override val localProxyPortLabel: String = "Local SOCKS5 proxy inbound port"
    override val localProxyLoginLabel: String = "Local proxy username authentication"
    override val localProxyPasswordLabel: String = "Local proxy password authentication"
    override val remoteDnsLabel: String = "Remote secure DNS resolver"
    override val internalDnsLabel: String = "Internal direct DNS resolver"
    override val dnsHostsLabel: String = "Static DNS hosts map (IP domain, ...)"
    override val cardMux: String = "Multiplexing (Mux)"
    override val cardMuxDesc: String = "Multiplexes multiple logical streams over a single TCP connection"
    override val muxEnable: String = "Enable TCP Multiplexing (Mux)"
    override val muxEnableDesc: String = "Reduces connection setup overhead and TLS handshakes"
    override val muxTcpLabel: String = "Max TCP Mux concurrent streams"
    override val muxXudpLabel: String = "Max xUDP Mux concurrent streams"
    override val cardFragmentation: String = "Packet Fragmentation (Anti-DPI)"
    override val cardFragmentationDesc: String = "Splits the TLS Client Hello handshake to evade deep packet inspection"
    override val fragmentEnable: String = "Enable packet fragmentation"
    override val fragmentEnableDesc: String = "Fragments initial packets to prevent SNI fingerprinting"
    override val fragmentLengthLabel: String = "Fragment length range (bytes, e.g. 10-50)"
    override val fragmentIntervalLabel: String = "Fragment dispatch interval (ms, e.g. 10-20)"
    override val fragmentPacketsLabel: String = "Targeted packet filter (e.g. 1-3 or tlshello)"
    override val cardSystemTray: String = "System & Tray Preferences"
    override val cardSystemTrayDesc: String = "Window minimize behavior and OS desktop tray integration"
    override val minimizeToTrayEnable: String = "Minimize to system tray on window close"
    override val minimizeToTrayEnableDesc: String = "App continues protecting connections in the background"
    override val cardReset: String = "Reset Settings & Data"
    override val cardResetDesc: String = "Restore all parameters to factory defaults and clear cache"
    override val resetAllSettings: String = "Reset all settings to defaults"
    override val resetDialogTitle: String = "Reset All Configuration"
    override val resetDialogConfirm: String = "Are you sure you want to reset all settings to defaults? Your subscriptions will remain intact."
    override val subStatusActual: String = "Up to date"
    override val subStatusOutdated: String = "Outdated"
    override val subStatusFailed: String = "Update failed"
    override val error: String = "Error"
    override val langSystem: String = "System Default"
    override val resetSuccess: String = "Settings successfully reset to defaults!"
    override val statusConnecting: String = "Connecting..."
    override val sudoTitle: String = "TUN Mode Activation"
    override val sudoDesc: String = "TUN mode (system-wide traffic redirection) on Linux requires superuser privileges (sudo).\n\nThe password is entered once and stored only in RAM until the application closes."
    override val sudoPasswordLabel: String = "sudo password"
    override val sudoPasswordPlaceholder: String = "Enter superuser password..."
    override val sudoShowPassword: String = "Show password"
    override val sudoHidePassword: String = "Hide password"
    override val sudoVerifying: String = "Verifying sudo permissions..."
    override val sudoConfirm: String = "Confirm"
    override val sudoCancel: String = "Cancel"
    override val sudoEmptyError: String = "Password cannot be empty"
    override val sudoInvalidError: String = "Incorrect sudo password. Please try again."
    override val unsavedTitle: String = "Unsaved Changes"
    override val unsavedDesc: String = "You have unsaved changes in settings. Would you like to save them before switching tabs?"
    override val unsavedSave: String = "Save"
    override val unsavedDiscard: String = "Discard"
    override val unsavedCancel: String = "Cancel"
    override val updateDialogTitle: String = "Update Available"
    override val updateCurrentVersion: String = "Current version"
    override val updateNewVersion: String = "New version"
    override val updateChangelog: String = "What's new:"
    override val updateFile: String = "File"
    override val updateDownload: String = "Download Update"
    override val updateViewRelease: String = "View Release"
    override val updateAllReleases: String = "All Releases"
    override val updateLater: String = "Later"
    override val trayOpen: String = "Open ProBel"
    override val trayDisconnect: String = "⏹ Disconnect VPN"
    override val trayConnecting: String = "⏳ Connecting..."
    override val trayRetry: String = "🔄 Retry Connection"
    override val trayConnect: String = "▶ Connect VPN"
    override val traySwitchToProxy: String = "Mode: TUN → Switch to Proxy"
    override val traySwitchToTun: String = "Mode: Proxy → Switch to TUN"
    override val traySystemProxyOn: String = "✔ System Proxy: ON (click to disable)"
    override val traySystemProxyOff: String = "System Proxy: OFF (click to enable)"
    override val trayExit: String = "Exit"
}

object StringsZh : AppStrings {
    override val appName: String = "ПроБел"
    override val appTitle: String = "ПроБел — v2whitelist"
    override val menu: String = "菜单"
    override val subscriptions: String = "订阅设置"
    override val geekMode: String = "极客模式"
    override val appearance: String = "外观设置"
    override val settings: String = "设置"
    override val exit: String = "退出"
    override val save: String = "保存"
    override val saved: String = "设置保存成功！"
    override val cancel: String = "取消"
    override val done: String = "完成"
    override val delete: String = "删除"
    override val edit: String = "编辑"
    override val retry: String = "重试"
    override val copy: String = "复制"
    override val clear: String = "清空"
    override val close: String = "关闭"
    override val source: String = "来源: "
    override val status: String = "状态"
    override val protocol: String = "协议: "
    override val address: String = "地址: "
    override val unknown: String = "未知"
    override val all: String = "全部"
    override val statusConnected: String = "已连接"
    override val statusTesting: String = "正在测试连接…"
    override val statusError: String = "连接错误"
    override val statusDisconnected: String = "未连接"
    override val statusTestingServers: String = "正在测试服务器…"
    override val manualSelectionPrefix: String = "手动选择: "
    override val tunnelActive: String = "安全隧道已激活"
    override val pressStartToConnect: String = "点击启动以安全连接"
    override val btnStart: String = "启动"
    override val btnStop: String = "停止"
    override val btnRetry: String = "重试"
    override val btnCancel: String = "取消"
    override val btnSwitchServer: String = "切换节点"
    override val btnSwitchingServer: String = "正在切换节点…"
    override val routingModeTitle: String = "工作模式: "
    override val routingModeTun: String = "TUN (虚拟网卡)"
    override val routingModeProxy: String = "代理 (SOCKS5 / HTTP)"
    override val routingModeTunDesc: String = "所有系统流量均通过 TUN 隧道分流"
    override val routingModeProxyDesc: String = "系统代理运行于 127.0.0.1:10808 / 10809"
    override val tunEnabled: String = "TUN 模式已启用"
    override val proxyEnabled: String = "代理模式已启用"
    override val scenarioManual: String = "分流场景: 手动选择节点"
    override val scenarioAuto: String = "分流场景: 自动优选"
    override val scenarioAutoTitle: String = "自动"
    override val scenarioAutoSubtitle: String = "白名单与黑名单均衡"
    override val scenarioWhitelistTitle: String = "白名单"
    override val scenarioWhitelistSubtitle: String = "仅直连本国流量"
    override val scenarioBlacklistTitle: String = "黑名单"
    override val scenarioBlacklistSubtitle: String = "绕过受限站点"
    override val scenarioYoutubeTitle: String = "YouTube"
    override val scenarioYoutubeSubtitle: String = "YouTube 及流媒体"
    override val showServerList: String = "展开服务器列表"
    override val hideServerList: String = "折叠服务器列表"
    override val searchNodesPlaceholder: String = "按别名或主机搜索节点…"
    override val noServersFoundCriteria: String = "没有符合条件的服务器"
    override val updateAvailableTitle: String = "发现 ПроБел 新版本: v"
    override val updateAvailableSubtitle: String = "点击“更新”查看更新日志或下载"
    override val btnUpdate: String = "更新"
    override val btnDismiss: String = "忽略"
    override val noSubsText: String = "无订阅"
    override val subFailureBanner: String = "更新失败: "
    override val qrShowCode: String = "显示节点二维码"
    override val noActiveServer: String = "无活动服务器"
    override val pingAsc: String = "⚡ 延迟 ▲"
    override val pingTest: String = "真连接延迟测试"
    override val ping: String = "延迟"
    override val findVip: String = "查找 VIP 节点"
    override val pingVip: String = "测试 VIP 延迟"
    override val dpiBypass: String = "DPI 绕过 (抗审查伪装参数):"
    override val copyLogs: String = "复制日志"
    override val addSub: String = "添加订阅"
    override val updateAll: String = "更新全部"
    override val editSub: String = "编辑"
    override val deleteSub: String = "删除"
    override val theme: String = "界面主题"
    override val language: String = "应用语言"
    override val turnOnVpn: String = "▶ 开启 VPN"
    override val turnOffVpn: String = "⏹ 停止 VPN"
    override val systemProxyOs: String = "系统代理"
    override val quickSwitch: String = "快捷切换"
    override val routingModeCardTitle: String = "工作模式 (代理 / TUN)"
    override val serversNotFound: String = "未找到服务器"
    override val searchServers: String = "搜索服务器..."
    override val serverList: String = "服务器列表"
    override val locationsFilter: String = "国家/地区过滤"
    override val locationsFilterExclude: String = "排除选中地区"
    override val locationsFilterWhitelist: String = "仅限选中地区"
    override val locationsFilterDisabled: String = "已禁用"
    override val proxyStandaloneTitle: String = "纯代理模式 (独立代理)"
    override val proxyStandaloneDescLan: String = "免 TUN 运行：本地端口已向局域网 (LAN) 开放"
    override val proxyStandaloneDescNoRoot: String = "免 TUN 运行，无需 Root/管理员权限"
    override val proxySystemProxyOn: String = "系统代理: 开启"
    override val proxySystemProxyOff: String = "系统代理: 关闭"
    override val proxyQuickExportTitle: String = "一键配置应用程序代理:"
    override val proxyTgLinkCopied: String = "Telegram 代理链接"
    override val proxyTerminalCopied: String = "终端环境变量命令"
    override val proxyPsCopied: String = "PowerShell 代理命令"
    override val locFilterTitle: String = "国家与地区过滤"
    override val locFilterDesc: String = "用于 SmartConnect 智能节点优选和自动分流模式。"
    override val locExcludeRadio: String = "排除选中地区"
    override val locExcludeDesc: String = "黑名单地区（跳过所选国家的服务器）"
    override val locWhitelistRadio: String = "仅限选中地区"
    override val locWhitelistDesc: String = "白名单地区（仅连接已勾选的国家）"
    override val locDisabledRadio: String = "禁用过滤"
    override val locDisabledDesc: String = "使用所有可用国家/地区的服务器"
    override val locResetAll: String = "重置选择"
    override val servCheckTitle: String = "网络服务可用性测试"
    override val servCheckDesc: String = "选择需要测试的互联网服务。若任一已选服务不可达，该节点将被判定不合格并剔除。"
    override val servCheckEndpointWarning: String = "注意：启用链式代理端点时，服务检查将被链式端到端测试替代。"
    override val servCustomBadge: String = "自定义"
    override val servAddBtn: String = "+ 添加服务"
    override val servDefaultBtn: String = "恢复默认"
    override val servAddDialogTitle: String = "添加待测服务"
    override val servAddNameLabel: String = "服务名称 (如 GitHub)"
    override val servAddUrlLabel: String = "目标网址 (如 https://github.com)"
    override val servAddErrorEmpty: String = "请填写所有必填字段"
    override val qrDialogTitle: String = "当前节点二维码"
    override val qrCopyLink: String = "复制分享链接"
    override val qrFailGenerate: String = "二维码生成失败"
    override val appearanceTitle: String = "外观设置"
    override val appearanceSubtitle: String = "个性化定制主题色彩、暗黑模式、AMOLED 纯黑以及动态流光背景"
    override val themeCardTitle: String = "界面主题"
    override val themeCardDesc: String = "选择应用显示模式："
    override val themeSystem: String = "跟随系统 (自动)"
    override val themeLight: String = "浅色"
    override val themeDark: String = "深色"
    override val paletteCardTitle: String = "主题配色方案"
    override val paletteCardDesc: String = "设置交互控件、按钮及高亮元素的强调色："
    override val amoledCardTitle: String = "AMOLED 纯黑"
    override val amoledSwitchTitle: String = "启用 #000000 极致纯黑背景"
    override val amoledSwitchDesc: String = "在暗黑模式下为 OLED/AMOLED 屏幕提供极致黑背景，深度省电"
    override val languageCardTitle: String = "应用语言"
    override val languageCardDesc: String = "选择应用显示语言："
    override val meshCardTitle: String = "流光渐变背景 (Mesh)"
    override val meshSwitchTitle: String = "柔和流光动画渐变背景"
    override val meshSwitchDesc: String = "默认关闭。开启后提供现代化灵动视觉沉浸感。"
    override val meshModeLabel: String = "流光渐变渲染模式："
    override val meshModeStatic: String = "静态柔和"
    override val meshModeBreathing: String = "呼吸律动 (平滑过渡)"
    override val meshModeReactive: String = "灵动响应 (启动聚拢)"
    override val subManageTitle: String = "订阅管理"
    override val subManageSubtitle: String = "集中管理订阅源、备用镜像及场景分流规则"
    override val subAddBtn: String = "添加"
    override val subUpdateAllBtn: String = "更新全部"
    override val subUpdateSingleBtn: String = "更新订阅"
    override val personalRulesHeader: String = "专属服务器场景规则"
    override val personalRulesHeaderDesc: String = "配置在不同场景下使用专属节点还是公共订阅"
    override val personalBlacklistRuleTitle: String = "黑名单 / YouTube 模式 (开放网络)"
    override val personalBlacklistRuleDesc: String = "开放网络可用时的连接策略"
    override val personalWhitelistRuleTitle: String = "白名单模式 (白名单限制 / 干扰)"
    override val personalWhitelistRuleDesc: String = "检测到白名单或网络干扰时的连接策略"
    override val personalRuleAuto: String = "⚡ 自动选择 (专属)"
    override val personalRuleSelected: String = "🎯 已选服务器"
    override val personalRulePublic: String = "🌐 ProBel 公共订阅"
    override val personalRuleDirect: String = "🚫 直连 (无 VPN)"
    override val subUpdatingBtn: String = "更新中…"
    override val subFailBannerTitle: String = "部分订阅更新失败"
    override val subRetryBtn: String = "重试"
    override val subEmptyText: String = "尚未添加任何订阅。请点击“添加”配置节点源。"
    override val subMirrorsCount: String = "镜像数: "
    override val subServersCount: String = "节点数: "
    override val subSharePercent: String = "配额比例: "
    override val subDialogAddTitle: String = "添加订阅"
    override val subDialogEditTitle: String = "编辑订阅"
    override val subNameLabel: String = "订阅名称"
    override val subUrlLabel: String = "订阅链接 (多个镜像使用 | 分隔)"
    override val subFilterLabel: String = "Regex 正则过滤 (导入前剔除匹配节点)"
    override val subGroupRegexLabel: String = "分组正则表达式"
    override val subSharePercentLabel: String = "服务器配额占比 (%)"
    override val subScenariosLabel: String = "自动模式匹配场景："
    override val subCatWhitelist: String = "白名单"
    override val subCatBlacklist: String = "黑名单"
    override val subCatYoutube: String = "YouTube 及音乐"
    override val geekConsoleTitle: String = "极客模式 — 工程调试控制台"
    override val geekEvents: String = "条日志"
    override val geekCopyLogs: String = "复制"
    override val geekClearLogs: String = "清空"
    override val geekNoServerSelected: String = "未选择服务器"
    override val geekProtocolLabel: String = "协议: "
    override val geekAddressLabel: String = "地址: "
    override val geekPingLabel: String = "延迟: "
    override val geekExitIpLabel: String = "出口 IP:"
    override val geekExitIpUndefined: String = "未获取"
    override val geekPingTestBtn: String = "延迟测速"
    override val geekFindVipBtn: String = "查找 VIP"
    override val geekPingVipBtn: String = "VIP 延迟测速"
    override val geekCheckServicesBtn: String = "测试服务可用性"
    override val geekCheckingServicesBtn: String = "并发检查服务可用性中…"
    override val geekTuneServicesDesc: String = "管理测试服务"
    override val geekServicesAvailabilityTitle: String = "网络服务可用性："
    override val geekServicesAvailableCount: String = "可用"
    override val geekVipCacheTitle: String = "VIP 快速节点缓存"
    override val geekStopVipBtn: String = "⏹ 停止 VIP"
    override val geekRemoveVip: String = "从 VIP 移除"
    override val geekDpiBypassTitle: String = "DPI 绕过 (抗审查伪装参数配置):"
    override val geekGlobalSniLabel: String = "全局 SNI 伪装"
    override val geekCloudflareIpLabel: String = "Cloudflare 优选 IP"
    override val geekCustomUaLabel: String = "自定义 User-Agent"
    override val geekLogFilterPlaceholder: String = "核心日志过滤 (按关键词搜索)…"
    override val catScannerCore: String = "扫描器与核心"
    override val catScannerCoreDesc: String = "SmartConnect 节点分流设置及 v2w-core 原生核心扫描参数"
    override val catProfileCheck: String = "节点验证"
    override val catProfileCheckDesc: String = "连接前智能检测、网络服务可用性校验及下载测速"
    override val catFailover: String = "故障转移"
    override val catFailoverDesc: String = "连接中断自动故障转移、基于流量的智能容灾及网络连通性监控"
    override val catUpdates: String = "自动更新"
    override val catUpdatesDesc: String = "后台订阅自动同步及 GitHub 新版本检测"
    override val catRouting: String = "路由与分流"
    override val catRoutingDesc: String = "本国服务直连白名单、域名分流黑白名单及链式出口节点"
    override val catPersonalServers: String = "个人服务器"
    override val catPersonalServersDesc: String = "自建 VPS 节点、私有订阅、自动优选及出口节点管理"
    override val catVpnCore: String = "VPN 与核心"
    override val catVpnCoreDesc: String = "VPN 虚拟网卡、本地 SOCKS5/HTTP 代理、DNS 解析、多路复用及分片抗审查"
    override val catBackupData: String = "系统与重置"
    override val catBackupDataDesc: String = "系统托盘运行参数及应用出厂配置重置"
    override val cardSmartConnect: String = "SmartConnect 节点分流"
    override val cardSmartConnectDesc: String = "候选节点分批测速及分流策略配置"
    override val smartBatchLabel: String = "每批次测试节点数 (默认 20)"
    override val smartPresetLabel: String = "默认分流分配预设："
    override val presetEqual: String = "等量分配"
    override val presetRandom: String = "随机分配"
    override val presetMore: String = "偏多"
    override val presetLess: String = "偏少"
    override val cardV2wCore: String = "v2w-core 核心与扫描器"
    override val cardV2wCoreDesc: String = "基于 Go 原生的高性能节点并发扫描及 Xray 进程守护"
    override val v2wEnable: String = "使用 v2w-core 核心"
    override val v2wEnableDesc: String = "通过独立守护进程进行毫秒级并发测试"
    override val v2wFallback: String = "回退到旧版扫描器"
    override val v2wFallbackDesc: String = "原生核心异常时自动调用内置备用扫描器"
    override val v2wSocketsLabel: String = "v2w-core 套接字并发上限"
    override val xrayPathLabel: String = "Xray 可执行文件路径 (留空自动探测)"
    override val v2wPathLabel: String = "v2w-scan 可执行文件路径 (留空自动探测)"
    override val cardProfileCheck: String = "节点智能校验"
    override val cardProfileCheckDesc: String = "连接建立前的快速有效性检测"
    override val testBeforeConnect: String = "连接前测试节点有效性"
    override val testBeforeConnectDesc: String = "自动剔除失效节点，保障网络无缝切换"
    override val removeInsecure: String = "自动过滤不安全节点"
    override val removeInsecureDesc: String = "自动移除存在安全漏洞或明文传输的节点"
    override val serviceCheckEnable: String = "网络服务可用性校验"
    override val serviceCheckEnableDesc: String = "通过 Google、YouTube、Telegram、WhatsApp、Instagram 进行真实可用性检测"
    override val vipLimitLabel: String = "VIP 极速节点缓存容量上限"
    override val cardSpeedTest: String = "节点连通测速"
    override val cardSpeedTestDesc: String = "测量节点的实际下载吞吐速率"
    override val speedTestEnable: String = "校验时启用实际测速"
    override val speedTestEnableDesc: String = "下载微小测试块以计算真实 Mbps 带宽"
    override val speedTestBytesLabel: String = "测速下载数据块大小 (字节)"
    override val speedTestTimeoutLabel: String = "测速超时时间 (毫秒)"
    override val minSpeedThresholdLabel: String = "最低合格速率门槛 (Mbps)"
    override val cardFailover: String = "Smart Failover 自动故障切换"
    override val cardFailoverDesc: String = "防止节点突发离线及 DPI 动态封锁的平滑容灾机制"
    override val autoFailoverEnable: String = "开启节点故障自动转移"
    override val autoFailoverEnableDesc: String = "检测到连接失效时自动无感切换至可用备选节点"
    override val heartbeatLabel: String = "心跳检测周期 (秒)"
    override val smartFailoverEnable: String = "基于流量速率的智能容灾"
    override val smartFailoverEnableDesc: String = "当遭遇运营商 DPI 限速或丢包激增时立即触发重选"
    override val failoverDegradationLabel: String = "流量衰减判定阈值 (KB)"
    override val failoverStuckLabel: String = "流量归零触发切换时长 (秒)"
    override val cardNetCheck: String = "网络连通性状态监测"
    override val cardNetCheckDesc: String = "全局互联网通达度心跳监测"
    override val netCheckEnable: String = "启用网络连通性监测"
    override val netCheckEnableDesc: String = "定期探测指定域名以确认设备具备外部连接"
    override val primaryDomainLabel: String = "主探测域名 (如 google.com)"
    override val fallbackDomainLabel: String = "备用探测域名 (如 cloudflare.com)"
    override val connCheckUrlLabel: String = "VPN 连接有效性测试 URL"
    override val cardSubUpdate: String = "后台订阅自动同步"
    override val cardSubUpdateDesc: String = "按计划定时拉取并刷新最新节点配置"
    override val subAutoUpdateEnable: String = "自动同步订阅列表"
    override val subAutoUpdateEnableDesc: String = "在后台定期更新服务器节点列表"
    override val subIntervalLabel: String = "更新周期 (分钟)"
    override val subOnStartEnable: String = "应用启动时立即同步"
    override val subOnStartEnableDesc: String = "每次打开程序自动拉取最新节点列表"
    override val subOnlyProxyEnable: String = "仅通过 VPN/代理更新"
    override val subOnlyProxyEnableDesc: String = "防止运营商阻断订阅服务器的直连请求"
    override val subThreadsLabel: String = "订阅多线程并发拉取数"
    override val yandexTranslateEnable: String = "通过 Yandex 接口绕过 Cloudflare 人机验证"
    override val yandexTranslateEnableDesc: String = "成功下载受到严苛抗爬虫/人机验证防护的订阅源"
    override val userAgentLabel: String = "订阅请求自定义 User-Agent"
    override val cardAppUpdates: String = "程序版本与更新"
    override val cardAppUpdatesDesc: String = "检查 GitHub Release 发布的最新版本"
    override val autoCheckUpdates: String = "自动检查新版本"
    override val autoCheckUpdatesDesc: String = "发现 ПроБел 客户端更新时桌面弹窗提示"
    override val betaUpdates: String = "接收测试版 (Beta) 更新"
    override val betaUpdatesDesc: String = "抢先体验全新功能、实验性特性及性能调优"
    override val cardRoutingBypass: String = "分流规则与链式代理"
    override val cardRoutingBypassDesc: String = "本国常用服务直连白名单及多跳出口中继配置"
    override val bypassRuEnable: String = "直连本国服务 (Bypass RU)"
    override val bypassRuEnableDesc: String = "政务网站、银行网银、本土电商等不走代理直连访问"
    override val customEndpointEnable: String = "启用链式出口节点 (Proxy Chaining)"
    override val customEndpointEnableDesc: String = "建立多跳转发链路：优选中转节点 -> 最终落地节点"
    override val endpointConfigLabel: String = "落地节点配置 (URI 链接或 JSON)"
    override val endpointSubUrlLabel: String = "落地节点专属订阅源 URL"
    override val endpointSelectServer: String = "从订阅列表中选择出口节点"
    override val endpointSelectServerTitle: String = "从订阅中选择最终落地节点"
    override val endpointE2eEnable: String = "端到端全链路连通性检测 (E2E)"
    override val endpointE2eEnableDesc: String = "连接建立前确保中转与落地双节点均畅通无阻"
    override val cardSplitTunneling: String = "基于域名的智能分流"
    override val cardSplitTunnelingDesc: String = "自定义域名的强制直连或强制代理转发规则"
    override val domainSplitEnable: String = "启用自定义域名分流"
    override val domainSplitEnableDesc: String = "根据预设域名列表精确分流网络请求"
    override val domainWhitelistMode: String = "白名单模式 (仅匹配域名走代理)"
    override val domainBlacklistMode: String = "黑名单模式 (仅匹配域名直连，其余走代理)"
    override val directDomainsLabel: String = "强制直连域名列表 (逗号分隔)"
    override val proxyDomainsLabel: String = "强制代理域名列表 (逗号分隔)"
    override val cardVpnDns: String = "VPN 网卡与 DNS 深度配置"
    override val cardVpnDnsDesc: String = "虚拟网络适配器底层网络栈及上游 DNS 解析服务"
    override val preferIpv6: String = "优先发起 IPv6 连接"
    override val preferIpv6Desc: String = "当目标域名存在 AAAA 记录时优先使用 IPv6 通道"
    override val localDns: String = "使用本地系统内置 DNS"
    override val localDnsDesc: String = "优先通过本地区域网络接口解析域名"
    override val fakeDns: String = "开启 FakeDNS 虚拟域名映射"
    override val fakeDnsDesc: String = "杜绝 DNS 污染劫持并实现极速秒开域名"
    override val additionalHttpProxy: String = "开启辅助 HTTP 本地代理端口"
    override val additionalHttpProxyDesc: String = "在 SOCKS5 端口旁额外监听 HTTP 代理端口"
    override val vpnDnsLabel: String = "VPN 上游 DNS 服务器 (英文逗号分隔)"
    override val vpnBypassLan: String = "绕过局域网私有网段 (Bypass LAN)"
    override val vpnBypassLanDesc: String = "局域网路由器、打印机及内网设备不走 VPN 直连"
    override val vpnAddressLabel: String = "VPN 虚拟网卡分配的 IPv4 地址"
    override val vpnMtuLabel: String = "VPN 虚拟网卡最大传输单元 (MTU)"
    override val cardCoreSocks: String = "核心引擎与 SOCKS5 代理"
    override val cardCoreSocksDesc: String = "底层协议嗅探、入站绑定及出站路由策略"
    override val sniffingEnable: String = "开启协议嗅探识别 (Sniffing)"
    override val sniffingEnableDesc: String = "智能识别 TLS/HTTP 流量中的真实目标域名以精准分流"
    override val routeOnlyEnable: String = "仅路由转发模式 (Route Only)"
    override val routeOnlyEnableDesc: String = "嗅探到的域名仅用于规则匹配，不改写原始连接目标"
    override val allowLanEnable: String = "允许局域网其他设备连接 (Allow LAN)"
    override val allowLanEnableDesc: String = "将本地代理端口绑定至 0.0.0.0，供内网手机/电视共享"
    override val allowInsecureEnable: String = "允许不安全的 TLS 证书连接"
    override val allowInsecureEnableDesc: String = "跳过服务器证书权威性校验 (存在安全风险，谨慎使用)"
    override val localProxyPortLabel: String = "本地 SOCKS5 监听端口"
    override val localProxyLoginLabel: String = "本地代理身份认证用户名"
    override val localProxyPasswordLabel: String = "本地代理身份认证密码"
    override val remoteDnsLabel: String = "远端安全 DNS (Remote DNS)"
    override val internalDnsLabel: String = "国内直连 DNS (Direct DNS)"
    override val dnsHostsLabel: String = "静态 DNS 主机映射条目 (IP domain, ...)"
    override val cardMux: String = "TCP 多路复用 (Mux)"
    override val cardMuxDesc: String = "将多个并发 TCP 请求聚合至单一长连接，极大降低握手延迟"
    override val muxEnable: String = "开启 TCP 多路复用 (Mux)"
    override val muxEnableDesc: String = "大幅缩减高频小请求的握手开销"
    override val muxTcpLabel: String = "TCP Mux 最大并发多路复用连接数"
    override val muxXudpLabel: String = "xUDP Mux 最大并发流数量"
    override val cardFragmentation: String = "TCP/TLS 报文分片 (抗 DPI 审查)"
    override val cardFragmentationDesc: String = "切分 TLS Client Hello 握手首包以绕过运营商 DPI 深度特征探测"
    override val fragmentEnable: String = "启用 TCP 数据包分片绕过"
    override val fragmentEnableDesc: String = "打碎首包以隐藏明文 SNI 握手特征"
    override val fragmentLengthLabel: String = "分片长度区间 (字节，如 10-50)"
    override val fragmentIntervalLabel: String = "分片发送间隔 (毫秒，如 10-20)"
    override val fragmentPacketsLabel: String = "目标分片数据包 (如 1-3 或 tlshello)"
    override val cardSystemTray: String = "系统托盘与常规偏好"
    override val cardSystemTrayDesc: String = "窗口关闭/最小化行为及操作系统托盘栏常驻选项"
    override val minimizeToTrayEnable: String = "点击关闭按钮时最小化至系统托盘"
    override val minimizeToTrayEnableDesc: String = "窗口隐藏后仍会在后台守护网络连接"
    override val cardReset: String = "恢复出厂设置与清理"
    override val cardResetDesc: String = "将所有网络及应用选项还原为出厂默认值"
    override val resetAllSettings: String = "重置所有设置为默认值"
    override val resetDialogTitle: String = "确认重置出厂设置"
    override val resetDialogConfirm: String = "确定要将所有网络参数恢复为默认状态吗？您的自定义订阅配置将被保留。"
    override val subStatusActual: String = "最新"
    override val subStatusOutdated: String = "已过期"
    override val subStatusFailed: String = "同步失败"
    override val error: String = "错误"
    override val langSystem: String = "跟随系统"
    override val resetSuccess: String = "所有配置已成功恢复出厂默认值！"
    override val statusConnecting: String = "正在连接..."
    override val sudoTitle: String = "启用 TUN 模式"
    override val sudoDesc: String = "在 Linux 上使用 TUN 模式（全局系统流量代理）需要超级用户权限 (sudo)。\n\n密码仅需输入一次，并仅保存在内存中，直到程序关闭。"
    override val sudoPasswordLabel: String = "sudo 密码"
    override val sudoPasswordPlaceholder: String = "请输入超级用户密码..."
    override val sudoShowPassword: String = "显示密码"
    override val sudoHidePassword: String = "隐藏密码"
    override val sudoVerifying: String = "正在验证 sudo 权限..."
    override val sudoConfirm: String = "确认"
    override val sudoCancel: String = "取消"
    override val sudoEmptyError: String = "密码不能为空"
    override val sudoInvalidError: String = "sudo 密码错误，请重试。"
    override val unsavedTitle: String = "未保存的更改"
    override val unsavedDesc: String = "设置中存在尚未保存的更改。离开当前页面前是否保存？"
    override val unsavedSave: String = "保存"
    override val unsavedDiscard: String = "放弃更改"
    override val unsavedCancel: String = "取消"
    override val updateDialogTitle: String = "发现新版本"
    override val updateCurrentVersion: String = "当前版本"
    override val updateNewVersion: String = "最新版本"
    override val updateChangelog: String = "更新日志:"
    override val updateFile: String = "文件"
    override val updateDownload: String = "下载更新"
    override val updateViewRelease: String = "查看发布页面"
    override val updateAllReleases: String = "所有版本"
    override val updateLater: String = "稍后"
    override val trayOpen: String = "打开 ПроБел"
    override val trayDisconnect: String = "⏹ 断开 VPN"
    override val trayConnecting: String = "⏳ 正在连接..."
    override val trayRetry: String = "🔄 重新连接"
    override val trayConnect: String = "▶ 连接 VPN"
    override val traySwitchToProxy: String = "模式: TUN → 切换为系统代理"
    override val traySwitchToTun: String = "模式: 系统代理 → 切换为 TUN"
    override val traySystemProxyOn: String = "✔ 系统代理: 已开启 (点击关闭)"
    override val traySystemProxyOff: String = "系统代理: 已关闭 (点击开启)"
    override val trayExit: String = "退出"
}

object StringsFa : AppStrings {
    override val appName: String = "پروبل"
    override val appTitle: String = "ПроБел — v2whitelist"
    override val menu: String = "منو"
    override val subscriptions: String = "اشتراک‌ها"
    override val geekMode: String = "حالت حرفه‌ای (Geek)"
    override val appearance: String = "ظاهر برنامه"
    override val settings: String = "تنظیمات"
    override val exit: String = "خروج"
    override val save: String = "ذخیره"
    override val saved: String = "تنظیمات با موفقیت ذخیره شد!"
    override val cancel: String = "لغو"
    override val done: String = "انجام شد"
    override val delete: String = "حذف"
    override val edit: String = "ویرایش"
    override val retry: String = "تلاش مجدد"
    override val copy: String = "کپی"
    override val clear: String = "پاک کردن"
    override val close: String = "بستن"
    override val source: String = "منبع: "
    override val status: String = "وضعیت"
    override val protocol: String = "پروتکل: "
    override val address: String = "آدرس: "
    override val unknown: String = "نامشخص"
    override val all: String = "همه"
    override val statusConnected: String = "متصل شد"
    override val statusTesting: String = "در حال بررسی اتصال…"
    override val statusError: String = "خطای اتصال"
    override val statusDisconnected: String = "متصل نیست"
    override val statusTestingServers: String = "در حال بررسی سرورها…"
    override val manualSelectionPrefix: String = "انتخاب دستی: "
    override val tunnelActive: String = "تونل امن فعال است"
    override val pressStartToConnect: String = "برای اتصال ایمن روی شروع کلیک کنید"
    override val btnStart: String = "شروع"
    override val btnStop: String = "توقف"
    override val btnRetry: String = "تلاش مجدد"
    override val btnCancel: String = "لغو"
    override val btnSwitchServer: String = "تغییر سرور"
    override val btnSwitchingServer: String = "در حال تغییر سرور…"
    override val routingModeTitle: String = "حالت کارکرد: "
    override val routingModeTun: String = "TUN (آداپتور مجازی)"
    override val routingModeProxy: String = "پروکسی (SOCKS5 / HTTP)"
    override val routingModeTunDesc: String = "تمام ترافیک سیستم از طریق تونل TUN هدایت می‌شود"
    override val routingModeProxyDesc: String = "پروکسی سیستم روی 127.0.0.1:10808 / 10809"
    override val tunEnabled: String = "حالت TUN فعال است"
    override val proxyEnabled: String = "حالت پروکسی فعال است"
    override val scenarioManual: String = "سناریو: انتخاب دستی سرور"
    override val scenarioAuto: String = "سناریو: انتخاب خودکار"
    override val scenarioAutoTitle: String = "خودکار"
    override val scenarioAutoSubtitle: String = "تعادل لیست سفید و سیاه"
    override val scenarioWhitelistTitle: String = "لیست سفید"
    override val scenarioWhitelistSubtitle: String = "فقط ترافیک داخلی"
    override val scenarioBlacklistTitle: String = "لیست سیاه"
    override val scenarioBlacklistSubtitle: String = "دور زدن سایت‌های مسدود"
    override val scenarioYoutubeTitle: String = "یوتیوب"
    override val scenarioYoutubeSubtitle: String = "یوتیوب و موسیقی"
    override val showServerList: String = "نمایش لیست سرورها"
    override val hideServerList: String = "پنهان کردن لیست سرورها"
    override val searchNodesPlaceholder: String = "جستجوی سرور با نام یا آدرس…"
    override val noServersFoundCriteria: String = "سروری مطابق با شرایط یافت نشد"
    override val updateAvailableTitle: String = "نسخه جدید ПроБел در دسترس است: v"
    override val updateAvailableSubtitle: String = "روی «به‌روزرسانی» کلیک کنید"
    override val btnUpdate: String = "به‌روزرسانی"
    override val btnDismiss: String = "نادیده گرفتن"
    override val noSubsText: String = "بدون اشتراک"
    override val subFailureBanner: String = "خطا در به‌روزرسانی: "
    override val qrShowCode: String = "نمایش کد QR سرور"
    override val noActiveServer: String = "سرور فعالی وجود ندارد"
    override val pingAsc: String = "⚡ پینگ ▲"
    override val pingTest: String = "تست پینگ واقعی (204)"
    override val ping: String = "پینگ"
    override val findVip: String = "یافتن سرورهای VIP"
    override val pingVip: String = "تست پینگ VIP"
    override val dpiBypass: String = "دور زدن فیلترینگ هوشمند (DPI Bypass):"
    override val copyLogs: String = "کپی گزارش‌ها"
    override val addSub: String = "افزودن اشتراک"
    override val updateAll: String = "به‌روزرسانی همه"
    override val editSub: String = "ویرایش"
    override val deleteSub: String = "حذف"
    override val theme: String = "پوسته برنامه"
    override val language: String = "زبان برنامه"
    override val turnOnVpn: String = "▶ اتصال VPN"
    override val turnOffVpn: String = "⏹ قطع اتصال VPN"
    override val systemProxyOs: String = "پروکسی سیستم‌عامل"
    override val quickSwitch: String = "تغییر سریع"
    override val routingModeCardTitle: String = "حالت اتصال (پروکسی / TUN)"
    override val serversNotFound: String = "سروری یافت نشد"
    override val searchServers: String = "جستجوی سرورها..."
    override val serverList: String = "لیست سرورها"
    override val locationsFilter: String = "فیلتر کشورها"
    override val locationsFilterExclude: String = "به‌جز موارد انتخابی"
    override val locationsFilterWhitelist: String = "فقط موارد انتخابی"
    override val locationsFilterDisabled: String = "غیرفعال"
    override val proxyStandaloneTitle: String = "حالت پروکسی مستقل (Standalone Proxy)"
    override val proxyStandaloneDescLan: String = "بدون TUN: پورت‌های محلی برای شبکه داخلی (LAN) باز است"
    override val proxyStandaloneDescNoRoot: String = "بدون آداپتور TUN و بدون نیاز به دسترسی روت"
    override val proxySystemProxyOn: String = "پروکسی سیستم‌عامل: روشن"
    override val proxySystemProxyOff: String = "پروکسی سیستم‌عامل: خاموش"
    override val proxyQuickExportTitle: String = "پیکربندی سریع برنامه‌ها با یک کلیک:"
    override val proxyTgLinkCopied: String = "لینک پروکسی تلگرام"
    override val proxyTerminalCopied: String = "دستور پروکسی ترمینال"
    override val proxyPsCopied: String = "دستور پروکسی PowerShell"
    override val locFilterTitle: String = "فیلتر کشورها و موقعیت‌ها"
    override val locFilterDesc: String = "در انتخاب هوشمند سرورها (SmartConnect) و حالت خودکار استفاده می‌شود."
    override val locExcludeRadio: String = "به‌جز موارد انتخابی"
    override val locExcludeDesc: String = "لیست سیاه کشورها (سرورهای این کشورها نادیده گرفته می‌شوند)"
    override val locWhitelistRadio: String = "فقط موارد انتخابی"
    override val locWhitelistDesc: String = "لیست سفید کشورها (اتصال فقط به کشورهای علامت‌گذاری شده)"
    override val locDisabledRadio: String = "غیرفعال کردن فیلتر"
    override val locDisabledDesc: String = "استفاده از سرورهای تمام مناطق موجود"
    override val locResetAll: String = "بازنشانی انتخاب‌ها"
    override val servCheckTitle: String = "بررسی دسترسی‌پذیری سرویس‌ها"
    override val servCheckDesc: String = "سرویس‌هایی را که به جای تست استاندارد 204 بررسی می‌شوند انتخاب کنید. در صورت عدم دسترسی به هر یک، سرور رد می‌شود."
    override val servCheckEndpointWarning: String = "توجه: با فعال بودن نود نهایی، بررسی سرویس‌ها جای خود را به تست کامل زنجیره می‌دهد."
    override val servCustomBadge: String = "سفارشی"
    override val servAddBtn: String = "+ افزودن سرویس"
    override val servDefaultBtn: String = "پیش‌فرض"
    override val servAddDialogTitle: String = "افزودن سرویس برای بررسی"
    override val servAddNameLabel: String = "نام (مثلا GitHub)"
    override val servAddUrlLabel: String = "آدرس (مثلا https://github.com)"
    override val servAddErrorEmpty: String = "تمام فیلدها را پر کنید"
    override val qrDialogTitle: String = "کد QR سرور فعلی"
    override val qrCopyLink: String = "کپی کردن لینک"
    override val qrFailGenerate: String = "تولید کد QR ناموفق بود"
    override val appearanceTitle: String = "ظاهر برنامه"
    override val appearanceSubtitle: String = "شخصی‌سازی پوسته، پالت رنگ، سیاه خالص AMOLED و پس‌زمینه‌های گرادینت"
    override val themeCardTitle: String = "پوسته برنامه"
    override val themeCardDesc: String = "انتخاب حالت نمایش رابط کاربری:"
    override val themeSystem: String = "هماهنگ با سیستم (خودکار)"
    override val themeLight: String = "روشن"
    override val themeDark: String = "تاریک"
    override val paletteCardTitle: String = "پالت رنگ"
    override val paletteCardDesc: String = "انتخاب رنگ شاخص برای دکمه‌ها و بخش‌های اصلی:"
    override val amoledCardTitle: String = "سیاه خالص (AMOLED)"
    override val amoledSwitchTitle: String = "فعال‌سازی پس‌زمینه کاملاً سیاه #000000"
    override val amoledSwitchDesc: String = "سیاه خالص و عمیق #000000 در حالت تاریک برای نمایشگرهای OLED جهت کاهش مصرف باتری"
    override val languageCardTitle: String = "زبان برنامه"
    override val languageCardDesc: String = "انتخاب زبان رابط کاربری:"
    override val meshCardTitle: String = "پس‌زمینه‌های گرادینت (Mesh)"
    override val meshSwitchTitle: String = "پس‌زمینه متحرک و ملایم رنگی"
    override val meshSwitchDesc: String = "به‌طور پیش‌فرض غیرفعال است. یک پس‌زمینه متحرک و چشم‌نواز اضافه می‌کند."
    override val meshModeLabel: String = "حالت انیمیشن گرادینت:"
    override val meshModeStatic: String = "ایستا"
    override val meshModeBreathing: String = "تنفس (جریان ملایم)"
    override val meshModeReactive: String = "واکنشی (انقباض در شروع)"
    override val subManageTitle: String = "مدیریت اشتراک‌ها"
    override val subManageSubtitle: String = "سیستم یکپارچه اشتراک‌ها، سرورهای آیینه و دسته‌بندی سناریوها"
    override val subAddBtn: String = "افزودن"
    override val subUpdateAllBtn: String = "به‌روزرسانی همه"
    override val subUpdateSingleBtn: String = "به‌روزرسانی اشتراک"
    override val personalRulesHeader: String = "قوانین سناریو برای سرورهای مورد اعتماد"
    override val personalRulesHeaderDesc: String = "پیکربندی استفاده از گره‌های شخصی یا اشتراک‌های عمومی بر اساس سناریو"
    override val personalBlacklistRuleTitle: String = "در سناریوی لیست سیاه / یوتیوب (اینترنت آزاد)"
    override val personalBlacklistRuleDesc: String = "رفتار اتصال در زمان در دسترس بودن اینترنت آزاد"
    override val personalWhitelistRuleTitle: String = "در سناریوی لیست سفید (حالت فیلترینگ شدید)"
    override val personalWhitelistRuleDesc: String = "رفتار اتصال هنگام شناسایی لیست سفید یا اختلال شبکه"
    override val personalRuleAuto: String = "⚡ انتخاب خودکار (شخصی)"
    override val personalRuleSelected: String = "🎯 سرور انتخابی"
    override val personalRulePublic: String = "🌐 اشتراک‌های عمومی ProBel"
    override val personalRuleDirect: String = "🚫 مستقیم (بدون VPN)"
    override val subUpdatingBtn: String = "در حال به‌روزرسانی…"
    override val subFailBannerTitle: String = "خطا در به‌روزرسانی اشتراک‌ها"
    override val subRetryBtn: String = "تلاش مجدد"
    override val subEmptyText: String = "هیچ اشتراکی اضافه نشده است. برای اتصال روی «افزودن» کلیک کنید."
    override val subMirrorsCount: String = "آیینه‌ها: "
    override val subServersCount: String = "سرورها: "
    override val subSharePercent: String = "سهم: "
    override val subDialogAddTitle: String = "افزودن اشتراک"
    override val subDialogEditTitle: String = "ویرایش اشتراک"
    override val subNameLabel: String = "نام اشتراک"
    override val subUrlLabel: String = "لینک‌های اشتراک (آیینه‌ها با | جدا شوند)"
    override val subFilterLabel: String = "فیلتر Regex (حذف سرورها قبل از افزودن)"
    override val subGroupRegexLabel: String = "Regex برای گروه‌بندی"
    override val subSharePercentLabel: String = "درصد سهم سرورها (%)"
    override val subScenariosLabel: String = "دسته‌بندی‌های حالت خودکار:"
    override val subCatWhitelist: String = "لیست‌های سفید"
    override val subCatBlacklist: String = "لیست‌های سیاه"
    override val subCatYoutube: String = "یوتیوب و موسیقی"
    override val geekConsoleTitle: String = "حالت حرفه‌ای (Geek) — کنسول مهندسی"
    override val geekEvents: String = "رویداد"
    override val geekCopyLogs: String = "کپی"
    override val geekClearLogs: String = "پاک کردن"
    override val geekNoServerSelected: String = "سروری انتخاب نشده"
    override val geekProtocolLabel: String = "پروتکل: "
    override val geekAddressLabel: String = "آدرس: "
    override val geekPingLabel: String = "پینگ: "
    override val geekExitIpLabel: String = "آی‌پی خروجی:"
    override val geekExitIpUndefined: String = "تشخیص داده نشد"
    override val geekPingTestBtn: String = "تست پینگ"
    override val geekFindVipBtn: String = "یافتن VIP"
    override val geekPingVipBtn: String = "پینگ VIP"
    override val geekCheckServicesBtn: String = "بررسی سرویس‌ها"
    override val geekCheckingServicesBtn: String = "در حال بررسی همزمان سرویس‌ها…"
    override val geekTuneServicesDesc: String = "تنظیم سرویس‌ها"
    override val geekServicesAvailabilityTitle: String = "دسترسی‌پذیری سرویس‌ها:"
    override val geekServicesAvailableCount: String = "در دسترس"
    override val geekVipCacheTitle: String = "حافظه موقت سرورهای سریع VIP"
    override val geekStopVipBtn: String = "⏹ توقف VIP"
    override val geekRemoveVip: String = "حذف از VIP"
    override val geekDpiBypassTitle: String = "دور زدن فیلترینگ (DPI Bypass):"
    override val geekGlobalSniLabel: String = "SNI سراسری"
    override val geekCloudflareIpLabel: String = "آی‌پی تمیز کلودفلر"
    override val geekCustomUaLabel: String = "User-Agent سفارشی"
    override val geekLogFilterPlaceholder: String = "فیلتر گزارش‌های هسته (جستجو با کلمه کلیدی)…"
    override val catScannerCore: String = "اسکنر و هسته"
    override val catScannerCoreDesc: String = "تنظیمات توزیع سرورهای SmartConnect و هسته v2w-core"
    override val catProfileCheck: String = "اعتبارسنجی"
    override val catProfileCheckDesc: String = "بررسی هوشمند قبل از اتصال، تست سرویس‌ها و سرعت"
    override val catFailover: String = "تعویض خودکار (Failover)"
    override val catFailoverDesc: String = "تغییر خودکار سرور در صورت قطع ارتباط، Smart Failover و بررسی شبکه"
    override val catUpdates: String = "به‌روزرسانی"
    override val catUpdatesDesc: String = "به‌روزرسانی خودکار اشتراک‌ها و بررسی نسخه جدید برنامه"
    override val catRouting: String = "مسیریابی"
    override val catRoutingDesc: String = "دور زدن سایت‌های داخلی، تونل اختصاصی دامنه‌ها و زنجیره پروکسی"
    override val catPersonalServers: String = "سرورهای شخصی"
    override val catPersonalServersDesc: String = "سرورهای اختصاصی، اشتراک‌های خصوصی و مدیریت گره خروجی"
    override val catVpnCore: String = "تنظیمات VPN و هسته"
    override val catVpnCoreDesc: String = "تنظیمات شبکه VPN، پروکسی محلی، DNS، چندبخشی Mux و تکه‌تکه کردن بسته‌ها"
    override val catBackupData: String = "سیستم و داده‌ها"
    override val catBackupDataDesc: String = "تنظیمات سینی سیستم، بهینه‌سازی و بازنشانی به پیش‌فرض"
    override val cardSmartConnect: String = "توزیع سرورهای SmartConnect"
    override val cardSmartConnectDesc: String = "تنظیمات دسته‌بندی و سهمیه‌بندی سرورها"
    override val smartBatchLabel: String = "تعداد سرور در هر دسته (پیش‌فرض ۲۰)"
    override val smartPresetLabel: String = "الگوی پیش‌فرض توزیع سرورها:"
    override val presetEqual: String = "مساوی"
    override val presetRandom: String = "تصادفی"
    override val presetMore: String = "بیشتر"
    override val presetLess: String = "کمتر"
    override val cardV2wCore: String = "هسته و اسکنر v2w-core"
    override val cardV2wCoreDesc: String = "اسکنر پیشرفته محلی و مدیریت هسته Xray"
    override val v2wEnable: String = "استفاده از هسته v2w-core"
    override val v2wEnableDesc: String = "تست فوق‌سریع بومی از طریق پروسه مجزا"
    override val v2wFallback: String = "استفاده از اسکنر قدیمی در صورت خطا"
    override val v2wFallbackDesc: String = "اسکنر رزرو در صورت بروز خطا در فایل باینری"
    override val v2wSocketsLabel: String = "حداکثر سوکت‌های همزمان v2w-core"
    override val xrayPathLabel: String = "مسیر فایل اجرایی Xray (خالی برای تشخیص خودکار)"
    override val v2wPathLabel: String = "مسیر فایل اجرایی v2w-scan (خالی برای تشخیص خودکار)"
    override val cardProfileCheck: String = "بررسی هوشمند سرورها"
    override val cardProfileCheckDesc: String = "بررسی سلامت سرور قبل از برقراری ارتباط"
    override val testBeforeConnect: String = "بررسی سرور قبل از اتصال"
    override val testBeforeConnectDesc: String = "فیلتر کردن سرورهای قطع قبل از جابجایی"
    override val removeInsecure: String = "حذف سرورهای ناامن"
    override val removeInsecureDesc: String = "حذف خودکار سرورهایی با رمزنگاری ضعیف"
    override val serviceCheckEnable: String = "بررسی دسترسی‌پذیری سرویس‌ها"
    override val serviceCheckEnableDesc: String = "تست دسترسی به تلگرام، واتساپ، اینستاگرام و گوگل به جای پینگ ساده"
    override val vipLimitLabel: String = "ظرفیت حافظه موقت سرورهای سریع (VIP)"
    override val cardSpeedTest: String = "تست سرعت هنگام بررسی"
    override val cardSpeedTestDesc: String = "اندازه‌گیری سرعت دانلود واقعی"
    override val speedTestEnable: String = "فعال‌سازی تست سرعت هنگام بررسی"
    override val speedTestEnableDesc: String = "دانلود یک قطعه تستی کوچک برای محاسبه دقیق پهنای باند"
    override val speedTestBytesLabel: String = "حجم داده آزمایشی (بایت)"
    override val speedTestTimeoutLabel: String = "مهلت زمانی تست سرعت (میلی‌ثانیه)"
    override val minSpeedThresholdLabel: String = "حداقل سرعت مجاز (مگابیت بر ثانیه)"
    override val cardFailover: String = "جابجایی خودکار سرور (Smart Failover)"
    override val cardFailoverDesc: String = "محافظت در برابر قطعی ناگهانی و فیلترینگ شدید"
    override val autoFailoverEnable: String = "فعال‌سازی تغییر خودکار سرور هنگام قطعی"
    override val autoFailoverEnableDesc: String = "تغییر خودکار به سرور آماده در صورت افت اتصال"
    override val heartbeatLabel: String = "فاصله زمانی بررسی وضعیت اتصال (ثانیه)"
    override val smartFailoverEnable: String = "تعویض هوشمند سرور بر اساس افت ترافیک"
    override val smartFailoverEnableDesc: String = "واکنش آنی به افت شدید سرعت ناشی از اختلالات شبکه"
    override val failoverDegradationLabel: String = "آستانه افت ترافیک (کیلوبایت)"
    override val failoverStuckLabel: String = "مدت زمان توقف ترافیک قبل از تغییر سرور (ثانیه)"
    override val cardNetCheck: String = "بررسی وضعیت اتصال اینترنت"
    override val cardNetCheckDesc: String = "بررسی پایداری دسترسی به اینترنت جهانی"
    override val netCheckEnable: String = "فعال‌سازی مانیتورینگ سلامت شبکه"
    override val netCheckEnableDesc: String = "بررسی دوره‌ای دامنه‌ها جهت اطمینان از اتصال اینترنت"
    override val primaryDomainLabel: String = "دامنه اصلی بررسی (مثلا google.com)"
    override val fallbackDomainLabel: String = "دامنه جایگزین بررسی (مثلا cloudflare.com)"
    override val connCheckUrlLabel: String = "آدرس تست وضعیت اتصال VPN"
    override val cardSubUpdate: String = "به‌روزرسانی خودکار اشتراک‌ها در پس‌زمینه"
    override val cardSubUpdateDesc: String = "دریافت منظم سرورهای جدید طبق زمان‌بندی"
    override val subAutoUpdateEnable: String = "به‌روزرسانی خودکار اشتراک‌ها"
    override val subAutoUpdateEnableDesc: String = "تازه‌سازی لیست سرورها در پس‌زمینه"
    override val subIntervalLabel: String = "فاصله زمانی به‌روزرسانی (دقیقه)"
    override val subOnStartEnable: String = "به‌روزرسانی اشتراک‌ها هنگام اجرای برنامه"
    override val subOnStartEnableDesc: String = "دریافت آخرین پیکربندی‌ها با هر بار باز شدن برنامه"
    override val subOnlyProxyEnable: String = "به‌روزرسانی فقط از طریق VPN/پروکسی"
    override val subOnlyProxyEnableDesc: String = "محافظت در برابر فیلتر بودن لینک‌های اشتراک"
    override val subThreadsLabel: String = "تعداد رشته‌های همزمان دریافت اشتراک"
    override val yandexTranslateEnable: String = "دور زدن کپچای کلودفلر از طریق Yandex"
    override val yandexTranslateEnableDesc: String = "امکان دریافت اشتراک‌های محافظت شده با کلودفلر"
    override val userAgentLabel: String = "User-Agent سفارشی اشتراک‌ها"
    override val cardAppUpdates: String = "به‌روزرسانی‌های برنامه"
    override val cardAppUpdatesDesc: String = "کنترل نسخه و بررسی انتشارهای جدید در گیت‌هاب"
    override val autoCheckUpdates: String = "بررسی خودکار نسخه‌های جدید"
    override val autoCheckUpdatesDesc: String = "اطلاع‌رسانی هنگام انتشار نسخه جدید ПроБел"
    override val betaUpdates: String = "دریافت نسخه‌های آزمایشی (Beta)"
    override val betaUpdatesDesc: String = "دسترسی زودهنگام به ویژگی‌های جدید و بهینه‌سازی‌ها"
    override val cardRoutingBypass: String = "قوانین مسیریابی و زنجیره پروکسی"
    override val cardRoutingBypassDesc: String = "دسترسی مستقیم به سرویس‌های بومی و زنجیره نودها"
    override val bypassRuEnable: String = "دسترسی مستقیم به سرویس‌های داخلی (Bypass RU)"
    override val bypassRuEnableDesc: String = "سامانه‌های بانکی و دولتی بدون عبور از فیلترشکن باز می‌شوند"
    override val customEndpointEnable: String = "استفاده از نود پایانی (Proxy Chaining)"
    override val customEndpointEnableDesc: String = "هدایت ترافیک در زنجیره: سرور میانی -> سرور خروجی نهایی"
    override val endpointConfigLabel: String = "پیکربندی نود نهایی (URI یا JSON)"
    override val endpointSubUrlLabel: String = "لینک اشتراک برای انتخاب نود نهایی"
    override val endpointSelectServer: String = "انتخاب سرور از اشتراک"
    override val endpointSelectServerTitle: String = "انتخاب نود خروجی از اشتراک"
    override val endpointE2eEnable: String = "بررسی کامل زنجیره تا مقصد (E2E)"
    override val endpointE2eEnableDesc: String = "بررسی اتصال اینترنت از هر دو سرور قبل از شروع"
    override val cardSplitTunneling: String = "مسیریابی بر اساس نام دامنه (Split Tunneling)"
    override val cardSplitTunnelingDesc: String = "قوانین سفارشی برای عبور مستقیم یا از طریق پروکسی"
    override val domainSplitEnable: String = "فعال‌سازی تفکیک ترافیک بر اساس دامنه"
    override val domainSplitEnableDesc: String = "هدایت ترافیک بر اساس لیست دامنه‌های مشخص شده"
    override val domainWhitelistMode: String = "حالت لیست سفید (فقط دامنه‌های ذکر شده از پروکسی عبور کنند)"
    override val domainBlacklistMode: String = "حالت لیست سیاه (تمام ترافیک از پروکسی به‌جز دامنه‌های ذکر شده)"
    override val directDomainsLabel: String = "دامنه‌های مستقیم (Direct - با کاما جدا شوند)"
    override val proxyDomainsLabel: String = "دامنه‌های پروکسی (Proxy - با کاما جدا شوند)"
    override val cardVpnDns: String = "تنظیمات پیشرفته VPN و DNS"
    override val cardVpnDnsDesc: String = "تنظیمات آداپتور شبکه مجازی و سرورهای DNS"
    override val preferIpv6: String = "اولویت با اتصالات IPv6"
    override val preferIpv6Desc: String = "استفاده از IPv6 در صورت در دسترس بودن"
    override val localDns: String = "استفاده از DNS محلی سیستم"
    override val localDnsDesc: String = "ترجمه نام‌ها از طریق DNS ارائه‌دهنده اینترنت"
    override val fakeDns: String = "فعال‌سازی FakeDNS"
    override val fakeDnsDesc: String = "جلوگیری از نشت DNS و افزایش سرعت اتصال"
    override val additionalHttpProxy: String = "پورت کمکی پروکسی HTTP"
    override val additionalHttpProxyDesc: String = "ایجاد پورت پروکسی HTTP همزمان با SOCKS5"
    override val vpnDnsLabel: String = "سرورهای DNS برای VPN (با کاما جدا شوند)"
    override val vpnBypassLan: String = "عبور ندادن شبکه محلی (Bypass LAN)"
    override val vpnBypassLanDesc: String = "دسترسی مستقیم به روتر، چاپگر و دستگاه‌های شبکه محلی"
    override val vpnAddressLabel: String = "آدرس آی‌پی کارت شبکه مجازی VPN"
    override val vpnMtuLabel: String = "اندازه بسته MTU در VPN"
    override val cardCoreSocks: String = "تنظیمات هسته و SOCKS5"
    override val cardCoreSocksDesc: String = "تنظیمات سطح پایین اسنیفینگ پروتکل و پورت‌های ورودی"
    override val sniffingEnable: String = "فعال‌سازی اسنیفینگ پروتکل (Sniffing)"
    override val sniffingEnableDesc: String = "تشخیص دامنه واقعی در ترافیک رمزنگاری شده TLS"
    override val routeOnlyEnable: String = "فقط مسیریابی (Route Only)"
    override val routeOnlyEnableDesc: String = "استفاده از اسنیفینگ صرفاً جهت قوانین مسیریابی"
    override val allowLanEnable: String = "امکان اتصال دستگاه‌های دیگر از شبکه محلی (LAN)"
    override val allowLanEnableDesc: String = "باز کردن پورت‌های پروکسی برای سایر دستگاه‌های خانه"
    override val allowInsecureEnable: String = "اجازه اتصال با گواهینامه‌های نامعتبر (Allow Insecure)"
    override val allowInsecureEnableDesc: String = "نادیده گرفتن اعتبارسنجی گواهینامه SSL/TLS (با احتیاط استفاده شود)"
    override val localProxyPortLabel: String = "پورت ورودی پروکسی محلی SOCKS5"
    override val localProxyLoginLabel: String = "نام کاربری برای اتصال به پروکسی محلی"
    override val localProxyPasswordLabel: String = "رمز عبور برای اتصال به پروکسی محلی"
    override val remoteDnsLabel: String = "DNS راه دور و امن (Remote DNS)"
    override val internalDnsLabel: String = "DNS مستقیم داخلی (Direct DNS)"
    override val dnsHostsLabel: String = "نگاشت دستی DNS (IP domain, ...)"
    override val cardMux: String = "چندبخشی کردن ارتباطات (Mux)"
    override val cardMuxDesc: String = "ترکیب چندین اتصال در یک جریان واحد TCP برای کاهش تاخیر"
    override val muxEnable: String = "فعال‌سازی چندبخشی (Mux)"
    override val muxEnableDesc: String = "کاهش بار هندشیک در درخواست‌های مکرر اینترنتی"
    override val muxTcpLabel: String = "حداکثر جریان‌های همزمان TCP Mux"
    override val muxXudpLabel: String = "حداکثر جریان‌های همزمان xUDP Mux"
    override val cardFragmentation: String = "تکه‌تکه کردن بسته‌ها (Anti-DPI)"
    override val cardFragmentationDesc: String = "تقسیم بسته Client Hello برای دور زدن فیلترینگ هوشمند DPI"
    override val fragmentEnable: String = "فعال‌سازی تکه‌تکه کردن بسته‌ها"
    override val fragmentEnableDesc: String = "تقسیم بسته‌های آغازین برای پنهان‌سازی SNI"
    override val fragmentLengthLabel: String = "طول قطعات (بایت، مثلا 10-50)"
    override val fragmentIntervalLabel: String = "فاصله زمانی بین ارسال قطعات (میلی‌ثانیه)"
    override val fragmentPacketsLabel: String = "بسته‌های هدف برای تقسیم (مثلا 1-3 یا tlshello)"
    override val cardSystemTray: String = "تنظیمات سینی سیستم"
    override val cardSystemTrayDesc: String = "رفتار پنجره برنامه و قرارگیری در نوار وظیفه"
    override val minimizeToTrayEnable: String = "کوچک کردن برنامه به سینی سیستم (Tray) هنگام بستن پنجره"
    override val minimizeToTrayEnableDesc: String = "برنامه در پس‌زمینه به کار خود ادامه می‌دهد"
    override val cardReset: String = "بازنشانی تنظیمات و پاک‌سازی داده‌ها"
    override val cardResetDesc: String = "بازگرداندن تمام تنظیمات به حالت اولیه"
    override val resetAllSettings: String = "بازنشانی تمام تنظیمات به پیش‌فرض"
    override val resetDialogTitle: String = "تایید بازنشانی تنظیمات"
    override val resetDialogConfirm: String = "آیا از بازنشانی تمام تنظیمات اطمینان دارید؟ اشتراک‌های شما حذف نخواهند شد."
    override val subStatusActual: String = "به‌روز"
    override val subStatusOutdated: String = "منقضی‌شده"
    override val subStatusFailed: String = "خطا در به‌روزرسانی"
    override val error: String = "خطا"
    override val langSystem: String = "هماهنگ با سیستم"
    override val resetSuccess: String = "تنظیمات با موفقیت به حالت پیش‌فرض بازگشت!"
    override val statusConnecting: String = "در حال اتصال..."
    override val sudoTitle: String = "فعال‌سازی حالت TUN"
    override val sudoDesc: String = "برای حالت TUN (هدایت تمام ترافیک سیستم) در لینوکس دسترسی ریشه (sudo) نیاز است.\n\nرمز عبور فقط یک‌بار دریافت شده و تا بستن برنامه فقط در حافظه رم نگهداری می‌شود."
    override val sudoPasswordLabel: String = "رمز عبور sudo"
    override val sudoPasswordPlaceholder: String = "رمز عبور کاربر ارشد را وارد کنید..."
    override val sudoShowPassword: String = "نمایش رمز عبور"
    override val sudoHidePassword: String = "پنهان کردن رمز عبور"
    override val sudoVerifying: String = "در حال بررسی دسترسی sudo..."
    override val sudoConfirm: String = "تأیید"
    override val sudoCancel: String = "لغو"
    override val sudoEmptyError: String = "رمز عبور نمی‌تواند خالی باشد"
    override val sudoInvalidError: String = "رمز عبور sudo اشتباه است. لطفاً دوباره تلاش کنید."
    override val unsavedTitle: String = "تغییرات ذخیره‌نشده"
    override val unsavedDesc: String = "در تنظیمات تغییرات ذخیره‌نشده وجود دارد. آیا مایلید قبل از خروج ذخیره شوند؟"
    override val unsavedSave: String = "ذخیره"
    override val unsavedDiscard: String = "عدم ذخیره"
    override val unsavedCancel: String = "لغو"
    override val updateDialogTitle: String = "به‌روزرسانی در دسترس است"
    override val updateCurrentVersion: String = "نسخه فعلی"
    override val updateNewVersion: String = "نسخه جدید"
    override val updateChangelog: String = "تغییرات جدید:"
    override val updateFile: String = "فایل"
    override val updateDownload: String = "دانلود به‌روزرسانی"
    override val updateViewRelease: String = "مشاهده انتشار"
    override val updateAllReleases: String = "همه نسخه‌ها"
    override val updateLater: String = "بعداً"
    override val trayOpen: String = "باز کردن پروبل"
    override val trayDisconnect: String = "⏹ قطع VPN"
    override val trayConnecting: String = "⏳ در حال اتصال..."
    override val trayRetry: String = "🔄 تلاش مجدد اتصال"
    override val trayConnect: String = "▶ اتصال به VPN"
    override val traySwitchToProxy: String = "حالت: TUN ← تغییر به پروکسی"
    override val traySwitchToTun: String = "حالت: پروکسی ← تغییر به TUN"
    override val traySystemProxyOn: String = "✔ پروکسی سیستم: روشن (برای خاموش کردن کلیک کنید)"
    override val traySystemProxyOff: String = "پروکسی سیستم: خاموش (برای روشن کردن کلیک کنید)"
    override val trayExit: String = "خروج"
}

object I18n {
    fun get(lang: String): AppStrings {
        return when (lang.uppercase()) {
            "EN" -> StringsEn
            "ZH", "ZH-CN", "ZH_CN" -> StringsZh
            "FA" -> StringsFa
            "SYSTEM" -> {
                val sys = java.util.Locale.getDefault().language.lowercase()
                when {
                    sys.startsWith("ru") -> StringsRu
                    sys.startsWith("zh") -> StringsZh
                    sys.startsWith("fa") -> StringsFa
                    else -> StringsEn
                }
            }
            else -> StringsRu
        }
    }
}

val LocalAppStrings = compositionLocalOf<AppStrings> { StringsRu }
