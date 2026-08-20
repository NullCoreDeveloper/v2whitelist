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
                        binding.tvLogs.text = logsList.joinToString("\n")
                        // Scroll to bottom
                        val scrollView = binding.tvLogs.parent as? android.widget.ScrollView
                        scrollView?.post {
                            scrollView.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPolling()
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
