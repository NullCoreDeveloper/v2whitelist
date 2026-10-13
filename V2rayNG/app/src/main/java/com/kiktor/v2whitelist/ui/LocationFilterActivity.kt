package com.kiktor.v2whitelist.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.handler.SubscriptionHelper
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import java.util.Locale

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
            com.kiktor.v2whitelist.handler.SmartConnectManager.cleanVipCacheForFilter()
        }
    }

    private fun setupLocationList() {
        val groupRegexMap = getGroupRegexMap()
        val disabledSubIds = SubscriptionHelper.loadCustomSubs().filter { !it.enabled }.map { "custom_sub_${it.id}" }.toSet()

        val allServers = MmkvManager.decodeServerList()
        val emojiCountMap = mutableMapOf<String, Int>()

        for (guid in allServers) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (disabledSubIds.contains(profile.subscriptionId)) continue
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
            com.kiktor.v2whitelist.handler.SmartConnectManager.cleanVipCacheForFilter()
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
                    val subs = com.kiktor.v2whitelist.util.JsonUtil.fromJson(customSubsJson, Array<com.kiktor.v2whitelist.core.subscription.model.CustomSubData>::class.java)
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
        fun getDefaultFilterSet(): Set<String> = ServerTagResolver.getDefaultFilterSet()

        /**
         * Извлекает первый эмодзи-флаг из строки.
         */
        fun extractFirstFlagEmoji(text: String): String? = ServerTagResolver.extractFirstFlagEmoji(text)

        fun extractTextCountryTag(text: String): String? = ServerTagResolver.extractTextCountryTag(text)

        /**
         * Универсальное определение тега сервера.
         */
        fun resolveServerTag(remarks: String, regexStr: String?): String =
            ServerTagResolver.resolveServerTag(remarks, regexStr)

        /**
         * Преобразует двухбуквенный эмодзи-флаг в код ISO 3166-1 alpha-2.
         */
        fun flagEmojiToCountryCode(emoji: String): String? = ServerTagResolver.flagEmojiToCountryCode(emoji)


        /**
         * Возвращает локализованное название страны/локации для отображения в списке фильтра.
         */
        fun getCountryDisplayName(tag: String, context: Context): String {
            if (tag == TAG_UNKNOWN || tag.contains("Неизвестные") || tag.contains("Unknown")) {
                return context.getString(R.string.location_filter_unknown)
            }
            val countryCode = flagEmojiToCountryCode(tag)
            if (countryCode != null) {
                val locale = SettingsManager.getLocale()
                val displayCountry = try {
                    Locale.Builder().setRegion(countryCode).build().getDisplayCountry(locale)
                } catch (e: Exception) {
                    null
                }
                if (!displayCountry.isNullOrBlank() && !displayCountry.equals(countryCode, ignoreCase = true)) {
                    return displayCountry
                }
            }
            return tag
        }
    }

    data class LocationItem(
        val emoji: String,
        val serverCount: Int,
        var isSelected: Boolean
    )
}
