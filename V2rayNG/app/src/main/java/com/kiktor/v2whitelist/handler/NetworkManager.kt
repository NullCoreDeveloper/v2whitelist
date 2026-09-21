package com.kiktor.v2whitelist.handler

import android.content.Context
import android.util.Log
import com.kiktor.v2whitelist.handler.GeekModeLogger
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
                    GeekModeLogger.log("Network", "waitForInternet: Интернет появился (dzen.ru ответил)")
                }
                break
            }

            if (!isWaiting) {
                GeekModeLogger.log("Network", "waitForInternet: Нет прямого доступа в интернет. Ожидание сети...")
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

    /**
     * Проверяет доступность заданного HTTP/HTTPS URL (возвращает true, если код 200..399).
     */
    fun checkHttpAccess(urlString: String, timeoutMs: Int = 3500): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(urlString)
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "v2whitelist/${com.kiktor.v2whitelist.BuildConfig.VERSION_NAME}")
            }
            val responseCode = conn.responseCode
            responseCode in 200..399
        } catch (_: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    @Volatile
    private var lastFailureReportCheckTime = 0L
    @Volatile
    private var lastFailureReportCheckResult = false

    /**
     * Проверяет, следует ли фиксировать и отображать ошибку обновления подписки.
     * 
     * Логика:
     * - Проверяем доступность ya.ru и google.com.
     * - Если ОБА доступны (ya.ru && google.com) -> интернет полноценный, ошибка в самой подписке -> true (выводим ошибку).
     * - Если доступен только ya.ru (а google.com нет) -> белые списки / глушат зарубежный трафик -> false (игнорируем).
     * - Если оба не прошли -> интернета нет -> false (игнорируем).
     */
    fun shouldReportSubscriptionFailure(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastFailureReportCheckTime < 10_000L)) {
            return lastFailureReportCheckResult
        }

        val yaOk = checkHttpAccess("https://ya.ru")
        val result = if (!yaOk) {
            // ya.ru не доступен -> условие "оба доступны" уже не выполнено
            false
        } else {
            // ya.ru доступен, теперь проверяем google.com
            checkHttpAccess("https://www.google.com/generate_204") || checkHttpAccess("https://google.com")
        }

        lastFailureReportCheckTime = now
        lastFailureReportCheckResult = result
        GeekModeLogger.log("Network", "shouldReportSubscriptionFailure: yaOk=$yaOk, bothOk=$result")
        return result
    }
}
