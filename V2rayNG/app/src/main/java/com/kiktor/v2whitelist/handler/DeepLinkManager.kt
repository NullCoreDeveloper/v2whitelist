package com.kiktor.v2whitelist.handler

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.core.config.security.ParseGuard
import com.kiktor.v2whitelist.core.subscription.network.NetworkSecurityGuard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object DeepLinkManager {

    private val gson = Gson()

    const val MAX_ENCODED_CHARS = 64 * 1024
    const val MAX_DECOMPRESSED_BYTES = 256 * 1024
    const val MAX_SPLIT_PACKAGES = 500
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * Сжимает и кодирует любой объект в Base64 для передачи через DeepLink.
     */
    fun encodeToDeepLinkData(obj: Any): String {
        return try {
            val json = gson.toJson(obj)
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            Base64.encodeToString(bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to encode deep link data", e)
            ""
        }
    }

    /**
     * Распаковывает и парсит данные из Base64 GZIP в нужный класс с защитой от GZIP/JSON-бомб.
     */
    fun <T> decodeFromDeepLinkData(data: String, clazz: Class<T>): T? {
        if (data.isBlank() || data.length > MAX_ENCODED_CHARS) {
            return null
        }
        return try {
            val bytes = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
            val decompressed = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        throw SecurityException("DeepLink GZIP payload exceeds size limit")
                    }
                    decompressed.write(buffer, 0, read)
                }
            }
            val json = decompressed.toString(Charsets.UTF_8.name())
            if (!ParseGuard.checkJsonBudget(json)) {
                return null
            }
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to decode deep link data", e)
            null
        }
    }

    fun validateSharedSubscription(sub: SharedSubscription?): Boolean {
        if (sub == null) return false
        val name = sub.name.trim()
        if (name.isEmpty() || name.length > 200 || name.any(Char::isISOControl)) return false
        val mirrors = sub.url.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (mirrors.isEmpty() || mirrors.size > 32) return false
        return mirrors.all { NetworkSecurityGuard.validateExternalUrlString(it, allowHttp = true) != null }
    }

    fun validateSharedSplitTunneling(split: SharedSplitTunneling?): Boolean {
        if (split == null) return false
        val name = split.name.trim()
        if (name.length > 200 || name.any(Char::isISOControl)) return false
        if (split.packages.size > MAX_SPLIT_PACKAGES) return false
        return split.packages.all { pkg ->
            pkg.length <= 255 && PACKAGE_NAME_REGEX.matches(pkg)
        }
    }

    // Модели данных для шеринга

    data class SharedSubscription(
        val name: String,
        val url: String,
        val filter: String = "",
        val groupRegex: String = "",
        val targetScenarios: List<String> = emptyList()
    )

    data class SharedSplitTunneling(
        val name: String = "Custom Preset",
        val bypassMode: Boolean = false,
        val packages: List<String>
    )

    const val SCHEME = "v2whitelist"
    const val SCHEME_SUB = "v2w-sub"
    const val SCHEME_SPLIT = "v2w-split"
    const val HOST_SUB = "sub"
    const val HOST_SPLIT = "split"
}
