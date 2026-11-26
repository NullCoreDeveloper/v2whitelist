package com.kiktor.v2whitelist.core.config

import com.kiktor.v2whitelist.core.config.security.ParseGuard
import com.kiktor.v2whitelist.core.config.security.XrayConfigSanitizer
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseGuardAndSanitizerTest {

    @Test
    fun parseGuardEnforcesLinkQueryAndJsonLimits() {
        assertFalse(ParseGuard.checkLinkLength("vless://" + "a".repeat(ParseGuard.MAX_LINK_CHARS + 1)))
        assertFalse(ParseGuard.checkLinkLength("vless://id@host:443\nvless://id2@host:443"))
        assertTrue(ParseGuard.checkLinkLength("vless://id@host:443?security=tls"))

        val tooManyParams = (1..(ParseGuard.MAX_QUERY_PARAMS + 1)).joinToString("&") { "k$it=v" }
        assertFalse(ParseGuard.checkQueryParamCount(tooManyParams))
        assertTrue(ParseGuard.checkQueryParamCount("a=1&b=2"))

        val deepJson = "[".repeat(ParseGuard.MAX_JSON_DEPTH + 1) + "]".repeat(ParseGuard.MAX_JSON_DEPTH + 1)
        assertFalse(ParseGuard.checkJsonBudget(deepJson))
        assertTrue(ParseGuard.checkJsonBudget("""{"outbounds":[{"protocol":"vless"}]}"""))
    }

    @Test
    fun sanitizerStripsDangerousFieldsAndUnsafeBalancers() {
        val raw = """
            {
              "outbounds": [
                {
                  "tag": "proxy-1",
                  "protocol": "vless",
                  "streamSettings": {
                    "security": "tls",
                    "tlsSettings": {
                      "serverName": "example.com",
                      "allowInsecure": true,
                      "masterKeyLog": "/tmp/keys.log",
                      "certificates": [{"certificateFile": "/etc/passwd", "keyFile": "/etc/shadow"}]
                    },
                    "xhttpSettings": {
                      "extra": {
                        "downloadSettings": {
                          "security": "tls",
                          "tlsSettings": { "allowInsecure": true }
                        }
                      }
                    }
                  }
                },
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ],
              "routing": {
                "balancers": [
                  { "tag": "bad-bal", "selector": ["dir"], "fallbackTag": "block" },
                  { "tag": "good-bal", "selector": ["proxy"], "fallbackTag": "direct" }
                ]
              }
            }
        """.trimIndent()

        val sanitized = XrayConfigSanitizer.sanitizeJson(raw, stripAllowInsecure = true)
        assertNotNull(sanitized)
        assertFalse(sanitized.contains("masterKeyLog"))
        assertFalse(sanitized.contains("certificateFile"))
        assertFalse(sanitized.contains("keyFile"))
        assertFalse(sanitized.contains("allowInsecure"))
        assertFalse(sanitized.contains("bad-bal"))
        assertTrue(sanitized.contains("good-bal"))
        // fallbackTag "direct" должен быть удалён, а selector сужен до реального proxy-тега
        assertFalse(sanitized.contains("\"fallbackTag\":\"direct\""))
    }

    @Test
    fun profileItemIsSecureRejectsWeakCiphersAndMissingTls() {
        val ssWeak = ProfileItem(configType = EConfigType.SHADOWSOCKS, method = "rc4-md5")
        assertFalse(ssWeak.isSecure())
        assertNotNull(ssWeak.securityIssueReason())

        val ssStrong = ProfileItem(configType = EConfigType.SHADOWSOCKS, method = "aes-256-gcm")
        assertTrue(ssStrong.isSecure())
        assertNull(ssStrong.securityIssueReason())

        val ss2022 = ProfileItem(configType = EConfigType.SHADOWSOCKS, method = "2022-blake3-aes-128-gcm")
        assertTrue(ss2022.isSecure())

        val vlessPlain = ProfileItem(configType = EConfigType.VLESS, security = "none")
        assertFalse(vlessPlain.isSecure())

        val vlessTls = ProfileItem(configType = EConfigType.VLESS, security = "reality")
        assertTrue(vlessTls.isSecure())

        val xhttpInsecureDownload = ProfileItem(
            configType = EConfigType.VLESS,
            security = "reality",
            network = "xhttp",
            xhttpExtra = """{"downloadSettings":{"security":"tls","tlsSettings":{"allowInsecure":true}}}"""
        )
        assertFalse(xhttpInsecureDownload.isSecure())
    }
}
