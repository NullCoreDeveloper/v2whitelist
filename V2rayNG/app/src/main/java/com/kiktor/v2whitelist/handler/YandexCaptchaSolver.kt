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
     * @return true if captcha was resolved and cookies updated, false otherwise.
     */
    suspend fun solve(context: Context, captchaUrl: String): Boolean = mutex.withLock {
        val currentCookies = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
        if (currentCookies.contains("spravka=")) {
            Log.i(AppConfig.TAG, "YandexCaptchaSolver: valid spravka already in cookies, testing if challenge bypassed")
        }

        withContext(Dispatchers.Main) {
            val activity = findActivity(context)

            // 1. Попытка тихого решения в фоновом WebView
            val silentSuccess = trySilentSolve(context, captchaUrl)
            if (silentSuccess) {
                Log.i(AppConfig.TAG, "YandexCaptchaSolver: silent challenge passed successfully!")
                return@withContext true
            }

            // 2. Интерактивный диалог с пользователем
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                Log.i(AppConfig.TAG, "YandexCaptchaSolver: silent pass insufficient, launching interactive dialog")
                return@withContext showInteractiveDialog(activity, captchaUrl)
            } else {
                Log.w(AppConfig.TAG, "YandexCaptchaSolver: no active activity for interactive dialog, skipping")
                return@withContext false
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        if (context is Activity) return context
        return AngApplication.getCurrentActivity()
    }

    private fun getCombinedCookies(): String {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://translate.yandex.ru").orEmpty()
        val c2 = cm.getCookie("https://yandex.ru").orEmpty()
        return mergeCookies(c1, c2)
    }

    fun isSolved(url: String?, cookies: String): Boolean {
        if (cookies.contains("spravka=")) {
            return true
        }
        val lowerUrl = url?.lowercase().orEmpty()
        val isCaptchaUrl = lowerUrl.contains("showcaptcha") ||
                lowerUrl.contains("captcha.yandex") ||
                lowerUrl.contains("smartcaptcha")
        return !isCaptchaUrl && lowerUrl.contains("translate.yandex.ru") && cookies.contains("yandexuid=")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun trySilentSolve(context: Context, url: String): Boolean {
        var webView: WebView? = null
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

            webView.loadUrl(url)

            return withTimeoutOrNull(4000) {
                deferred.await()
            } ?: false
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "YandexCaptchaSolver: silent solve exception: ${e.message}")
            return false
        } finally {
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
                            deferred.complete(true)
                            dialog?.dismiss()
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, currentUrl: String?, isReload: Boolean) {
                        val cookies = getCombinedCookies()
                        if (isSolved(currentUrl, cookies)) {
                            saveCookies(cookies)
                            deferred.complete(true)
                            dialog?.dismiss()
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
            try {
                dialog?.dismiss()
                webView?.stopLoading()
                webView?.destroy()
            } catch (ignored: Exception) {}
        }
    }

    fun mergeCookies(oldCookies: String, newCookies: String): String {
        val map = linkedMapOf<String, String>()
        fun parse(str: String) {
            str.split(";").forEach { part ->
                val trimmed = part.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
        }
        parse(oldCookies)
        parse(newCookies)
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun saveCookies(cookies: String) {
        if (cookies.isBlank()) return
        val existing = MmkvManager.decodeSettingsString(AppConfig.PREF_YANDEX_COOKIES, "").orEmpty()
        val merged = mergeCookies(existing, cookies)
        MmkvManager.encodeSettings(AppConfig.PREF_YANDEX_COOKIES, merged)
        Log.i(AppConfig.TAG, "YandexCaptchaSolver: updated saved cookies (spravka=${merged.contains("spravka=")})")
    }
}
