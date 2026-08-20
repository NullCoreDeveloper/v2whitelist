package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kiktor.v2whitelist.databinding.FragmentGeekModeBinding
import com.kiktor.v2whitelist.extension.toSpeedString
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.viewmodel.GeekModeViewModel

import android.widget.Button
import android.widget.LinearLayout
import com.google.android.material.chip.Chip
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.util.MessageUtil
import kotlinx.coroutines.launch


class GeekModeBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentGeekModeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: GeekModeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeekModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        binding.btnForcePing.setOnClickListener {
            viewModel.forcePingTest()
        }

        binding.btnFindMoreVip.setOnClickListener {
            viewModel.findAdditionalVipServers()
        }


        updateNodeInfo()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rxTxSpeeds.collect { (rx, tx) ->
                        binding.tvRxSpeed.text = "Rx: ${rx.toLong().toSpeedString()}"
                        binding.tvTxSpeed.text = "Tx: ${tx.toLong().toSpeedString()}"
                        binding.speedGraph.addSpeeds(rx, tx)
                    }
                }
                

                launch {
                    viewModel.logs.collect { logsList ->
                        binding.tvLogs.text = logsList.joinToString("
")
                        // Scroll to bottom
                        val scrollView = binding.tvLogs.parent as? android.widget.ScrollView
                        scrollView?.post {
                            scrollView.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
                
                launch {
                    viewModel.vipServersFlow.collect { vipList ->
                        binding.vipContainer.removeAllViews()
                        for (vip in vipList) {
                            val chip = Chip(requireContext()).apply {
                                text = "${vip.name} (${vip.ping}ms)"
                                isCloseIconVisible = true
                                setOnCloseIconClickListener {
                                    viewModel.removeVipServer(vip.guid)
                                }
                                setOnClickListener {
                                    MmkvManager.setSelectServer(vip.guid)
                                    MessageUtil.sendMsg2Service(requireContext(), AppConfig.MSG_STATE_RESTART, "")
                                    updateNodeInfo()
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
        viewModel.startPolling()
        viewModel.loadVipServers()
    }


    override fun onStop() {
        super.onStop()
        viewModel.stopPolling()
    }

    private fun updateNodeInfo() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            binding.tvNodeInfo.text = "No server selected"
            return
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config != null) {
            val address = config.server ?: ""
            val port = config.serverPort?.toIntOrNull() ?: 0
            val protocol = config.configType.name
            binding.tvNodeInfo.text = "Name: ${config.remarks}\nProtocol: $protocol\nAddress: $address:$port"
        } else {
            binding.tvNodeInfo.text = "Unknown config"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
