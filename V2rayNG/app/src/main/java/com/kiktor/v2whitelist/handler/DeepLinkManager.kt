package com.kiktor.v2whitelist.handler

import android.util.Base64
import com.google.gson.Gson
import com.kiktor.v2whitelist.AppConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import android.util.Log

object DeepLinkManager {

    private val gson = Gson()

    /**
     * Сжимает и кодирует любой объект в Base64 для передачи через DeepLink.
     */
    fun encodeToDeepLinkData(obj: Any): String {
        return try {
            val json = gson.toJson(obj)
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            val base64 = Base64.encodeToString(bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            base64
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to encode deep link data", e)
            ""
        }
    }

    /**
     * Распаковывает и парсит данные из Base64 GZIP в нужный класс.
     */
    fun <T> decodeFromDeepLinkData(data: String, clazz: Class<T>): T? {
        return try {
            val bytes = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to decode deep link data", e)
            null
        }
    }

    // Модели данных для шеринга

    data class SharedSubscription(
        val name: String,
        val url: String,
        val filter: String = "",
        val groupRegex: String = ""
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
