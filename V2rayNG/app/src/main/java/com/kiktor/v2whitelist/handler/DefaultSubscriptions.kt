package com.kiktor.v2whitelist.handler

object DefaultSubscriptions {
    
    /**
     * Список предустановленных подписок.
     * enabled = true означает, что подписка будет активна сразу после установки.
     * enabled = false означает, что она просто будет лежать в списке кастомных подписок на всякий случай.
     */
    val PREPOPULATED_SUBS = listOf(
        SmartConnectManager.CustomSubData(
            id = "def_zieng2",
            name = "zieng2 (Универсальные сервера)",
            url = listOf(
                "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
                "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
                "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
                "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt"
            ).joinToString("|"),
            enabled = true
        ),
        SmartConnectManager.CustomSubData(
            id = "def_igareck_black",
            name = "igareck (Обход черных списков / Блокировок)",
            url = listOf(
                "https://gitlab.com/igareck/vpn-configs-for-russia/raw/main/BLACK_VLESS_RUS_mobile.txt",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/BLACK_VLESS_RUS_mobile.txt",
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
                "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt"
            ).joinToString("|"),
            groupRegex = "(🌐)\\s*Anycast-IP",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_igareck_white",
            name = "igareck (Обход белых списков / Внутри РФ)",
            url = listOf(
                "https://gitlab.com/igareck/vpn-configs-for-russia/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"
            ).joinToString("|"),
            groupRegex = "(🌐)\\s*Anycast-IP",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_avencores_goida",
            name = "AvenCores/goida (Рекомендованные стабильные)",
            url = "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/1.txt",
            groupRegex = "\\[OpenRay\\]\\s*(Dynamic)",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_avencores_goida_bypass",
            name = "AvenCores/goida (Обход жестких блокировок по SNI/CIDR)",
            url = "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt",
            groupRegex = "\\[OpenRay\\]\\s*(Dynamic)",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_jsxta_whitelist",
            name = "jsxta (Динамические авто-тестируемые сервера)",
            url = "https://gbr.mydan.online/configs",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_mifa_bobrik",
            name = "mifa/bobrik (Резервные сервера)",
            url = "https://mifa.world/bobrik",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_rkp_bypass",
            name = "RKP (Анти-РосКомПозор: Белые и Черные списки)",
            url = listOf(
                "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt",
                "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/blacklist.txt"
            ).joinToString("|"),
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_aetris",
            name = "AetrisVPN (Сборник: Резервные + YouTube)",
            url = "https://raw.githubusercontent.com/flaafix/AetrisVPN/main/AetrisVPN.txt",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_etoneya",
            name = "etoneya (Специально для YouTube / YTUnblock)",
            url = "https://etoneya.su/whitelist",
            enabled = false
        ),
        SmartConnectManager.CustomSubData(
            id = "def_hiztin_gribi",
            name = "VLESS-PO-GRIBI (Альтернативные маршруты / Обход)",
            url = listOf(
                "https://raw.githubusercontent.com/hiztin/VLESS-PO-GRIBI/main/deploy/subscriptions/25.txt",
                "https://raw.githubusercontent.com/hiztin/VLESS-PO-GRIBI/main/deploy/subscriptions/26.txt"
            ).joinToString("|"),
            enabled = false
        )
    )
}
