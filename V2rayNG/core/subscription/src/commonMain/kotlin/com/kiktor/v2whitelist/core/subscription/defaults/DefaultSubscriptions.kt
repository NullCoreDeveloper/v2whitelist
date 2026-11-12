package com.kiktor.v2whitelist.core.subscription.defaults

import com.kiktor.v2whitelist.core.subscription.model.CustomSubData

object DefaultSubscriptions {

    /**
     * Список предустановленных подписок (Золотое ядро + Проверенные источники).
     * Каждая подписка содержит Telegram-канал или GitHub автора в названии.
     * enabled = true означает, что подписка будет активна сразу после установки.
     */
    val PREPOPULATED_SUBS = listOf(
        // --- 1. RKP Whitelist ---
        CustomSubData(
            id = "def_rkp_whitelist",
            name = "RKP (Белые списки) [@roskompozor]",
            url = listOf(
                "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/wl",
                "https://raw.githubusercontent.com/rkp-community/sub/main/wl"
            ).joinToString("|"),
            groupRegex = "(?i)#([A-Za-zА-Яа-я0-9]+)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 2. RKP Blacklist ---
        CustomSubData(
            id = "def_rkp_blacklist",
            name = "RKP (Черные списки) [@roskompozor]",
            url = listOf(
                "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/bl",
                "https://raw.githubusercontent.com/rkp-community/sub/main/bl"
            ).joinToString("|"),
            groupRegex = "(?i)#([A-Za-zА-Яа-я0-9]+)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 3. AetrisVPN ---
        CustomSubData(
            id = "def_aetris_yt",
            name = "AetrisVPN (YouTube & BL) [@YoutubeUnBlockRu]",
            url = listOf(
                "https://raw.githubusercontent.com/flaafix/AetrisVPN/main/AetrisVPN.txt",
                "https://fastly.jsdelivr.net/gh/flaafix/AetrisVPN@main/AetrisVPN.txt",
                "https://cdn.jsdelivr.net/gh/flaafix/AetrisVPN@main/AetrisVPN.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_YOUTUBE, CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 4. CYBERPORTAL White Keys ---
        CustomSubData(
            id = "def_cp042_white",
            name = "CYBERPORTAL (White Keys #42) [@cyb_portal]",
            url = listOf(
                "https://raw.githubusercontent.com/NullCoreDeveloper/cyberportal-mirror/master/cp-042.txt",
                "https://warp-gen.cyb-portal.org/CP-042"
            ).joinToString("|"),
            groupRegex = "(?i)(White\\s*Keys)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 5. CYBERPORTAL Black MIX ---
        CustomSubData(
            id = "def_cp001_mix",
            name = "CYBERPORTAL (Black MIX RU) [@cyb_portal]",
            url = listOf(
                "https://raw.githubusercontent.com/NullCoreDeveloper/cyberportal-mirror/master/cp-001.txt",
                "https://warp-gen.cyb-portal.org/CP-001"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 6. igareck Blacklist ---
        CustomSubData(
            id = "def_igareck_bl",
            name = "igareck (Черные списки [BL]) [@vpn_configs_ru]",
            url = listOf(
                "https://gitlab.com/igareck/vpn-configs-for-russia/raw/main/BLACK_VLESS_RUS_mobile.txt",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/BLACK_VLESS_RUS_mobile.txt",
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 7. WHITE-CIDR-RU ---
        CustomSubData(
            id = "def_white_cidr_ru",
            name = "WHITE-CIDR-RU (Белые списки) [@vpn_configs_ru]",
            url = listOf(
                "https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/WHITE-CIDR-RU-all.txt",
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\*([A-Z0-9_-]+)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 8. ЭтоНеЯ Whitelist ---
        CustomSubData(
            id = "def_etoneya_wl",
            name = "ЭтоНеЯ (Белые списки) [@etoneya]",
            url = listOf(
                "https://etoneya.su/whitelist",
                "https://etoneya.best/whitelist",
                "https://alley.serv00.net/whitelist",
                "https://etoskam.ru/whitelist"
            ).joinToString("|"),
            groupRegex = "(?i)(CIDR-[A-Za-z0-9_-]+|SNI-[A-Za-z0-9_-]+|YoutubeUnBlockRu)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 9. ЭтоНеЯ YouTube ---
        CustomSubData(
            id = "def_etoneya_yt",
            name = "ЭтоНеЯ (YouTube Unblock) [@etoneya]",
            url = listOf(
                "https://alley.serv00.net/youtube",
                "https://etoneya.su/youtube",
                "https://etoneya.best/youtube",
                "https://etoskam.ru/youtube"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_YOUTUBE, CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 10. rjsxrd Bypass All ---
        CustomSubData(
            id = "def_rjsxrd_all",
            name = "rjsxrd (Bypass All) [@rjsxrd]",
            url = listOf(
                "https://raw.githubusercontent.com/whoahaow/rjsxrd/main/githubmirror/bypass/bypass-all.txt",
                "https://fastly.jsdelivr.net/gh/whoahaow/rjsxrd@main/githubmirror/bypass/bypass-all.txt",
                "https://cdn.jsdelivr.net/gh/whoahaow/rjsxrd@main/githubmirror/bypass/bypass-all.txt"
            ).joinToString("|"),
            groupRegex = "(?i)(rjsxrd)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST, CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 11. VLESSFORU ---
        CustomSubData(
            id = "def_vlessforu",
            name = "VLESSFORU (Working Configs) [@VLESSFORU]",
            url = "https://sub.vlessfo.ru/vlessforu/working_configs.txt",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST, CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 12. Бункер Черные списки ---
        CustomSubData(
            id = "def_bunker_bl",
            name = "Бункер (Черные списки) [@akelladejavu]",
            url = "https://hub.mos.ru/akelladejavu/bunker/-/raw/main/BLACK_LIST.txt",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 13. Generation-Liberty ---
        CustomSubData(
            id = "def_generation_lib",
            name = "Generation-Liberty (Best) [@GenerationLiberty]",
            url = listOf(
                "https://raw.githubusercontent.com/gergew452/Generation-Liberty/main/githubmirror/best.txt",
                "https://fastly.jsdelivr.net/gh/gergew452/Generation-Liberty@main/githubmirror/best.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 14. PizdukVPN Main Sub ---
        CustomSubData(
            id = "def_pizduk_sub",
            name = "PizdukVPN (Main Sub) [@PizMod]",
            url = listOf(
                "https://raw.githubusercontent.com/PizdukMod/PizdukVPN/main/sub",
                "https://fastly.jsdelivr.net/gh/PizdukMod/PizdukVPN@main/sub"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 15. PizdukVPN Whitelist ---
        CustomSubData(
            id = "def_pizduk_wl",
            name = "PizdukVPN (Белые списки) [@PizMod]",
            url = listOf(
                "https://raw.githubusercontent.com/PizdukMod/PizdukVPN/main/wl",
                "https://fastly.jsdelivr.net/gh/PizdukMod/PizdukVPN@main/wl"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 16. Vlesstrojan kort0881 ---
        CustomSubData(
            id = "def_kort_white",
            name = "Vlesstrojan (kort0881 White) [@vlesstrojan]",
            url = listOf(
                "https://raw.githubusercontent.com/kort0881/vpn-checker-backend/main/checked/RU_Best/ru_white_all_WHITE.txt",
                "https://fastly.jsdelivr.net/gh/kort0881/vpn-checker-backend@main/checked/RU_Best/ru_white_all_WHITE.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\[\\d+ms\\s+([A-Z]{2})\\b",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 17. FreeCFGHub ---
        CustomSubData(
            id = "def_freecfghub",
            name = "FreeCFGHub (Hysteria2 & SS) [@FreeCFGHub]",
            url = listOf(
                "https://raw.githubusercontent.com/Ilyacom4ik/free-v2ray-2026/main/subscriptions/FreeCFGHub1.txt",
                "https://fastly.jsdelivr.net/gh/Ilyacom4ik/free-v2ray-2026@main/subscriptions/FreeCFGHub1.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 18. Luxxuria Non-RU ---
        CustomSubData(
            id = "def_luxxuria_nonru",
            name = "Luxxuria (Non-RU Harvest) [github/luxxuria]",
            url = listOf(
                "https://raw.githubusercontent.com/luxxuria/harvester/main/non_ru.txt",
                "https://fastly.jsdelivr.net/gh/luxxuria/harvester@main/non_ru.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 19. AkresVPN All ---
        CustomSubData(
            id = "def_theakres_all",
            name = "AkresVPN (All) [@AkresMC]",
            url = "https://raw.githubusercontent.com/theakres/VPN/refs/heads/main/all",
            groupRegex = "(?i)\\b([A-Z]{2})\\b",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 20. Scarlet Devil Whitelist ---
        CustomSubData(
            id = "def_scarlet_bs",
            name = "Scarlet Devil (Белые списки) [@ScDevNetwork]",
            url = listOf(
                "https://scarlet-devil.vercel.app/sub1",
                "https://raw.githubusercontent.com/Areral/ScarletDevil/main/sub_bs.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\|\\s*([A-Z]{2})\\s*\\|",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 21. Scarlet Devil Blacklist ---
        CustomSubData(
            id = "def_scarlet_chs",
            name = "Scarlet Devil (Черные списки) [@ScDevNetwork]",
            url = listOf(
                "https://scarlet-devil.vercel.app/sub2",
                "https://raw.githubusercontent.com/Areral/ScarletDevil/main/sub_chs.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\|\\s*([A-Z]{2})\\s*\\|",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 22. zieng2 Universal WL ---
        CustomSubData(
            id = "def_zieng_universal",
            name = "zieng2 (Universal WL) [github/zieng2]",
            url = listOf(
                "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
                "https://fastly.jsdelivr.net/gh/zieng2/wl@main/vless_universal.txt",
                "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
                "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
                "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\b([A-Z]{2})\\b",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 23. КIЗЯК VPN v7 ---
        CustomSubData(
            id = "def_kizyak_v7",
            name = "КIЗЯК VPN v7 (Белые списки) [@kizyak_vpn]",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta7.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\[(.*?)\\]",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 24. КIЗЯК VPN v6 ---
        CustomSubData(
            id = "def_kizyak_v6",
            name = "КIЗЯК VPN v6 (LTE БС) [@kizyak_vpn]",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta6.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\[(.*?)\\]",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 25. КIЗЯК VPN v6BL ---
        CustomSubData(
            id = "def_kizyak_v6_bl",
            name = "КIЗЯК VPN v6 (Черные списки) [@kizyak_vpn]",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta6BL.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6BL.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6BL.txt"
            ).joinToString("|"),
            groupRegex = "(?i)\\[(.*?)\\]",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 26. КIЗЯК VPN v8 ---
        CustomSubData(
            id = "def_kizyak_v8",
            name = "КIЗЯК VPN v8 (Best) [@kizyak_vpn]",
            url = listOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/main/kizyakbeta8.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta8.txt",
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta8.txt"
            ).joinToString("|"),
            groupRegex = "(?i)(LTE|BL|КIЗЯК)",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST, CustomSubData.SCENARIO_BLACKLIST)
        )
    )
}
