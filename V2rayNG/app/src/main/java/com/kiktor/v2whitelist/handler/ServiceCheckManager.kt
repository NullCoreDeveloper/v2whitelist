package com.kiktor.v2whitelist.handler

import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.model.servicecheck.ServiceCheckItem

object ServiceCheckManager {

    fun isServiceCheckEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_SERVICE_CHECK_ENABLED, false)
    }

    fun setServiceCheckEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_SERVICE_CHECK_ENABLED, enabled)
    }

    fun loadServices(): List<ServiceCheckItem> {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_SERVICE_CHECK_ITEMS).orEmpty()
        return ServiceCheckItem.parseList(raw)
    }

    fun saveServices(services: List<ServiceCheckItem>) {
        val serialized = ServiceCheckItem.serializeList(services)
        MmkvManager.encodeSettings(AppConfig.PREF_SERVICE_CHECK_ITEMS, serialized)
    }

    fun toggleService(id: String, enabled: Boolean): List<ServiceCheckItem> {
        val current = loadServices().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveServices(current)
        return current
    }

    fun addCustomService(name: String, url: String): List<ServiceCheckItem> {
        val current = loadServices().toMutableList()
        val customId = "custom_${System.currentTimeMillis()}"
        val trimmedUrl = url.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        current.add(
            ServiceCheckItem(
                id = customId,
                name = name.trim(),
                url = trimmedUrl,
                enabled = true,
                isCustom = true
            )
        )
        saveServices(current)
        return current
    }

    fun removeService(id: String): List<ServiceCheckItem> {
        val current = loadServices().filter { it.id != id }
        saveServices(current)
        return current
    }

    fun resetToDefault(): List<ServiceCheckItem> {
        val defaults = ServiceCheckItem.DEFAULT_SERVICES
        saveServices(defaults)
        return defaults
    }
}

