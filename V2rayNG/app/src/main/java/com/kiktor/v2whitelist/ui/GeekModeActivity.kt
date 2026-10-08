package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.databinding.ActivityGeekModeBinding
import com.kiktor.v2whitelist.extension.toSpeedString
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.V2RayServiceManager
import com.kiktor.v2whitelist.util.MessageUtil
import com.kiktor.v2whitelist.viewmodel.GeekModeViewModel
import kotlinx.coroutines.launch

class GeekModeActivity : BaseActivity() {

    private val binding by lazy { ActivityGeekModeBinding.inflate(layoutInflater) }
    private val viewModel: GeekModeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "Geek Mode")

        binding.btnForcePing.setOnClickListener {
            viewModel.forcePingTest()
            // Если VPN запущен — также тригеррим системный пинг-тест ядра
            if (V2RayServiceManager.isRunning() == true) {
                MessageUtil.sendMsg2Service(this, AppConfig.MSG_MEASURE_DELAY, "")
            }
        }

        binding.btnFindMoreVip.setOnClickListener {
            viewModel.findAdditionalVipServers()
        }

        binding.btnPingVip.setOnClickListener {
            viewModel.pingVipServers()
        }

        updateNodeInfo()

        binding.etGlobalSni.setText(MmkvManager.decodeSettingsString(AppConfig.PREF_GLOBAL_SNI, ""))
        binding.etCloudflareIp.setText(MmkvManager.decodeSettingsString(AppConfig.PREF_CLOUDFLARE_IP, ""))
        
        val userAgents = arrayOf(
            "Auto (Default)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36 Edg/118.0.2088.46",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Safari/605.1.15",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userAgents)
        binding.spinnerUserAgent.setAdapter(adapter)
        val currentUa = MmkvManager.decodeSettingsString(AppConfig.PREF_CUSTOM_USER_AGENT, "Auto (Default)")
        binding.spinnerUserAgent.setText(currentUa, false)

        binding.etGlobalSni.addTextChangedListener(object: android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                MmkvManager.encodeSettings(AppConfig.PREF_GLOBAL_SNI, s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etCloudflareIp.addTextChangedListener(object: android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                MmkvManager.encodeSettings(AppConfig.PREF_CLOUDFLARE_IP, s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.spinnerUserAgent.setOnItemClickListener { _, _, position, _ ->
            MmkvManager.encodeSettings(AppConfig.PREF_CUSTOM_USER_AGENT, userAgents[position])
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.exitIp.collect { ip ->
                        binding.tvExitIp.text = if (ip != null) "Exit IP: $ip" else ""
                    }
                }

                launch {
                    viewModel.pingUpdated.collect {
                        updateNodeInfo()
                    }
                }

                launch {
                    viewModel.logs.collect { logsList ->
                        binding.tvLogs.text = logsList.joinToString("\n")
                        // Скроллим к низу только внутренний NestedScrollView с логами
                        val nsv = binding.logsScrollView
                        nsv.post { nsv.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }

                launch {
                    viewModel.vipServersFlow.collect { vipList ->
                        binding.vipContainer.removeAllViews()
                        for (vip in vipList) {
                            val chip = Chip(this@GeekModeActivity).apply {
                                text = "${vip.name} (${vip.ping}ms)"
                                isCloseIconVisible = true
                                setOnCloseIconClickListener {
                                    viewModel.removeVipServer(vip.guid)
                                }
                                setOnClickListener {
                                    val changed = MmkvManager.getSelectServer() != vip.guid
                                    MmkvManager.setSelectServer(vip.guid)
                                    updateNodeInfo()
                                    
                                    if (V2RayServiceManager.isRunning() == true) {
                                        if (changed) {
                                            // Останавливаем сервис и запускаем заново для применения нового конфига
                                            V2RayServiceManager.stopVService(this@GeekModeActivity)
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                V2RayServiceManager.startVService(this@GeekModeActivity)
                                            }, 300L) // Небольшая задержка перед стартом
                                        }
                                    } else {
                                        // Включаем VPN, если он был выключен
                                        V2RayServiceManager.startVService(this@GeekModeActivity)
                                    }
                                }
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = 8
                                }
                            }
                            binding.vipContainer.addView(chip)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.loadVipServers()
        if (V2RayServiceManager.isRunning() == true) {
            viewModel.fetchExitIp()
        }
    }

    override fun onStop() {
        super.onStop()
    }

    private fun updateNodeInfo() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            binding.tvNodeInfo.text = getString(R.string.geek_mode_no_server)
            binding.tvExitIp.text = ""
            return
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config != null) {
            val address = config.server ?: ""
            val port = config.serverPort?.toIntOrNull() ?: 0
            val protocol = config.configType.name
            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            val delayStr = if (delay > 0) "\nPing: ${delay}ms" else if (delay < 0) "\nPing: Failed (-1)" else ""
            binding.tvNodeInfo.text = "Name: ${config.remarks}\nProtocol: $protocol\nAddress: $address:$port$delayStr"
        } else {
            binding.tvNodeInfo.text = getString(R.string.geek_mode_unknown_config)
        }
    }
}
