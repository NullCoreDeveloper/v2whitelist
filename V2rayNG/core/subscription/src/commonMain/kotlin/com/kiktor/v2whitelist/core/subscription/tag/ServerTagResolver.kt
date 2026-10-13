package com.kiktor.v2whitelist.core.subscription.tag

object ServerTagResolver {
    const val TAG_UNKNOWN = "🌐 Неизвестные"

    /** Дефолтный набор фильтруемых флагов (Россия + Украина) */
    fun getDefaultFilterSet(): Set<String> = setOf("🇷🇺", "🇺🇦")

    /**
     * Извлекает первый эмодзи-флаг из строки.
     * Флаги состоят из двух Regional Indicator Symbols (U+1F1E6..U+1F1FF).
     * В кодировке UTF-16 каждый символ представлен суррогатной парой (\uD83C \uDDE6..\uDDFF).
     */
    fun extractFirstFlagEmoji(text: String): String? {
        if (text.length < 4) return null
        for (i in 0..text.length - 4) {
            if (text[i] == '\uD83C' && text[i + 1] in '\uDDE6'..'\uDDFF' &&
                text[i + 2] == '\uD83C' && text[i + 3] in '\uDDE6'..'\uDDFF'
            ) {
                return text.substring(i, i + 4)
            }
        }
        return null
    }

    /**
     * Преобразует двухбуквенный эмодзи-флаг в код ISO 3166-1 alpha-2 (например, "🇷🇺" -> "RU").
     */
    fun flagEmojiToCountryCode(emoji: String): String? {
        if (emoji.length == 4 &&
            emoji[0] == '\uD83C' && emoji[1] in '\uDDE6'..'\uDDFF' &&
            emoji[2] == '\uD83C' && emoji[3] in '\uDDE6'..'\uDDFF'
        ) {
            val char1 = ('A'.code + (emoji[1].code - 0xDDE6)).toChar()
            val char2 = ('A'.code + (emoji[3].code - 0xDDE6)).toChar()
            return "$char1$char2"
        }
        return null
    }

    private val COUNTRY_PATTERNS = listOf(
        Regex("(?i)\\b(Россия|РФ|Russia|Russian|Moscow|RU)\\b|俄罗斯联邦|俄罗斯") to "🇷🇺",
        Regex("(?i)\\b(Германия|Germany|Frankfurt|Berlin|DE)\\b|德国") to "🇩🇪",
        Regex("(?i)\\b(Нидерланды|Голландия|Netherlands|The Netherlands|Amsterdam|NL)\\b|荷兰") to "🇳🇱",
        Regex("(?i)\\b(США|Соединенные Штаты|United States|USA|America|US)\\b|美国|🇺🇲") to "🇺🇸",
        Regex("(?i)\\b(Финляндия|Finland|Helsinki|FI)\\b|芬兰") to "🇫🇮",
        Regex("(?i)\\b(Франция|France|Paris|Lyon|FR)\\b|法国") to "🇫🇷",
        Regex("(?i)\\b(Польша|Poland|Warsaw|PL)\\b|波兰") to "🇵🇱",
        Regex("(?i)\\b(Швеция|Sweden|Stockholm|SE)\\b|瑞典") to "🇸🇪",
        Regex("(?i)\\b(Япония|Japan|Tokyo|JP)\\b|日本") to "🇯🇵",
        Regex("(?i)\\b(Сингапур|Singapore|SG)\\b|新加坡") to "🇸🇬",
        Regex("(?i)\\b(Гонконг|Hong\\s*Kong|HongKong|HK)\\b|香港") to "🇭🇰",
        Regex("(?i)\\b(Южная Корея|Корея|Korea|Seoul|KR)\\b|韩国") to "🇰🇷",
        Regex("(?i)\\b(Турция|Turkey|Istanbul|TR)\\b|土耳其") to "🇹🇷",
        Regex("(?i)\\b(Казахстан|Kazakhstan|Almaty|Astana|KZ)\\b|哈萨克斯坦") to "🇰🇿",
        Regex("(?i)\\b(Украина|Ukraine|Kyiv|Kiev|UA)\\b|乌克兰") to "🇺🇦",
        Regex("(?i)\\b(Великобритания|Англия|United Kingdom|UK|London|GB)\\b|英国") to "🇬🇧",
        Regex("(?i)\\b(Канада|Canada|Toronto|CA)\\b|加拿大") to "🇨🇦",
        Regex("(?i)\\b(Швейцария|Switzerland|Zurich|CH)\\b|瑞士") to "🇨🇭",
        Regex("(?i)\\b(Австрия|Austria|Vienna|AT)\\b|奥地利") to "🇦🇹",
        Regex("(?i)\\b(Италия|Italy|Rome|Milano|IT)\\b|意大利") to "🇮🇹",
        Regex("(?i)\\b(Испания|Spain|Madrid|ES)\\b|西班牙") to "🇪🇸",
        Regex("(?i)\\b(ОАЭ|Эмираты|UAE|Dubai|AE)\\b|阿联酋|迪拜") to "🇦🇪",
        Regex("(?i)\\b(Чехия|Czech|Prague|CZ)\\b|捷克") to "🇨🇿",
        Regex("(?i)\\b(Болгария|Bulgaria|Sofia|BG)\\b|保加利亚") to "🇧🇬",
        Regex("(?i)\\b(Тайвань|Taiwan|Taipei|TW)\\b|台湾") to "🇹🇼",
        Regex("(?i)\\b(Сейшелы|Seychelles|SC)\\b|塞舌尔") to "🇸🇨",
        Regex("(?i)\\b(Джерси|Jersey|JE)\\b|泽西") to "🇯🇪",
        Regex("(?i)\\b(Кюрасао|Curacao|CW)\\b|库拉索") to "🇨🇼",
        Regex("(?i)\\b(Норвегия|Norway|Oslo|NO)\\b|挪威") to "🇳🇴",
        Regex("(?i)\\b(Индия|India|Mumbai|IN)\\b|印度") to "🇮🇳",
        Regex("(?i)\\b(Таиланд|Тайланд|Thailand|Bangkok|TH)\\b|泰国") to "🇹🇭",
        Regex("(?i)\\b(Австралия|Australia|Sydney|AU)\\b|澳大利亚") to "🇦🇺",
        Regex("(?i)\\b(Венгрия|Hungary|Budapest|HU)\\b|匈牙利") to "🇭🇺",
        Regex("(?i)\\b(Ирландия|Ireland|Dublin|IE)\\b|爱尔兰") to "🇮🇪",
        Regex("(?i)\\b(Румыния|Romania|Bucharest|RO)\\b|罗马尼亚") to "🇷🇴",
        Regex("(?i)\\b(Малайзия|Malaysia|MY)\\b|马来西亚") to "🇲🇾",
        Regex("(?i)\\b(Армения|Armenia|AM)\\b|亚美尼亚") to "🇦🇲",
        Regex("(?i)\\b(Грузия|Georgia|GE)\\b|格鲁吉亚") to "🇬🇪",
        Regex("(?i)\\b(Молдова|Молдавия|Moldova|MD)\\b|摩尔多瓦") to "🇲🇩",
        Regex("(?i)\\b(Сербия|Serbia|RS)\\b|塞尔维亚") to "🇷🇸",
        Regex("(?i)\\b(Эстония|Estonia|EE)\\b|爱沙尼亚") to "🇪🇪",
        Regex("(?i)\\b(Латвия|Latvia|LV)\\b|拉脱维亚") to "🇱🇻",
        Regex("(?i)\\b(Литва|Lithuania|LT)\\b|立陶宛") to "🇱🇹",
        Regex("(?i)\\b(Маршалловы Острова|MH)\\b|马绍尔群岛") to "🇲🇭",
        Regex("(?i)\\b(Дания|Denmark|DK)\\b|丹麦") to "🇩🇰",
        Regex("(?i)\\b(Иран|Iran|IR)\\b|伊朗") to "🇮🇷"
    )

    fun extractTextCountryTag(text: String): String? {
        for ((regex, flag) in COUNTRY_PATTERNS) {
            if (regex.containsMatchIn(text)) {
                return flag
            }
        }
        return null
    }

    private val PROTOCOL_FALLBACK_REGEX = Regex("(?i)\\b(VLESS|VMESS|HYSTERIA2|HY2|TROJAN|SHADOWSOCKS|SS|WIREGUARD|WARP)\\b")

    /**
     * Универсальное определение тега сервера:
     * 1. Приоритет: реальный эмодзи-флаг страны (🇷🇺, 🇩🇪, 🇺🇸 и др.).
     * 2. Текстовое название страны или ISO-код (Россия, Germany, NL, Taiwan...) -> эмодзи-флаг.
     * 3. Если страна не определена — применяется индивидуальный groupRegex подписки (White List, White Keys, Cloudflare, CIDR-*, SNI-*, Dynamic и др.).
     * 4. Fallback: группировка по типу протокола (VLESS, HYSTERIA2, TROJAN, VMESS, SS).
     * 5. Fallback: TAG_UNKNOWN ("🌐 Неизвестные").
     */
    fun resolveServerTag(remarks: String, regexStr: String? = null): String {
        val flag = extractFirstFlagEmoji(remarks)
        if (!flag.isNullOrEmpty()) {
            return flag
        }

        val textTag = extractTextCountryTag(remarks)
        if (!textTag.isNullOrEmpty()) {
            return textTag
        }

        if (!regexStr.isNullOrEmpty()) {
            try {
                val match = Regex(regexStr).find(remarks)
                if (match != null && match.groupValues.size > 1) {
                    val tag = match.groupValues[1].trim()
                    if (tag.isNotEmpty()) return tag
                }
            } catch (_: Exception) {
                // Ignore regex error
            }
        }

        // Если страна и regex не определили тег, но в названии указан протокол — группируем по протоколу
        val protoMatch = PROTOCOL_FALLBACK_REGEX.find(remarks)
        if (protoMatch != null) {
            return protoMatch.groupValues[1].uppercase()
        }

        return TAG_UNKNOWN
    }
}
