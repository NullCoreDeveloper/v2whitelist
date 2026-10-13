package com.kiktor.v2whitelist.handler

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kiktor.v2whitelist.AngApplication
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object YandexCaptchaSolver {

    private val mutex = Mutex()

    /**
     * Attempts to solve Yandex SmartCaptcha:
     * 1. Silent background pass in headless WebView (for invisible challenges / JS proof-of-work).
     * 2. Interactive Dialog with WebView if human interaction is required (user clicks checkbox).
     *
     * @param context Context or Activity
     * @param captchaUrl URL where captcha was encountered
     * @param allowInteractive Whether to show interactive dialog if silent pass fails. False in background/sequential mode.
     * @return true if captcha was resolved and cookies updated, false otherwise.
     */
    suspend fun solve(
        context: Context,
        captchaUrl: String,
        allowInteractive: Boolean = true
    ): Boolean = mutex.withLock {
        val currentCookies = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
        if (currentCookies.contains("spravka=")) {
            Log.i(AppConfig.TAG, "YandexCaptchaSolver: valid spravka already in cookies, testing if challenge bypassed")
        }

        withContext(Dispatchers.Main) {
            val activity = findActivity(context)

            GeekModeLogger.log("YandexCaptcha", "Запуск резолвера капчи (URL: $captchaUrl)")

            // 1. Попытка тихого решения в фоновом WebView
            GeekModeLogger.log("YandexCaptcha", "Попытка автоматического фонового решения в headless WebView...")
            val silentSuccess = trySilentSolve(context, captchaUrl)
            if (silentSuccess) {
                Log.i(AppConfig.TAG, "YandexCaptchaSolver: silent challenge passed successfully!")
                GeekModeLogger.log("YandexCaptcha", "✅ Фоновая проверка успешно пройдена! Сохранены session cookies.")
                return@withContext true
            }

            // 2. Интерактивный диалог с пользователем (если разрешен интерактивный режим)
            if (allowInteractive && activity != null && !activity.isFinishing && !activity.isDestroyed) {
                Log.i(AppConfig.TAG, "YandexCaptchaSolver: silent pass insufficient, launching interactive dialog")
                GeekModeLogger.log("YandexCaptcha", "Фоновое решение не удалось. Открыт диалог подтверждения «Я не робот».")
                val dialogResult = showInteractiveDialog(activity, captchaUrl)
                if (dialogResult) {
                    GeekModeLogger.log("YandexCaptcha", "✅ Капча успешно подтверждена пользователем! Куки сохранены.")
                } else {
                    GeekModeLogger.log("YandexCaptcha", "❌ Капча не была пройдена или отменена.")
                }
                return@withContext dialogResult
            } else {
                if (!allowInteractive) {
                    Log.w(AppConfig.TAG, "YandexCaptchaSolver: interactive dialog disabled (background/sequential mode)")
                    GeekModeLogger.log("YandexCaptcha", "⚠️ Интерактивный диалог капчи пропущен (фоновый/последовательный режим)")
                } else {
                    Log.w(AppConfig.TAG, "YandexCaptchaSolver: no active activity for interactive dialog, skipping")
                    GeekModeLogger.log("YandexCaptcha", "⚠️ Нет активного Activity для показа диалога капчи, пропуск.")
                }
                return@withContext false
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        if (context is Activity) return context
        return AngApplication.getCurrentActivity()
    }

    private val SOLVER_JS = com.kiktor.v2whitelist.core.domain.captcha.YandexCaptchaDetector.SOLVER_JS

    private fun getCombinedCookies(): String {
        val cm = CookieManager.getInstance()
        val domains = com.kiktor.v2whitelist.core.domain.captcha.YandexCaptchaDetector.YANDEX_DOMAINS
        var res = ""
        for (d in domains) {
            val c = cm.getCookie(d).orEmpty()
            if (c.isNotEmpty()) {
                res = mergeCookies(res, c)
            }
        }
        return res
    }

    fun isSolved(url: String?, cookies: String, bodyText: String? = null): Boolean {
        return com.kiktor.v2whitelist.core.domain.captcha.YandexCaptchaDetector.isSolved(url, cookies, bodyText)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun trySilentSolve(context: Context, url: String): Boolean {
        var webView: WebView? = null
        var monitorJob: kotlinx.coroutines.Job? = null
        try {
            val deferred = CompletableDeferred<Boolean>()
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            // Передаем существующие куки в WebView
            val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
            if (existing.isNotBlank()) {
                existing.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie("https://translate.yandex.ru", trimmed)
                        cookieManager.setCookie("https://yandex.ru", trimmed)
                    }
                }
            }

            webView = WebView(context.applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = YandexTranslateUpdater.USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, currentUrl: String?) {
                        val cookies = getCombinedCookies()
                        if (isSolved(currentUrl, cookies)) {
                            saveCookies(cookies)
                            deferred.complete(true)
                        } else {
                            view.evaluateJavascript(SOLVER_JS) { res ->
                                val cleaned = res?.replace("\"", "").orEmpty()
                                if (cleaned == "CONFIGS_FOUND" || cleaned == "CLEAN_TRANSLATE") {
                                    saveCookies(getCombinedCookies())
                                    deferred.complete(true)
                                }
                            }
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, currentUrl: String?, isReload: Boolean) {
                        val cookies = getCombinedCookies()
                        if (isSolved(currentUrl, cookies)) {
                            saveCookies(cookies)
                            deferred.complete(true)
                        }
                    }
                }
            }

            monitorJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                while (!deferred.isCompleted) {
                    kotlinx.coroutines.delay(400)
                    webView?.evaluateJavascript(SOLVER_JS) { res ->
                        val cleaned = res?.replace("\"", "").orEmpty()
                        val cookies = getCombinedCookies()
                        if (cleaned == "CONFIGS_FOUND" || cleaned == "CLEAN_TRANSLATE" || isSolved(webView?.url, cookies)) {
                            saveCookies(cookies)
                            deferred.complete(true)
                        }
                    }
                }
            }

            webView.loadUrl(url)

            return withTimeoutOrNull(8000) {
                deferred.await()
            } ?: false
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "YandexCaptchaSolver: silent solve exception: ${e.message}")
            return false
        } finally {
            monitorJob?.cancel()
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (ignored: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun showInteractiveDialog(activity: Activity, url: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        var dialog: AlertDialog? = null
        var webView: WebView? = null
        var monitorJob: kotlinx.coroutines.Job? = null

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
            if (existing.isNotBlank()) {
                existing.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie("https://translate.yandex.ru", trimmed)
                        cookieManager.setCookie("https://yandex.ru", trimmed)
                    }
                }
            }

            val density = activity.resources.displayMetrics.density
            webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (420 * density).toInt()
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = YandexTranslateUpdater.USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, currentUrl: String?) {
                        val cookies = getCombinedCookies()
                        if (isSolved(currentUrl, cookies)) {
                            saveCookies(cookies)
                            dialog?.dismiss()
                            deferred.complete(true)
                        } else {
                            view.evaluateJavascript(SOLVER_JS) { res ->
                                val cleaned = res?.replace("\"", "").orEmpty()
                                if (cleaned == "CONFIGS_FOUND" || cleaned == "CLEAN_TRANSLATE") {
                                    saveCookies(getCombinedCookies())
                                    dialog?.dismiss()
                                    deferred.complete(true)
                                }
                            }
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, currentUrl: String?, isReload: Boolean) {
                        val cookies = getCombinedCookies()
                        if (isSolved(currentUrl, cookies)) {
                            saveCookies(cookies)
                            dialog?.dismiss()
                            deferred.complete(true)
                        }
                    }
                }
            }

            monitorJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                while (!deferred.isCompleted) {
                    kotlinx.coroutines.delay(400)
                    webView?.evaluateJavascript(SOLVER_JS) { res ->
                        val cleaned = res?.replace("\"", "").orEmpty()
                        val cookies = getCombinedCookies()
                        if (cleaned == "CONFIGS_FOUND" || cleaned == "CLEAN_TRANSLATE" || isSolved(webView?.url, cookies)) {
                            saveCookies(cookies)
                            dialog?.dismiss()
                            deferred.complete(true)
                        }
                    }
                }
            }

            val container = FrameLayout(activity).apply {
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                addView(webView)
            }

            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.title_yandex_captcha))
                .setMessage(activity.getString(R.string.msg_yandex_captcha))
                .setView(container)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    deferred.complete(false)
                }
                .setOnCancelListener {
                    deferred.complete(false)
                }
                .create()

            dialog.show()
            webView.loadUrl(url)

            return withTimeoutOrNull(60000) {
                deferred.await()
            } ?: false
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "YandexCaptchaSolver: interactive dialog exception: ${e.message}")
            return false
        } finally {
            monitorJob?.cancel()
            try {
                dialog?.dismiss()
                webView?.stopLoading()
                webView?.destroy()
            } catch (ignored: Exception) {}
        }
    }

    fun mergeCookies(oldCookies: String, newCookies: String): String {
        return com.kiktor.v2whitelist.core.domain.captcha.YandexCaptchaDetector.mergeCookies(oldCookies, newCookies)
    }

    fun saveCookies(cookies: String) {
        if (cookies.isBlank()) return
        val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
        val merged = mergeCookies(existing, cookies)
        MmkvManager.encodeSettings(AppConfig.PREF_YANDEX_COOKIES, merged)
        Log.i(AppConfig.TAG, "YandexCaptchaSolver: updated saved cookies (spravka=${merged.contains("spravka=")})")
    }

    fun clearCookies() {
        MmkvManager.encodeSettings(AppConfig.PREF_YANDEX_COOKIES, "")
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (ignored: Exception) {}
        Log.i(AppConfig.TAG, "YandexCaptchaSolver: cookies cleared")
        GeekModeLogger.log("YandexCaptcha", "🗑️ Сессия и cookies Яндекса сброшены.")
    }
}
