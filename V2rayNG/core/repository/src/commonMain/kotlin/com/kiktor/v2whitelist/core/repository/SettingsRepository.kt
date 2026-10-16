package com.kiktor.v2whitelist.core.repository

import com.kiktor.v2whitelist.core.model.AppScenario

/**
 * Кроссплатформенный интерфейс настроек приложения.
 */
interface SettingsRepository {
    fun getAppScenario(): AppScenario
    fun setAppScenario(scenario: AppScenario)

    fun isSmartFailoverEnabled(): Boolean
    fun setSmartFailoverEnabled(enabled: Boolean)

    fun getSocksPort(): Int
    fun setSocksPort(port: Int)

    fun getHttpPort(): Int
    fun setHttpPort(port: Int)

    fun getString(key: String, defaultValue: String = ""): String
    fun setString(key: String, value: String)

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun setBoolean(key: String, value: Boolean)

    fun getInt(key: String, defaultValue: Int = 0): Int
    fun setInt(key: String, value: Int)

    fun clearAll() {}
}

/**
 * Потокобезопасная реализация настроек в памяти (для Desktop / тестов).
 */
class InMemorySettingsRepository : SettingsRepository {
    private val stringMap = mutableMapOf<String, String>()
    private val boolMap = mutableMapOf<String, Boolean>()
    private val intMap = mutableMapOf<String, Int>()

    private var appScenario: AppScenario = AppScenario.AUTO
    private var smartFailover: Boolean = true
    private var socksPort: Int = 10808
    private var httpPort: Int = 10809

    override fun getAppScenario(): AppScenario = appScenario
    override fun setAppScenario(scenario: AppScenario) {
        appScenario = scenario
    }

    override fun isSmartFailoverEnabled(): Boolean = smartFailover
    override fun setSmartFailoverEnabled(enabled: Boolean) {
        smartFailover = enabled
    }

    override fun getSocksPort(): Int = socksPort
    override fun setSocksPort(port: Int) {
        socksPort = port
    }

    override fun getHttpPort(): Int = httpPort
    override fun setHttpPort(port: Int) {
        httpPort = port
    }

    override fun getString(key: String, defaultValue: String): String {
        return stringMap[key] ?: defaultValue
    }

    override fun setString(key: String, value: String) {
        stringMap[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return boolMap[key] ?: defaultValue
    }

    override fun setBoolean(key: String, value: Boolean) {
        boolMap[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int {
        return intMap[key] ?: defaultValue
    }

    override fun setInt(key: String, value: Int) {
        intMap[key] = value
    }

    override fun clearAll() {
        appScenario = AppScenario.AUTO
        smartFailover = true
        socksPort = 10808
        httpPort = 10809
        stringMap.clear()
        boolMap.clear()
        intMap.clear()
    }
}
