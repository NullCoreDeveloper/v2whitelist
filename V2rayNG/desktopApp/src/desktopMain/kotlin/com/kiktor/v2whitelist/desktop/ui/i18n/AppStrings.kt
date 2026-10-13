package com.kiktor.v2whitelist.desktop.ui.i18n

import androidx.compose.runtime.compositionLocalOf

data class AppStrings(
    val appTitle: String,
    val menu: String,
    val subscriptions: String,
    val geekMode: String,
    val appearance: String,
    val settings: String,
    val connected: String,
    val connecting: String,
    val disconnected: String,
    val switchServer: String,
    val proxyMode: String,
    val tunMode: String,
    val autoRouting: String,
    val whitelistRouting: String,
    val blacklistRouting: String,
    val youtubeRouting: String,
    val serverList: String,
    val searchServers: String,
    val pingAsc: String,
    val locationsFilter: String,
    val locationsFilterExclude: String,
    val locationsFilterWhitelist: String,
    val locationsFilterDisabled: String,
    val pingTest: String,
    val findVip: String,
    val pingVip: String,
    val dpiBypass: String,
    val copyLogs: String,
    val addSub: String,
    val updateAll: String,
    val editSub: String,
    val deleteSub: String,
    val theme: String,
    val language: String,
    val exit: String
)

val StringsRu = AppStrings(
    appTitle = "пробел — v2whitelist",
    menu = "Меню",
    subscriptions = "Подписки",
    geekMode = "Geek Mode",
    appearance = "Оформление",
    settings = "Настройки",
    connected = "Подключено",
    connecting = "Подключение...",
    disconnected = "Отключено",
    switchServer = "Сменить сервер",
    proxyMode = "Включен Прокси",
    tunMode = "Включен TUN",
    autoRouting = "Автоматический",
    whitelistRouting = "Белый список",
    blacklistRouting = "Черный список",
    youtubeRouting = "YouTube",
    serverList = "Список серверов",
    searchServers = "Поиск по серверам...",
    pingAsc = "⚡ Пинг ▲",
    locationsFilter = "Фильтр стран",
    locationsFilterExclude = "Исключать выбранные",
    locationsFilterWhitelist = "Только выбранные",
    locationsFilterDisabled = "Отключен",
    pingTest = "Пинг-тест",
    findVip = "Найти VIP сервер",
    pingVip = "Пинг VIP",
    dpiBypass = "DPI Bypass (Подмена параметров для обхода блокировок):",
    copyLogs = "Копировать логи",
    addSub = "Добавить подписку",
    updateAll = "Обновить все",
    editSub = "Редактировать",
    deleteSub = "Удалить",
    theme = "Тема интерфейса",
    language = "Язык приложения",
    exit = "Выход"
)

val StringsEn = AppStrings(
    appTitle = "probel — v2whitelist",
    menu = "Menu",
    subscriptions = "Subscriptions",
    geekMode = "Geek Mode",
    appearance = "Appearance",
    settings = "Settings",
    connected = "Connected",
    connecting = "Connecting...",
    disconnected = "Disconnected",
    switchServer = "Switch Server",
    proxyMode = "Proxy Enabled",
    tunMode = "TUN Enabled",
    autoRouting = "Automatic",
    whitelistRouting = "Whitelist",
    blacklistRouting = "Blacklist",
    youtubeRouting = "YouTube",
    serverList = "Servers List",
    searchServers = "Search servers...",
    pingAsc = "⚡ Ping ▲",
    locationsFilter = "Country Filter",
    locationsFilterExclude = "Exclude Selected",
    locationsFilterWhitelist = "Only Selected",
    locationsFilterDisabled = "Disabled",
    pingTest = "Ping Test",
    findVip = "Find VIP Server",
    pingVip = "Ping VIP",
    dpiBypass = "DPI Bypass (Anti-censorship parameters):",
    copyLogs = "Copy Logs",
    addSub = "Add Subscription",
    updateAll = "Update All",
    editSub = "Edit",
    deleteSub = "Delete",
    theme = "Interface Theme",
    language = "Application Language",
    exit = "Exit"
)

object I18n {
    fun get(lang: String): AppStrings {
        return when (lang.uppercase()) {
            "EN" -> StringsEn
            "SYSTEM" -> {
                val sys = java.util.Locale.getDefault().language.lowercase()
                if (sys.startsWith("ru")) StringsRu else StringsEn
            }
            else -> StringsRu
        }
    }
}

val LocalAppStrings = compositionLocalOf { StringsRu }
