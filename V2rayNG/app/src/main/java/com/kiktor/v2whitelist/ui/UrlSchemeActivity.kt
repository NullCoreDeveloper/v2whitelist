package com.kiktor.v2whitelist.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.config.UniversalConfigParser
import com.kiktor.v2whitelist.core.config.security.ParseGuard
import com.kiktor.v2whitelist.core.subscription.network.NetworkSecurityGuard
import com.kiktor.v2whitelist.databinding.ActivityLogcatBinding
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.extension.toastError
import com.kiktor.v2whitelist.handler.AngConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    companion object {
        private val ALLOWED_IMPORT_SCHEMES = setOf(
            "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "wireguard", "socks", "socks5", "https", "http"
        )

        /**
         * Фильтрует внешние ссылки перед импортом: блокирует file://, content://, javascript://,
         * управляющие символы и локальные/SSRF HTTP-адреса.
         */
        fun sanitizeIncomingLink(raw: String?): String? {
            val text = raw?.trim() ?: return null
            if (text.isEmpty() || text.length > ParseGuard.MAX_LINK_CHARS) return null
            if (text.any { it == '\n' || it == '\r' || it.isISOControl() }) return null
            val scheme = text.substringBefore("://", "").lowercase()
            if (scheme !in ALLOWED_IMPORT_SCHEMES) return null
            if ((scheme == "http" || scheme == "https") &&
                NetworkSecurityGuard.validateExternalUrlString(text, allowHttp = true) == null
            ) {
                return null
            }
            return text
        }

        fun buildImportPreview(link: String): String {
            val scheme = link.substringBefore("://", "").lowercase()
            if (scheme == "http" || scheme == "https") {
                val hostPortPair = NetworkSecurityGuard.extractHostAndPort(link)
                if (hostPortPair != null) {
                    val (host, port) = hostPortPair
                    val hostPort = if (port == null) host else "$host:$port"
                    return "$scheme://$hostPort"
                }
            }
            val parsed = UniversalConfigParser.parse(link)
            if (parsed != null && !parsed.server.isNullOrBlank()) {
                return "${parsed.configType.name} · ${parsed.server}:${parsed.serverPort.orEmpty()}"
            }
            return scheme
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            var handledDialog = false
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            handledDialog = parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    when (data?.host) {
                        "install-config", "install-sub" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            handledDialog = parseUri(shareUrl, uri?.fragment)
                        }

                        else -> {
                            toastError(R.string.toast_failure)
                        }
                    }
                }
            }

            if (!handledDialog) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing URL scheme", e)
            finish()
        }
    }

    private fun parseUri(uriString: String?, fragment: String?): Boolean {
        if (uriString.isNullOrEmpty()) {
            return false
        }

        var decodedUrl = try {
            URLDecoder.decode(uriString, "UTF-8")
        } catch (_: Exception) {
            uriString
        }
        if (!fragment.isNullOrEmpty() && !decodedUrl.contains("#")) {
            decodedUrl += "#${fragment}"
        }

        val sanitized = sanitizeIncomingLink(decodedUrl)
        if (sanitized == null) {
            toastError(R.string.toast_failure)
            return false
        }

        val preview = buildImportPreview(sanitized)
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.dialog_add_custom_sub_msg, sanitized.substringBefore("://").uppercase(), preview))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val (count, countSub) = AngConfigManager.importBatchConfig(sanitized, "", false)
                    withContext(Dispatchers.Main) {
                        if (count + countSub > 0) {
                            toast(R.string.import_subscription_success)
                        } else {
                            toast(R.string.import_subscription_failure)
                        }
                        startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
                        finish()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
        return true
    }
}
