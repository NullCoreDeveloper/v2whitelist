package com.kiktor.v2whitelist.core.domain.captcha

/**
 * Кроссплатформенный детектор капчи Яндекса (SmartCaptcha / ShowCaptcha),
 * валидатор решения и утилита слияния HTTP-cookie.
 */
object YandexCaptchaDetector {

    const val SOLVER_JS = """
        (function() {
            try {
                var cb = document.querySelector('.CheckboxCaptcha-Button, .SmartCaptcha-Button, .CheckboxCaptcha-Anchor, input[type="checkbox"], [data-testid="checkbox-captcha"]');
                if (cb && !cb.getAttribute('data-clicked')) {
                    cb.setAttribute('data-clicked', 'true');
                    cb.click();
                }
                var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
                if (text.indexOf('vless://') !== -1 || text.indexOf('vmess://') !== -1 || 
                    text.indexOf('trojan://') !== -1 || text.indexOf('ss://') !== -1 ||
                    text.indexOf('hysteria2://') !== -1 || text.indexOf('hy2://') !== -1) {
                    return 'CONFIGS_FOUND';
                }
                var isCaptcha = !!cb || location.href.indexOf('showcaptcha') !== -1 || location.href.indexOf('captcha.yandex') !== -1;
                if (!isCaptcha && location.href.indexOf('translate.yandex') !== -1) {
                    return 'CLEAN_TRANSLATE';
                }
                return isCaptcha ? 'CAPTCHA_PENDING' : 'READY';
            } catch(e) {
                return 'ERROR';
            }
        })();
    """

    val YANDEX_DOMAINS = listOf(
        "https://translate.yandex.ru",
        "https://yandex.ru",
        "https://captcha.yandex.ru",
        "https://smartcaptcha.yandexcloud.net",
        "https://yandex.com",
        "https://.yandex.ru"
    )

    /**
     * Проверяет, является ли URL страницей проверки Яндекс-капчи.
     */
    fun isCaptchaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("showcaptcha") ||
                lower.contains("captcha.yandex") ||
                lower.contains("smartcaptcha")
    }

    /**
     * Проверяет, содержит ли ответ сервера признак капчи.
     */
    fun isCaptchaResponse(statusCode: Int, body: String? = null, locationHeader: String? = null): Boolean {
        if (locationHeader != null && isCaptchaUrl(locationHeader)) {
            return true
        }
        if (statusCode == 403 || statusCode == 302) {
            if (locationHeader != null && isCaptchaUrl(locationHeader)) return true
        }
        if (!body.isNullOrBlank()) {
            val lowerBody = body.lowercase()
            if (lowerBody.contains("checkboxcaptcha") ||
                lowerBody.contains("smartcaptcha") ||
                lowerBody.contains("showcaptcha")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Проверяет, была ли капча успешно пройдена:
     * 1. В теле ответа обнаружены прокси-конфиги.
     * 2. В cookies появилась валидная сессионная метка 'spravka='.
     * 3. Страница не является капчей и получена 'yandexuid='.
     */
    fun isSolved(url: String?, cookies: String, bodyText: String? = null): Boolean {
        if (!bodyText.isNullOrBlank()) {
            val lowerBody = bodyText.lowercase()
            if (lowerBody.contains("vless://") ||
                lowerBody.contains("vmess://") ||
                lowerBody.contains("trojan://") ||
                lowerBody.contains("ss://") ||
                lowerBody.contains("hysteria2://") ||
                lowerBody.contains("hy2://")
            ) {
                return true
            }
        }

        if (cookies.contains("spravka=")) {
            return true
        }

        val lowerUrl = url?.lowercase().orEmpty()
        val isCaptcha = isCaptchaUrl(lowerUrl)
        return !isCaptcha && (lowerUrl.contains("translate.yandex") || lowerUrl.contains("yandex.ru")) && cookies.contains("yandexuid=")
    }

    /**
     * Объединяет две строки cookie по именам параметров (более новые заменяют старые).
     */
    fun mergeCookies(existing: String, incoming: String): String {
        if (existing.isBlank()) return incoming
        if (incoming.isBlank()) return existing

        val cookieMap = linkedMapOf<String, String>()

        fun parseAndPut(cookieHeader: String) {
            cookieHeader.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isNotEmpty()) {
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        cookieMap[key] = value
                    }
                }
            }
        }

        parseAndPut(existing)
        parseAndPut(incoming)

        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
