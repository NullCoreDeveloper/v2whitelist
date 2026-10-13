package com.kiktor.v2whitelist.core.domain

import com.kiktor.v2whitelist.core.domain.captcha.YandexCaptchaDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YandexCaptchaDetectorTest {

    @Test
    fun testIsCaptchaUrl() {
        assertTrue(YandexCaptchaDetector.isCaptchaUrl("https://yandex.ru/showcaptcha?cc=1"))
        assertTrue(YandexCaptchaDetector.isCaptchaUrl("https://captcha.yandex.net/check"))
        assertTrue(YandexCaptchaDetector.isCaptchaUrl("https://smartcaptcha.yandexcloud.net/captcha"))
        assertFalse(YandexCaptchaDetector.isCaptchaUrl("https://translate.yandex.ru/?source=en"))
        assertFalse(YandexCaptchaDetector.isCaptchaUrl(null))
    }

    @Test
    fun testIsCaptchaResponse() {
        assertTrue(YandexCaptchaDetector.isCaptchaResponse(302, locationHeader = "https://yandex.ru/showcaptcha"))
        assertTrue(YandexCaptchaDetector.isCaptchaResponse(200, body = "<html><div class=\"CheckboxCaptcha-Anchor\"></div></html>"))
        assertFalse(YandexCaptchaDetector.isCaptchaResponse(200, body = "<html><body>Clean content</body></html>"))
    }

    @Test
    fun testIsSolved() {
        // 1. По куки 'spravka'
        assertTrue(YandexCaptchaDetector.isSolved("https://yandex.ru/showcaptcha", "spravka=12345; yandexuid=6789"))

        // 2. По найденным конфигам
        assertTrue(YandexCaptchaDetector.isSolved("https://yandex.ru/showcaptcha", "", bodyText = "vless://abcd-1234@server.com:443"))

        // 3. По yandexuid на чистом translate.yandex.ru
        assertTrue(YandexCaptchaDetector.isSolved("https://translate.yandex.ru/", "yandexuid=1234567890"))

        // 4. На странице showcaptcha без spravka (еще не решена)
        assertFalse(YandexCaptchaDetector.isSolved("https://yandex.ru/showcaptcha", "yandexuid=1234567890"))
    }

    @Test
    fun testMergeCookies() {
        val oldCookies = "yandexuid=111; session=abc"
        val newCookies = "session=xyz; spravka=token999"

        val merged = YandexCaptchaDetector.mergeCookies(oldCookies, newCookies)
        assertTrue(merged.contains("yandexuid=111"))
        assertTrue(merged.contains("session=xyz"))
        assertTrue(merged.contains("spravka=token999"))
        assertFalse(merged.contains("session=abc"))
    }
}
