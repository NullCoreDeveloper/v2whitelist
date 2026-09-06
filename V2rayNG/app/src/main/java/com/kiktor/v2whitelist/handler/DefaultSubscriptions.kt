package com.kiktor.v2whitelist.handler

object DefaultSubscriptions {
    
    /**
     * Список предустановленных подписок.
     * enabled = true означает, что подписка будет активна сразу после установки.
     * enabled = false означает, что она просто будет лежать в списке кастомных подписок на всякий случай.
     */
    val PREPOPULATED_SUBS = listOf(
        SubscriptionHelper.CustomSubData(
            id = "def_zieng2",
            name = "zieng2 (Для обхода белых списков)",
            url = listOf(
                "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
                "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
                "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
                "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt"
            ).joinToString("|"),
            enabled = true
        ),
        SubscriptionHelper.CustomSubData(
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
        SubscriptionHelper.CustomSubData(
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
        SubscriptionHelper.CustomSubData(
            id = "def_avencores_goida",
            name = "AvenCores/goida (Сборник обхода черных списков)",
            url = "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/1.txt",
            groupRegex = "(Dynamic|wlrustg|V\\.O\\.I\\.D|oneclickvpnkeys|EbraSha|ShatakVPN)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_avencores_goida_bypass",
            name = "AvenCores/goida (Сборник обхода белых списков SNI/CIDR)",
            url = "https://github.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt",
            groupRegex = "(Dynamic|wlrustg|V\\.O\\.I\\.D|oneclickvpnkeys|EbraSha|ShatakVPN)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_jsxta_whitelist",
            name = "jsxta (Динамические авто-тестируемые сервера)",
            url = "https://gbr.mydan.online/configs",
            groupRegex = "(ShatakVPN|V\\.O\\.I\\.D|EbraSha)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_mifa_bobrik",
            name = "mifa/bobrik (Резервные сервера)",
            url = "https://mifa.world/bobrik",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_rkp_whitelist",
            name = "RKP (Анти-РосКомПозор: Белые списки)",
            url = "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt",
            groupRegex = "(🌐)\\s*Неизвестно",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_rkp_blacklist",
            name = "RKP (Анти-РосКомПозор: Черные списки)",
            url = "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/blacklist.txt",
            groupRegex = "(🌐)\\s*Неизвестно",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_aetris",
            name = "AetrisVPN (Сборник: Резервные + YouTube)",
            url = "https://raw.githubusercontent.com/flaafix/AetrisVPN/main/AetrisVPN.txt",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_hiztin_gribi",
            name = "VLESS-PO-GRIBI (Альтернативные маршруты / Обход)",
            url = "https://raw.githubusercontent.com/hiztin/VLESS-PO-GRIBI/main/deploy/subscriptions/25.txt",
            groupRegex = "(@[A-Za-z0-9_]+)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_kizyak_white",
            name = "КIЗЯК VPN (Обход белых списков / Mobile)",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta7.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt"
            ).joinToString("|"),
            groupRegex = "(?i)(Обход\\s*БС|🛸)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_kizyak_white_v6",
            name = "КIЗЯК VPN v6 (Обход белых списков / Резерв)",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta6.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6.txt"
            ).joinToString("|"),
            groupRegex = "(?i)(Обход\\s*БС|🛸)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_kizyak_black",
            name = "КIЗЯК VPN (Обход черных списков / Проводной)",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6BL.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta6BL.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6BL.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6BL.txt"
            ).joinToString("|"),
            groupRegex = "(🛸)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_cyberportal_cp002",
            name = "CYBERPORTAL (VLESS Black All RU)",
            url = "https://warp-gen.cyb-portal.org/CP-002",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_cyberportal_cp035",
            name = "CYBERPORTAL (VLESS White List)",
            url = "https://warp-gen.cyb-portal.org/CP-035",
            groupRegex = "(?i)(White\\s*List)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_cyberportal_cp006",
            name = "CYBERPORTAL (CIDR White All RU)",
            url = "https://warp-gen.cyb-portal.org/CP-006",
            groupRegex = "(?i)(CIDR\\s*White)(?!.*\\|)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_cyberportal_cp008",
            name = "CYBERPORTAL (SNI White All RU)",
            url = "https://warp-gen.cyb-portal.org/CP-008",
            groupRegex = "(?i)(SNI\\s*White)(?!.*\\|)",
            enabled = false
        ),
        SubscriptionHelper.CustomSubData(
            id = "def_cyberportal_cp042",
            name = "CYBERPORTAL (White Keys)",
            url = "https://warp-gen.cyb-portal.org/CP-042",
            groupRegex = "(?i)(White\\s*Keys)",
            enabled = false
        )
    )
}
