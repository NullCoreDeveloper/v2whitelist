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

    fun getDisplayName(tag: String, isEn: Boolean = false): String {
        if (tag.contains("Неизвестные") || tag.contains("Unknown")) {
            return if (isEn) "Unknown" else "Неизвестные"
        }
        val code = ServerTagResolver.flagEmojiToCountryCode(tag) ?: ServerTagResolver.extractTextCountryTag(tag)
        val dict = if (isEn) codeToNameEn else codeToNameRu
        return if (code != null) {
            val name = dict[code.uppercase()] ?: code
            "$tag $name"
        } else {
            tag
        }
    }
}
