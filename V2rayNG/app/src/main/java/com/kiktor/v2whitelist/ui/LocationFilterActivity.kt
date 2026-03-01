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
        // Собрать все уникальные эмодзи-флаги из профилей серверов
        val allServers = MmkvManager.decodeServerList()
        val emojiCountMap = mutableMapOf<String, Int>()

        for (guid in allServers) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val emoji = extractFirstFlagEmoji(profile.remarks)
            if (emoji != null) {
                emojiCountMap[emoji] = (emojiCountMap[emoji] ?: 0) + 1
            }
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
    }

    data class LocationItem(
        val emoji: String,
        val serverCount: Int,
        var isSelected: Boolean
    )
}
