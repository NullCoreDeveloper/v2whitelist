package com.kiktor.v2whitelist.core.model.logger

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Кроссплатформенный реактивный логгер GeekMode.
 * Сохраняет последние 2500 строк логов в StateFlow для UI и передаёт в platformLogHandler / fileLogHandler.
 */
object GeekModeLogger {
    private const val MAX_LOGS = 2500

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * Платформенный обработчик логов (на Android вызывает android.util.Log.d).
     */
    var platformLogHandler: ((tag: String, message: String) -> Unit)? = null

    /**
     * Дополнительный обработчик для записи форматированной строки в файл (например, на Desktop).
     */
    var fileLogHandler: ((formattedLine: String) -> Unit)? = null

    fun log(tag: String, message: String) {
        val now = currentTimeMillis()
        val timeStr = formatTime(now)
        val formattedMsg = "[$timeStr] [$tag] $message"

        platformLogHandler?.invoke(tag, message)
        fileLogHandler?.invoke(formattedMsg)

        _logs.update { current ->
            if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + formattedMsg
            } else {
                current + formattedMsg
            }
        }
    }

    fun snapshot(): List<String> = _logs.value

    fun clear() {
        _logs.value = emptyList()
    }

    private val PROXY_URI_REGEX = Regex(
        """\b(?:vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic|wireguard|wg)://[^\s"']+""",
        RegexOption.IGNORE_CASE
    )
    private val SECRET_QUERY_REGEX = Regex(
        """([?&](?:token|key|secret|password|pass|auth|uuid|sid|pbk)=)[^&\s"']+""",
        RegexOption.IGNORE_CASE
    )
    private val AUTH_HEADER_REGEX = Regex(
        """(Authorization\s*:\s*(?:Bearer|Basic)\s+)[^\s\r\n]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Маскирует приватные ключи, ссылки конфигураций и токены перед экспортом логов.
     */
    fun sanitizeLogLine(line: String): String {
        return line
            .replace(PROXY_URI_REGEX, "<redacted-proxy-uri>")
            .replace(SECRET_QUERY_REGEX, "$1***")
            .replace(AUTH_HEADER_REGEX, "$1***")
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = millis / 1000
        val ms = (millis % 1000).toString().padStart(3, '0')
        val sec = (totalSecs % 60).toString().padStart(2, '0')
        val min = ((totalSecs / 60) % 60).toString().padStart(2, '0')
        val hour = ((totalSecs / 3600) % 24).toString().padStart(2, '0')
        return "$hour:$min:$sec.$ms"
    }
}
