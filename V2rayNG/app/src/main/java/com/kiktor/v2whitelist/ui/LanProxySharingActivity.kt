package com.kiktor.v2whitelist.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.SettingsChangeManager
import com.kiktor.v2whitelist.handler.SettingsManager
import com.kiktor.v2whitelist.util.QRCodeDecoder
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** Экран раздачи SOCKS5/HTTP прокси по локальной сети: IP, порты, QR и подключённые клиенты. */
class LanProxySharingActivity : BaseActivity() {
    private lateinit var tvInfo: TextView
    private lateinit var tvClients: TextView
    private lateinit var tvHint: TextView
    private lateinit var ivQr: ImageView
    private var qrMode = 0 // 0 = Telegram, 1 = socks5://

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sw = MaterialSwitch(this).apply {
            setText(R.string.lan_sharing_switch)
            isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
            setOnCheckedChangeListener { _, checked ->
                MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, checked)
                SettingsChangeManager.makeRestartService()
                toast(R.string.lan_sharing_restart_hint)
                refresh()
            }
        }
        column.addView(sw)
        tvHint = TextView(this).apply { alpha = 0.7f; setPadding(0, pad / 2, 0, pad / 2) }
        column.addView(tvHint)
        tvInfo = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad / 2) }
        column.addView(tvInfo)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(text: Int, onClick: () -> Unit) = MaterialButton(this).apply {
            setText(text); setOnClickListener { onClick() }
        }.also { btnRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        btn(R.string.lan_sharing_qr_tg) { qrMode = 0; refresh() }
        btn(R.string.lan_sharing_qr_socks) { qrMode = 1; refresh() }
        column.addView(btnRow)

        ivQr = ImageView(this).apply { adjustViewBounds = true }
        column.addView(ivQr, LinearLayout.LayoutParams((280 * dp).toInt(), (280 * dp).toInt()).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        column.addView(MaterialButton(this).apply {
            setText(R.string.lan_sharing_copy)
            setOnClickListener { currentLink()?.let { Utils.setClipboard(this@LanProxySharingActivity, it); toast(R.string.toast_success) } }
        })
        tvClients = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) }
        column.addView(tvClients)
        column.addView(TextView(this).apply { text = getString(R.string.lan_sharing_both_same_lan); alpha = 0.6f; setPadding(0, pad, 0, 0) })

        setContentViewWithToolbar(ScrollView(this).apply { addView(column) }, true, getString(R.string.title_lan_sharing))
        refresh()
        lifecycleScope.launch {
            while (isActive) {
                val clients = withContext(Dispatchers.IO) { readClients() }
                tvClients.text = if (clients.isEmpty()) getString(if (readFailed) R.string.lan_sharing_clients_unavailable else R.string.lan_sharing_clients_none)
                else getString(R.string.lan_sharing_clients, clients.size) + "\n" + clients.joinToString("\n") { "• $it" }
                delay(3000)
            }
        }
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    private fun currentLink(): String? {
        val ip = lanIp() ?: return null
        val port = SettingsManager.getSocksPort()
        val u = SettingsManager.getSocksUser()
        val p = SettingsManager.getSocksPass()
        return if (qrMode == 0) "tg://socks?server=$ip&port=$port&user=$u&pass=$p"
        else "socks5://$u:$p@$ip:$port"
    }

    private fun refresh() {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
        tvHint.text = if (enabled) "" else getString(R.string.lan_sharing_off_hint)
        val ip = lanIp()
        val socks = SettingsManager.getSocksPort()
        tvInfo.text = getString(R.string.lan_sharing_ip) + ": " + (ip ?: getString(R.string.lan_sharing_no_ip)) + "\n" +
            getString(R.string.lan_sharing_ports, socks, SettingsManager.getHttpPort()) + "\n" +
            getString(R.string.lan_sharing_auth, SettingsManager.getSocksUser(), SettingsManager.getSocksPass())
        val link = currentLink()
        ivQr.setImageBitmap(if (link != null) QRCodeDecoder.createQRCode(link) else null)
    }

    private var readFailed = false

    /** Уникальные IP клиентов с ESTABLISHED-сокетами на наши порты (из /proc/net/tcp и tcp6, без root). */
    private fun readClients(): List<String> {
        val ports = setOf(SettingsManager.getSocksPort(), SettingsManager.getHttpPort())
        val result = linkedSetOf<String>()
        var okFiles = 0
        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                File(f).readLines().drop(1).forEach { line ->
                    val c = line.trim().split(Regex("\\s+"))
                    if (c.size > 3 && c[3] == "01") {
                        val local = c[1].split(":")
                        val remote = c[2].split(":")
                        if (local.size == 2 && local[1].toInt(16) in ports) {
                            parseIp(remote[0])?.let { ip ->
                                if (!ip.startsWith("127.") && ip != "0.0.0.0" && ip != "::1") result.add(ip)
                            }
                        }
                    }
                }
                okFiles++
            } catch (_: Exception) { }
        }
        readFailed = okFiles == 0
        return result.toList()
    }

    private fun parseIp(h: String): String? {
        fun v4(x: String) = (3 downTo 0).joinToString(".") { x.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        return when (h.length) {
            8 -> v4(h)
            // IPv6: 4 слова по 4 байта little-endian; IPv4-mapped = 00000000 00000000 0000FFFF + IPv4
            32 -> if (h.startsWith("0000000000000000FFFF0000", ignoreCase = true)) v4(h.substring(24)) else
                (0 until 4).joinToString(":") { w ->
                    val x = h.substring(w * 8, w * 8 + 8)
                    "%s%s:%s%s".format(x.substring(6, 8), x.substring(4, 6), x.substring(2, 4), x.substring(0, 2)).lowercase()
                }
            else -> null
        }
    }
}
