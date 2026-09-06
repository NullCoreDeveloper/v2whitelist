package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper

class LocationFilterActivity : BaseActivity() {

    private lateinit var rgFilterMode: RadioGroup
    private lateinit var tvModeHint: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvLocations: RecyclerView
    private lateinit var adapter: LocationFilterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_location_filter, showHomeAsUp = true, title = getString(R.string.title_location_filter))

        rgFilterMode = findViewById(R.id.rg_filter_mode)
        tvModeHint = findViewById(R.id.tv_mode_hint)
        tvEmpty = findViewById(R.id.tv_empty)
        rvLocations = findViewById(R.id.rv_locations)

        setupFilterMode()
        setupLocationList()
    }

    private fun setupFilterMode() {
        val currentMode = MmkvManager.decodeSettingsString(
            AppConfig.PREF_LOCATION_FILTER_MODE,
            AppConfig.LOCATION_FILTER_MODE_EXCLUDE
        )

        if (currentMode == AppConfig.LOCATION_FILTER_MODE_WHITELIST) {
            rgFilterMode.check(R.id.rb_whitelist)
            tvModeHint.text = getString(R.string.location_filter_mode_whitelist_hint)
        } else {
            rgFilterMode.check(R.id.rb_exclude)
            tvModeHint.text = getString(R.string.location_filter_mode_exclude_hint)
        }

        rgFilterMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_whitelist)
                AppConfig.LOCATION_FILTER_MODE_WHITELIST
            else
                AppConfig.LOCATION_FILTER_MODE_EXCLUDE

            MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_FILTER_MODE, mode)
            tvModeHint.text = if (mode == AppConfig.LOCATION_FILTER_MODE_WHITELIST)
                getString(R.string.location_filter_mode_whitelist_hint)
            else
                getString(R.string.location_filter_mode_exclude_hint)
        }
    }

    private fun setupLocationList() {
        val groupRegexMap = getGroupRegexMap()

        val allServers = MmkvManager.decodeServerList()
        val emojiCountMap = mutableMapOf<String, Int>()

        for (guid in allServers) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val regexStr = groupRegexMap[profile.subscriptionId]
            val tag = resolveServerTag(profile.remarks, regexStr)
            emojiCountMap[tag] = (emojiCountMap[tag] ?: 0) + 1
        }

        if (emojiCountMap.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvLocations.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        rvLocations.visibility = View.VISIBLE

        // Загрузить текущие выбранные флаги
        val savedSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET)
            ?: getDefaultFilterSet()

        // Если нет сохранённых настроек — сохранить дефолт
        if (MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET) == null) {
            MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_FILTER_SET, savedSet.toMutableSet())
        }

        val locations = emojiCountMap.entries
            .sortedByDescending { it.value }
            .map { LocationItem(it.key, it.value, savedSet.contains(it.key)) }

        adapter = LocationFilterAdapter(locations) { emoji, isChecked ->
            val currentSet = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_LOCATION_FILTER_SET)
                ?.toMutableSet() ?: mutableSetOf()
            if (isChecked) {
                currentSet.add(emoji)
            } else {
                currentSet.remove(emoji)
            }
            MmkvManager.encodeSettings(AppConfig.PREF_LOCATION_FILTER_SET, currentSet)
        }

        rvLocations.layoutManager = LinearLayoutManager(this)
        rvLocations.adapter = adapter
    }

    companion object {
        const val TAG_UNKNOWN = "🌐 Неизвестные"

        fun getGroupRegexMap(): Map<String, String> {
            val map = mutableMapOf<String, String>()

            // 1. Дефолтные регулярные выражения из DefaultSubscriptions.PREPOPULATED_SUBS (доступны всегда)
            for (sub in com.kiktor.v2whitelist.handler.DefaultSubscriptions.PREPOPULATED_SUBS) {
                if (sub.groupRegex.isNotEmpty()) {
                    map["custom_sub_${sub.id}"] = sub.groupRegex
                    map[sub.id] = sub.groupRegex
                }
            }

            // 2. Настройки кастомных подписок из MMKV (пользовательские переопределения)
            val customSubsJson = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_SUB_URLS)
            if (!customSubsJson.isNullOrEmpty()) {
                try {
                    val subs = com.kiktor.v2whitelist.util.JsonUtil.fromJson(customSubsJson, Array<com.kiktor.v2whitelist.handler.SubscriptionHelper.CustomSubData>::class.java)
                    subs?.forEach { sub ->
                        if (sub.groupRegex.isNotEmpty()) {
                            map["custom_sub_${sub.id}"] = sub.groupRegex
                            map[sub.id] = sub.groupRegex
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // 3. Сопоставляем реальные подписки из MMKV (если у них GUID не custom_sub_*, а UUID)
            try {
                val allRealSubs = MmkvManager.decodeSubscriptions()
                for (realSub in allRealSubs) {
                    val guid = realSub.guid
                    if (map.containsKey(guid)) continue
                    val url = realSub.subscription.url
                    val remarks = realSub.subscription.remarks
                    val matchedDefault = com.kiktor.v2whitelist.handler.DefaultSubscriptions.PREPOPULATED_SUBS.find {
                        (it.url.isNotEmpty() && url.contains(it.url.substringBefore("|"))) ||
                        (it.name.isNotEmpty() && remarks == it.name)
                    }
                    if (matchedDefault != null && matchedDefault.groupRegex.isNotEmpty()) {
                        map[guid] = matchedDefault.groupRegex
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            return map
        }

        /** Дефолтный набор фильтруемых флагов (Россия + Украина) */
        fun getDefaultFilterSet(): Set<String> = setOf("🇷🇺", "🇺🇦")

        /**
         * Извлекает первый эмодзи-флаг из строки.
         * Флаги состоят из двух Regional Indicator Symbols (U+1F1E6..U+1F1FF).
         */
        fun extractFirstFlagEmoji(text: String): String? {
            val codePoints = text.codePoints().toArray()
            for (i in 0 until codePoints.size - 1) {
                if (codePoints[i] in 0x1F1E6..0x1F1FF && codePoints[i + 1] in 0x1F1E6..0x1F1FF) {
                    return String(codePoints, i, 2)
                }
            }
            return null
        }

        private val COUNTRY_PATTERNS = listOf(
            Regex("(?i)\\b(Россия|РФ|Russia|Russian|Moscow|RU)\\b") to "🇷🇺",
            Regex("(?i)\\b(Германия|Germany|Frankfurt|Berlin|DE)\\b") to "🇩🇪",
            Regex("(?i)\\b(Нидерланды|Голландия|Netherlands|The Netherlands|Amsterdam|NL)\\b") to "🇳🇱",
            Regex("(?i)\\b(США|Соединенные Штаты|United States|USA|America|US)\\b") to "🇺🇸",
            Regex("(?i)\\b(Финляндия|Finland|Helsinki|FI)\\b") to "🇫🇮",
            Regex("(?i)\\b(Франция|France|Paris|Lyon|FR)\\b") to "🇫🇷",
            Regex("(?i)\\b(Польша|Poland|Warsaw|PL)\\b") to "🇵🇱",
            Regex("(?i)\\b(Швеция|Sweden|Stockholm|SE)\\b") to "🇸🇪",
            Regex("(?i)\\b(Япония|Japan|Tokyo|JP)\\b") to "🇯🇵",
            Regex("(?i)\\b(Сингапур|Singapore|SG)\\b") to "🇸🇬",
            Regex("(?i)\\b(Гонконг|Hong\\s*Kong|HongKong|HK)\\b") to "🇭🇰",
            Regex("(?i)\\b(Южная Корея|Корея|Korea|Seoul|KR)\\b") to "🇰🇷",
            Regex("(?i)\\b(Турция|Turkey|Istanbul|TR)\\b") to "🇹🇷",
            Regex("(?i)\\b(Казахстан|Kazakhstan|Almaty|Astana|KZ)\\b") to "🇰🇿",
            Regex("(?i)\\b(Украина|Ukraine|Kyiv|Kiev|UA)\\b") to "🇺🇦",
            Regex("(?i)\\b(Великобритания|Англия|United Kingdom|UK|London|GB)\\b") to "🇬🇧",
            Regex("(?i)\\b(Канада|Canada|Toronto|CA)\\b") to "🇨🇦",
            Regex("(?i)\\b(Швейцария|Switzerland|Zurich|CH)\\b") to "🇨🇭",
            Regex("(?i)\\b(Австрия|Austria|Vienna|AT)\\b") to "🇦🇹",
            Regex("(?i)\\b(Италия|Italy|Rome|Milano|IT)\\b") to "🇮🇹",
            Regex("(?i)\\b(Испания|Spain|Madrid|ES)\\b") to "🇪🇸",
            Regex("(?i)\\b(ОАЭ|Эмираты|UAE|Dubai|AE)\\b") to "🇦🇪",
            Regex("(?i)\\b(Чехия|Czech|Prague|CZ)\\b") to "🇨🇿",
            Regex("(?i)\\b(Болгария|Bulgaria|Sofia|BG)\\b") to "🇧🇬",
            Regex("(?i)\\b(Тайвань|Taiwan|Taipei|TW)\\b") to "🇹🇼",
            Regex("(?i)\\b(Сейшелы|Seychelles|SC)\\b") to "🇸🇨",
            Regex("(?i)\\b(Джерси|Jersey|JE)\\b") to "🇯🇪",
            Regex("(?i)\\b(Кюрасао|Curacao|CW)\\b") to "🇨🇼",
            Regex("(?i)\\b(Норвегия|Norway|Oslo|NO)\\b") to "🇳🇴",
            Regex("(?i)\\b(Индия|India|Mumbai|IN)\\b") to "🇮🇳",
            Regex("(?i)\\b(Таиланд|Тайланд|Thailand|Bangkok|TH)\\b") to "🇹🇭",
            Regex("(?i)\\b(Австралия|Australia|Sydney|AU)\\b") to "🇦🇺",
            Regex("(?i)\\b(Венгрия|Hungary|Budapest|HU)\\b") to "🇭🇺",
            Regex("(?i)\\b(Ирландия|Ireland|Dublin|IE)\\b") to "🇮🇪",
            Regex("(?i)\\b(Румыния|Romania|Bucharest|RO)\\b") to "🇷🇴",
            Regex("(?i)\\b(Малайзия|Malaysia|MY)\\b") to "🇲🇾",
            Regex("(?i)\\b(Армения|Armenia|AM)\\b") to "🇦🇲",
            Regex("(?i)\\b(Грузия|Georgia|GE)\\b") to "🇬🇪",
            Regex("(?i)\\b(Молдова|Молдавия|Moldova|MD)\\b") to "🇲🇩",
            Regex("(?i)\\b(Сербия|Serbia|RS)\\b") to "🇷🇸",
            Regex("(?i)\\b(Эстония|Estonia|EE)\\b") to "🇪🇪",
            Regex("(?i)\\b(Латвия|Latvia|LV)\\b") to "🇱🇻",
            Regex("(?i)\\b(Литва|Lithuania|LT)\\b") to "🇱🇹",
            Regex("(?i)\\b(Маршалловы Острова|MH)\\b") to "🇲🇭"
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
        fun resolveServerTag(remarks: String, regexStr: String?): String {
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
                } catch (e: Exception) {
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

    data class LocationItem(
        val emoji: String,
        val serverCount: Int,
        var isSelected: Boolean
    )
}
