package com.kiktor.v2whitelist.desktop.ui.util

import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver

object CountryNames {
    private val codeToNameRu = mapOf(
        "RU" to "Россия",
        "UA" to "Украина",
        "DE" to "Германия",
        "NL" to "Нидерланды",
        "US" to "США",
        "FI" to "Финляндия",
        "TR" to "Турция",
        "FR" to "Франция",
        "GB" to "Великобритания",
        "SE" to "Швеция",
        "PL" to "Польша",
        "KZ" to "Казахстан",
        "SG" to "Сингапур",
        "JP" to "Япония",
        "AT" to "Австрия",
        "CH" to "Швейцария",
        "IT" to "Италия",
        "ES" to "Испания",
        "CA" to "Канада",
        "CZ" to "Чехия",
        "EE" to "Эстония",
        "LV" to "Латвия",
        "LT" to "Литва",
        "MD" to "Молдова",
        "GE" to "Грузия",
        "AM" to "Армения",
        "BY" to "Беларусь",
        "HK" to "Гонконг",
        "KR" to "Южная Корея",
        "IN" to "Индия",
        "IL" to "Израиль"
    )

    private val codeToNameEn = mapOf(
        "RU" to "Russia",
        "UA" to "Ukraine",
        "DE" to "Germany",
        "NL" to "Netherlands",
        "US" to "USA",
        "FI" to "Finland",
        "TR" to "Turkey",
        "FR" to "France",
        "GB" to "United Kingdom",
        "SE" to "Sweden",
        "PL" to "Poland",
        "KZ" to "Kazakhstan",
        "SG" to "Singapore",
        "JP" to "Japan",
        "AT" to "Austria",
        "CH" to "Switzerland",
        "IT" to "Italy",
        "ES" to "Spain",
        "CA" to "Canada",
        "CZ" to "Czech Republic",
        "EE" to "Estonia",
        "LV" to "Latvia",
        "LT" to "Lithuania",
        "MD" to "Moldova",
        "GE" to "Georgia",
        "AM" to "Armenia",
        "BY" to "Belarus",
        "HK" to "Hong Kong",
        "KR" to "South Korea",
        "IN" to "India",
        "IL" to "Israel"
    )

    private val codeToNameZh = mapOf(
        "RU" to "俄罗斯",
        "UA" to "乌克兰",
        "DE" to "德国",
        "NL" to "荷兰",
        "US" to "美国",
        "FI" to "芬兰",
        "TR" to "土耳其",
        "FR" to "法国",
        "GB" to "英国",
        "SE" to "瑞典",
        "PL" to "波兰",
        "KZ" to "哈萨克斯坦",
        "SG" to "新加坡",
        "JP" to "日本",
        "AT" to "奥地利",
        "CH" to "瑞士",
        "IT" to "意大利",
        "ES" to "西班牙",
        "CA" to "加拿大",
        "CZ" to "捷克",
        "EE" to "爱沙尼亚",
        "LV" to "拉脱维亚",
        "LT" to "立陶宛",
        "MD" to "摩尔多瓦",
        "GE" to "格鲁吉亚",
        "AM" to "亚美尼亚",
        "BY" to "白俄罗斯",
        "HK" to "香港",
        "KR" to "韩国",
        "IN" to "印度",
        "IL" to "以色列"
    )

    private val codeToNameFa = mapOf(
        "RU" to "روسیه",
        "UA" to "اوکراین",
        "DE" to "آلمان",
        "NL" to "هلند",
        "US" to "ایالات متحده",
        "FI" to "فنلاند",
        "TR" to "ترکیه",
        "FR" to "فرانسه",
        "GB" to "بریتانیا",
        "SE" to "سوئد",
        "PL" to "لهستان",
        "KZ" to "قزاقستان",
        "SG" to "سنگاپور",
        "JP" to "ژاپن",
        "AT" to "اتریش",
        "CH" to "سوئیس",
        "IT" to "ایتالیا",
        "ES" to "اسپانیا",
        "CA" to "کانادا",
        "CZ" to "چک",
        "EE" to "استونی",
        "LV" to "لتونی",
        "LT" to "لیتوانی",
        "MD" to "مولداوی",
        "GE" to "گرجستان",
        "AM" to "ارمنستان",
        "BY" to "بلاروس",
        "HK" to "هنگ کنگ",
        "KR" to "کره جنوبی",
        "IN" to "هند",
        "IL" to "اسرائیل"
    )

    fun getDisplayName(tag: String, lang: String = "RU"): String {
        val unknownText = when (lang.uppercase()) {
            "EN" -> "Unknown"
            "ZH", "ZH-CN", "ZH_CN" -> "未知"
            "FA" -> "نامشخص"
            else -> "Неизвестные"
        }
        if (tag.contains("Неизвестные") || tag.contains("Unknown") || tag.contains("未知") || tag.contains("نامشخص")) {
            return unknownText
        }
        val code = ServerTagResolver.flagEmojiToCountryCode(tag) ?: ServerTagResolver.extractTextCountryTag(tag)
        val dict = when (lang.uppercase()) {
            "EN" -> codeToNameEn
            "ZH", "ZH-CN", "ZH_CN" -> codeToNameZh
            "FA" -> codeToNameFa
            else -> codeToNameRu
        }
        return if (code != null) {
            val name = dict[code.uppercase()] ?: code
            "$tag $name"
        } else {
            tag
        }
    }

    fun getDisplayName(tag: String, isEn: Boolean): String {
        return getDisplayName(tag, if (isEn) "EN" else "RU")
    }
}
