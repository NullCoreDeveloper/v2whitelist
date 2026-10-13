package com.kiktor.v2whitelist.core.model.logger

import com.kiktor.v2whitelist.core.model.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Кроссплатформенный реактивный логгер GeekMode.
 * Сохраняет последние 500 строк логов в StateFlow для UI и передаёт в platformLogHandler.
 */
object GeekModeLogger {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * Платформенный обработчик логов (на Android вызывает android.util.Log.d).
     */
    var platformLogHandler: ((tag: String, message: String) -> Unit)? = null

    fun log(tag: String, message: String) {
        val now = currentTimeMillis()
        val timeStr = formatTime(now)
        val formattedMsg = "[$timeStr] [$tag] $message"

        platformLogHandler?.invoke(tag, message)

        _logs.update { current ->
            if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + formattedMsg
            } else {
                current + formattedMsg
            }
        }
    }

    fun clear() {
        _logs.value = emptyList()
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
