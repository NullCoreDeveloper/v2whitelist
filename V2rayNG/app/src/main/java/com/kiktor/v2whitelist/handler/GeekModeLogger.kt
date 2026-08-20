package com.kiktor.v2whitelist.handler

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeekModeLogger {
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs = _logs.asSharedFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val formattedMsg = "[$time] [$tag] $message"
        _logs.tryEmit(formattedMsg)
        
        // Also print to logcat
        android.util.Log.d(tag, message)
    }
}
