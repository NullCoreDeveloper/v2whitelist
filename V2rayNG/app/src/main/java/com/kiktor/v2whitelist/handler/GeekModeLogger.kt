package com.kiktor.v2whitelist.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeekModeLogger {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val formattedMsg = "[$time] [$tag] $message"
        android.util.Log.d(tag, message)

        val current = _logs.value.toMutableList()
        current.add(formattedMsg)
        if (current.size > MAX_LOGS) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
