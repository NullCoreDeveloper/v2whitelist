package com.kiktor.v2whitelist.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.databinding.ActivityNoneBinding
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.util.QRCodeDecoder
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig

class ScannerActivity : HelperBaseActivity() {
    private val binding by lazy {  ActivityNoneBinding.inflate(layoutInflater) }

    private val scanQrCode = registerForActivityResult(ScanCustomCode(), ::handleResult)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildActionCards()
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.menu_item_import_config_qrcode))

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_START_SCAN_IMMEDIATE)) {
            launchScan()
        }
    }

    private fun buildActionCards() {
        val root = binding.root as android.view.ViewGroup
        root.removeAllViews()
        val dp = resources.displayMetrics.density
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        fun card(icon: Int, title: Int, desc: Int, onClick: () -> Unit) {
            val c = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16 * dp
                cardElevation = 2 * dp
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(icon)
                layoutParams = android.widget.LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
            })
            val texts = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((20 * dp).toInt(), 0, 0, 0)
            }
            texts.addView(android.widget.TextView(this).apply {
                setText(title); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(android.widget.TextView(this).apply { setText(desc); textSize = 13f; alpha = 0.7f })
            row.addView(texts)
            c.addView(row)
            column.addView(c, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() })
        }
        card(R.drawable.ic_scan_qr_24dp, R.string.scanner_btn_scan, R.string.scanner_btn_scan_desc) { launchScan() }
        card(R.drawable.ic_gallery_24dp, R.string.scanner_btn_photo, R.string.scanner_btn_photo_desc) { showFileChooser() }
        card(R.drawable.ic_paste_24dp, R.string.scanner_btn_clipboard, R.string.scanner_btn_clipboard_desc) {
            val text = com.kiktor.v2whitelist.util.Utils.getClipboard(this)
            if (text.isNullOrBlank()) toast(R.string.toast_none_data_clipboard) else finished(text)
        }
        root.addView(column)
    }

    private fun launchScan() {
        scanQrCode.launch(
            ScannerConfig.build {
                setHapticSuccessFeedback(true) // enable (default) or disable haptic feedback when a barcode was detected
                setShowTorchToggle(true) // show or hide (default) torch/flashlight toggle button
                setShowCloseButton(true) // show or hide (default) close button
                setBarcodeFormats(listOf(BarcodeFormat.QR_CODE))
            }
        )
    }

    private fun handleResult(result: QRResult) {
        if (result is QRResult.QRSuccess) {
            finished(result.content.rawValue.orEmpty())
        } else {
            finish()
        }
    }

    private fun finished(text: String) {
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", text)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scanner, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.scan_code -> {
            launchScan()
            true
        }

        R.id.select_photo -> {
            showFileChooser()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun showFileChooser() {
        launchFileChooser("image/*") { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val text = QRCodeDecoder.syncDecodeQRCode(bitmap)
                if (text.isNullOrEmpty()) {
                    toast(R.string.toast_decoding_failed)
                } else {
                    finished(text)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to decode QR code from file", e)
                toast(R.string.toast_decoding_failed)
            }
        }
    }
}