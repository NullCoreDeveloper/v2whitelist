package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket

object NetworkManager {

    /**
     * Блокирует выполнение до тех пор, пока не появится реальный доступ в интернет (проверка dzen.ru).
     * Защищает кэш серверов от удаления при выключенном WiFi или отсутствии сети.
     */
    suspend fun waitForInternet(context: Context) {
        var isWaiting = false
        while (true) {
            val dzenOk = try {
                Socket().use { it.connect(InetSocketAddress("dzen.ru", 443), 1500); true }
            } catch (_: Exception) { false }

            if (dzenOk) {
                if (isWaiting) {
                    Log.i(AppConfig.TAG, "waitForInternet: Интернет появился (dzen.ru ответил)")
                }
                break
            }

            if (!isWaiting) {
                Log.w(AppConfig.TAG, "waitForInternet: Нет прямого доступа в интернет. Ожидание сети...")
                isWaiting = true
            }
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_UI_STATUS_UPDATE, context.getString(R.string.status_waiting_for_network))
            
            delay(2000)
        }
    }

    /**
     * Проверяет состояние интернета.
     * @return 0 - OK (всё доступно), 1 - JAMMED (только Яндекс), 2 - NO_INTERNET (ничего не доступно)
     */
    fun checkInternetStatus(): Int {
        val googleOk = try {
            Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 1500); true }
        } catch (_: Exception) { false }

        val yandexOk = try {
            Socket().use { it.connect(InetSocketAddress("77.88.8.8", 53), 1500); true }
        } catch (_: Exception) { false }

        return when {
            googleOk && yandexOk -> 0   // Все отлично
            !googleOk && yandexOk -> 1  // Глушат (Яндекс жив, Гугл нет)
            else -> 2                   // Интернета нет совсем
        }
    }
}
