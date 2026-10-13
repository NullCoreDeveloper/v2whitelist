package com.kiktor.v2whitelist.handler

import kotlinx.coroutines.flow.StateFlow

/**
 * Android-адаптер для GeekModeLogger.
 * Делегирует в общий KMP-логгер :core:model и транслирует записи в android.util.Log.d.
 */
object GeekModeLogger {
    val logs: StateFlow<List<String>> 
        get() = com.kiktor.v2whitelist.core.model.logger.GeekModeLogger.logs

    init {
        com.kiktor.v2whitelist.core.model.logger.GeekModeLogger.platformLogHandler = { tag, message ->
            android.util.Log.d(tag, message)
        }
    }

    fun log(tag: String, message: String) {
        com.kiktor.v2whitelist.core.model.logger.GeekModeLogger.log(tag, message)
    }

    fun clear() {
        com.kiktor.v2whitelist.core.model.logger.GeekModeLogger.clear()
    }
}
