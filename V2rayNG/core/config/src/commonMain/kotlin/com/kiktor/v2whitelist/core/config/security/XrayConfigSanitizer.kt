package com.kiktor.v2whitelist.core.config.security

import com.kiktor.v2whitelist.core.model.ProfileItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Санитайзер и аудитор безопасности для сторонних JSON-конфигураций Xray и параметров xhttp.
 */
object XrayConfigSanitizer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val RESERVED_SYSTEM_TAGS = setOf("direct", "block", "blocked", "dns-out")
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "wireguard")

    /**
     * Очищает сторонний Xray JSON-конфиг от опасных файловых/отладочных опций
     * (masterKeyLog, certificateFile, keyFile, masquerade.dir/insecure)
     * и небезопасных балансеров/allowInsecure.
     */
    fun sanitizeJson(rawJson: String, stripAllowInsecure: Boolean = true): String? {
        if (!ParseGuard.checkJsonBudget(rawJson)) return null
        val parsed = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val sanitizedObj = sanitizeElement(parsed, parentKey = "", stripAllowInsecure = stripAllowInsecure) as? JsonObject
            ?: return null

        val cleanedWithSafeRouting = sanitizeRoutingBalancers(sanitizedObj)
        return cleanedWithSafeRouting.toString()
    }

    private fun sanitizeElement(element: JsonElement, parentKey: String, stripAllowInsecure: Boolean): JsonElement {
        return when (element) {
            is JsonObject -> {
                val parentLower = parentKey.lowercase()
                val filtered = LinkedHashMap<String, JsonElement>()
                for ((key, value) in element) {
                    val keyLower = key.lowercase()
                    if (shouldDropField(keyLower, parentLower, stripAllowInsecure)) continue
                    filtered[key] = sanitizeElement(value, key, stripAllowInsecure)
                }
                JsonObject(filtered)
            }
            is JsonArray -> JsonArray(element.map { sanitizeElement(it, parentKey, stripAllowInsecure) })
            else -> element
        }
    }

    private fun shouldDropField(keyLower: String, parentLower: String, stripAllowInsecure: Boolean): Boolean {
        if ((parentLower == "tlssettings" || parentLower == "realitysettings") && keyLower == "masterkeylog") {
            return true
        }
        if (parentLower == "certificates" && (keyLower == "certificatefile" || keyLower == "keyfile")) {
            return true
        }
        if (parentLower == "masquerade" && (keyLower == "dir" || keyLower == "insecure")) {
            return true
        }
        if (stripAllowInsecure) {
            if (keyLower == "allowinsecure" || keyLower == "allow_insecure") return true
            if (keyLower == "insecure" && (parentLower == "tlssettings" || parentLower == "downloadsettings")) return true
        }
        return false
    }

    private fun sanitizeRoutingBalancers(root: JsonObject): JsonObject {
        val routing = root["routing"] as? JsonObject ?: return root
        val balancers = routing["balancers"] as? JsonArray ?: return root
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        val proxyTags = outbounds.filter {
            it.str("protocol")?.lowercase() in PROXY_PROTOCOLS
        }.mapNotNull { it.str("tag") }

        val nonProxyTags = outbounds.filter {
            it.str("protocol")?.lowercase() !in PROXY_PROTOCOLS
        }.mapNotNull { it.str("tag") } + RESERVED_SYSTEM_TAGS

        val safeBalancers = balancers.filterIsInstance<JsonObject>().mapNotNull { balancer ->
            val prefixes = (balancer["selector"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            val matchedProxyTags = proxyTags.filter { tag -> prefixes.any { prefix -> tag.startsWith(prefix) } }.distinct()
            val leaksToNonProxy = nonProxyTags.any { nonProxyTag ->
                prefixes.any { prefix -> nonProxyTag.startsWith(prefix) }
            }
            if (matchedProxyTags.isEmpty() || leaksToNonProxy) {
                return@mapNotNull null
            }

            val updated = LinkedHashMap<String, JsonElement>(balancer)
            updated["selector"] = JsonArray(matchedProxyTags.map(::JsonPrimitive))
            val fallback = balancer.str("fallbackTag")
            if (fallback != null && (fallback in nonProxyTags || fallback !in proxyTags)) {
                updated.remove("fallbackTag")
            }
            JsonObject(updated)
        }

        val updatedRouting = LinkedHashMap<String, JsonElement>(routing)
        updatedRouting["balancers"] = JsonArray(safeBalancers)
        val updatedRoot = LinkedHashMap<String, JsonElement>(root)
        updatedRoot["routing"] = JsonObject(updatedRouting)
        return JsonObject(updatedRoot)
    }

    /**
     * Проверяет параметры `downloadSettings` внутри поля `extra` у xhttp/splithttp на слабое или отключённое шифрование.
     */
    fun findXhttpExtraSecurityIssue(xhttpExtra: String?): String? {
        val raw = xhttpExtra?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!ParseGuard.checkJsonBudget(raw)) return "Некорректная или слишком глубокая структура xhttp extra"
        val extra = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val download = extra["downloadSettings"] as? JsonObject ?: return null
        return findStreamSecurityIssue(download)
    }

    /**
     * Проводит аудит безопасности кастомного JSON-конфига Xray.
     * Возвращает описание проблемы или null, если замечаний не найдено.
     */
    fun findCustomJsonSecurityIssue(rawJson: String): String? {
        if (!ParseGuard.checkJsonBudget(rawJson)) return "Превышен бюджет разбора JSON"
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val proxyOutbounds = outbounds.filter { it.str("protocol")?.lowercase() in PROXY_PROTOCOLS }
        if (proxyOutbounds.isEmpty()) return null

        for (outbound in proxyOutbounds) {
            val proto = outbound.str("protocol")?.lowercase().orEmpty()
            val settings = outbound["settings"] as? JsonObject
            val stream = outbound["streamSettings"] as? JsonObject

            stream?.let { st ->
                findStreamSecurityIssue(st)?.let { return it }
                val xhttp = (st["xhttpSettings"] as? JsonObject) ?: (st["splithttpSettings"] as? JsonObject)
                val extraObj = (xhttp?.get("extra") as? JsonObject) ?: xhttp
                (extraObj?.get("downloadSettings") as? JsonObject)?.let { dl ->
                    findStreamSecurityIssue(dl)?.let { return it }
                }
            }

            val streamSec = stream?.str("security")?.lowercase().orEmpty()
            when (proto) {
                "vless" -> {
                    val enc = firstUser(settings)?.str("encryption")?.lowercase().orEmpty()
                    val hasStrongEnc = enc.isNotEmpty() && enc != "none"
                    if (streamSec !in setOf("tls", "reality", "xtls") && !hasStrongEnc) {
                        return "VLESS без TLS/Reality шифрования"
                    }
                }
                "vmess" -> {
                    val scy = firstUser(settings)?.str("security")?.lowercase().orEmpty()
                    if (streamSec !in setOf("tls", "reality", "xtls") && scy in setOf("none", "zero")) {
                        return "VMess без шифрования"
                    }
                }
                "trojan", "hysteria", "hysteria2" -> {
                    if (streamSec == "none") {
                        return "Отключено TLS-шифрование"
                    }
                }
                "shadowsocks" -> {
                    val method = firstServer(settings)?.str("method")?.lowercase()
                        ?: settings?.str("method")?.lowercase().orEmpty()
                    if (!ProfileItem.isSupportedAeadShadowsocks(method)) {
                        return "Устаревший или отсутствующий шифр Shadowsocks"
                    }
                }
            }
        }
        return null
    }

    private fun findStreamSecurityIssue(stream: JsonObject): String? {
        val tls = stream["tlsSettings"] as? JsonObject
        val pinned = tls?.str("pinnedPeerCertSha256")
        if (tls?.bool("allowInsecure") == true && pinned == null) {
            return "Отключена проверка TLS-сертификата"
        }
        val sec = stream.str("security")?.lowercase().orEmpty()
        if (sec == "none" || sec.isEmpty()) {
            return "Транспорт без TLS-шифрования"
        }
        return null
    }

    private fun firstServer(settings: JsonObject?): JsonObject? {
        if (settings == null) return null
        val servers = (settings["servers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val vnext = (settings["vnext"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return servers.firstOrNull() ?: vnext.firstOrNull() ?: settings
    }

    private fun firstUser(settings: JsonObject?): JsonObject? {
        val srv = firstServer(settings) ?: return null
        return (srv["users"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull() ?: srv
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") }
}
