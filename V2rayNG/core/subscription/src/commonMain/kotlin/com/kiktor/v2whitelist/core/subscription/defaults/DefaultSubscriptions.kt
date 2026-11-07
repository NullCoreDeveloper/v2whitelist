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

        // --- 12. CID Ускоритель ---
        CustomSubData(
            id = "def_cid_white",
            name = "CID Ускоритель (Белые списки) [@cid_white]",
            url = "https://gitverse.ru/api/repos/cid-uskoritel/cid-white/raw/branch/master/whitelist.txt",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 13. bywarm rser ---
        CustomSubData(
            id = "def_bywarm_rser",
            name = "bywarm rser (Selected) [@wlrustg]",
            url = "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 14. Бункер Черные списки ---
        CustomSubData(
            id = "def_bunker_bl",
            name = "Бункер (Черные списки) [@akelladejavu]",
            url = "https://hub.mos.ru/akelladejavu/bunker/-/raw/main/BLACK_LIST.txt",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        ),

        // --- 15. Generation-Liberty ---
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

        // --- 16. Vsevj OBwl ---
        CustomSubData(
            id = "def_vsevj_obwl",
            name = "Vsevj OBwl (Белые списки) [@obwhitel]",
            url = "https://gitverse.ru/api/repos/Vsevj/OBwl/raw/branch/master/wwh",
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 17. PizdukVPN Main Sub ---
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

        // --- 18. PizdukVPN Whitelist ---
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

        // --- 19. Vlesstrojan kort0881 ---
        CustomSubData(
            id = "def_kort_white",
            name = "Vlesstrojan (kort0881 White) [@vlesstrojan]",
            url = listOf(
                "https://raw.githubusercontent.com/kort0881/vpn-checker-backend/main/checked/RU_Best/ru_white_all_WHITE.txt",
                "https://fastly.jsdelivr.net/gh/kort0881/vpn-checker-backend@main/checked/RU_Best/ru_white_all_WHITE.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_WHITELIST)
        ),

        // --- 20. FreeCFGHub ---
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

        // --- 21. Luxxuria Non-RU ---
        CustomSubData(
            id = "def_luxxuria_nonru",
            name = "Luxxuria (Non-RU Harvest) [github/luxxuria]",
            url = listOf(
                "https://raw.githubusercontent.com/luxxuria/harvester/main/non_ru.txt",
                "https://fastly.jsdelivr.net/gh/luxxuria/harvester@main/non_ru.txt"
            ).joinToString("|"),
            enabled = true,
            targetScenarios = listOf(CustomSubData.SCENARIO_BLACKLIST)
        )
    )
}
