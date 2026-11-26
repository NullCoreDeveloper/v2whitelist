package com.kiktor.v2whitelist.core.domain.security

/**
 * Политика безопасности и валидации резервных копий (Android + Desktop).
 */
object BackupSecurityPolicy {
    const val MAX_BACKUP_BYTES = 16L * 1024 * 1024
    const val MAX_JSON_DEPTH = 64
    const val MAX_SERVERS = 5_000
    const val MAX_SUBSCRIPTIONS = 200

    /**
     * Настройки Desktop, которые содержат локальные пути к исполняемым файлам
     * и не должны переноситься через файлы резервных копий.
     */
    val FORBIDDEN_DESKTOP_SETTING_KEYS = setOf(
        "xray_path",
        "v2w_scan_path",
        "tun2socks_path",
        "v2w_tun_path",
        "service_path"
    )

    private val SECURITY_SENSITIVE_BOOL_KEYS = mapOf(
        "allow_lan" to "Доступ по локальной сети (allow_lan)",
        "allow_insecure" to "Небезопасные TLS-сертификаты (allow_insecure)",
        "pref_allow_insecure" to "Небезопасные TLS-сертификаты",
        "pref_domain_whitelist_mode" to "Режим белого списка доменов",
        "pref_app_split_enabled" to "Раздельное туннелирование приложений",
        "pref_bypass_russian_services" to "Обход российских сервисов",
        "pref_vpn_bypass_lan" to "Обход локальных сетей (VPN)"
    )

    private val SECURITY_SENSITIVE_STRING_KEYS = mapOf(
        "custom_direct_domains" to "Список прямых доменов",
        "custom_proxy_domains" to "Список проксируемых доменов",
        "pref_routing_mode" to "Режим маршрутизации",
        "pref_app_split_mode" to "Режим раздельного туннелирования"
    )

    data class BackupSecurityDiff(
        val warnings: List<String>
    ) {
        val hasChanges: Boolean get() = warnings.isNotEmpty()
    }

    /**
     * Проверяет, что глубина вложенности JSON не превышает [maxDepth]
     * и скобки/кавычки сбалансированы. Защищает парсер от StackOverflow / JSON-бомб.
     */
    fun checkJsonDepth(text: String, maxDepth: Int = MAX_JSON_DEPTH): Boolean {
        if (text.isBlank()) return false
        var depth = 0
        var quoted = false
        var escaped = false
        var hasRoot = false

        for (ch in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{', '[' -> {
                    hasRoot = true
                    if (++depth > maxDepth) return false
                }
                '}', ']' -> {
                    if (--depth < 0) return false
                }
            }
        }
        return hasRoot && depth == 0 && !quoted
    }

    /**
     * Удаляет пути к локальным бинарникам и прочие запрещённые ключи из строковых настроек Desktop.
     */
    fun sanitizeDesktopStringSettings(map: Map<String, String>): Map<String, String> {
        if (map.isEmpty()) return map
        return map.filterKeys { key ->
            val normalized = key.trim().lowercase()
            normalized !in FORBIDDEN_DESKTOP_SETTING_KEYS && !normalized.endsWith("_binary_path")
        }
    }

    /**
     * Проверяет допустимость TCP/UDP порта.
     */
    fun validatePort(port: Int, fallback: Int): Int {
        return if (port in 1..65535) port else fallback
    }

    /**
     * Формирует список изменений параметров безопасности между текущими и импортируемыми настройками Desktop.
     */
    fun detectDesktopSecurityChanges(
        currentStrings: Map<String, String>,
        currentBools: Map<String, Boolean>,
        incomingStrings: Map<String, String>,
        incomingBools: Map<String, Boolean>
    ): BackupSecurityDiff {
        val warnings = mutableListOf<String>()

        for ((key, label) in SECURITY_SENSITIVE_BOOL_KEYS) {
            val incoming = incomingBools[key] ?: continue
            val current = currentBools[key]
            if (current != incoming) {
                warnings += "$label: ${current?.toString() ?: "по умолчанию"} -> $incoming"
            }
        }

        for ((key, label) in SECURITY_SENSITIVE_STRING_KEYS) {
            val incoming = incomingStrings[key] ?: continue
            val current = currentStrings[key].orEmpty()
            if (current != incoming) {
                warnings += "$label изменён"
            }
        }

        return BackupSecurityDiff(warnings)
    }
}
