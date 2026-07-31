package com.kiktor.v2whitelist.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.kiktor.v2whitelist.AppConfig
import com.kiktor.v2whitelist.BuildConfig
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.databinding.ActivityCheckUpdateBinding
import com.kiktor.v2whitelist.dto.CheckUpdateResult
import com.kiktor.v2whitelist.extension.toast
import com.kiktor.v2whitelist.extension.toastError
import com.kiktor.v2whitelist.extension.toastSuccess
import com.kiktor.v2whitelist.handler.MmkvManager
import com.kiktor.v2whitelist.handler.UpdateCheckerManager
import com.kiktor.v2whitelist.handler.V2RayNativeManager
import com.kiktor.v2whitelist.util.Utils
import kotlinx.coroutines.launch
import android.content.Intent
import android.view.View
import android.widget.TextView
import java.io.File
import androidx.core.content.FileProvider

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            }
            finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        val dialogView = layoutInflater.inflate(R.layout.layout_update_dialog, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_update_title)
        val tvToggle = dialogView.findViewById<TextView>(R.id.tv_update_changelog_toggle)
        val scrollChangelog = dialogView.findViewById<View>(R.id.scroll_changelog)
        val tvChangelog = dialogView.findViewById<TextView>(R.id.tv_update_changelog)

        tvTitle.text = getString(R.string.update_new_version_found, result.latestVersion)
        tvChangelog.text = result.releaseNotes

        var isExpanded = false
        tvToggle.setOnClickListener {
            isExpanded = !isExpanded
            scrollChangelog.visibility = if (isExpanded) View.VISIBLE else View.GONE
            tvToggle.text = if (isExpanded) "▲ Hide Changelog" else "▼ Show Changelog"
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let { url ->
                    downloadAndInstall(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstall(url: String) {
        showLoading()
        toast("Downloading update...")
        lifecycleScope.launch {
            try {
                val apkFile = UpdateCheckerManager.downloadApk(this@CheckUpdateActivity, url)
                if (apkFile != null && apkFile.exists()) {
                    installApk(apkFile)
                } else {
                    toastError("Failed to download APK")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Update failed: ${e.message}")
                toastError(e.message ?: "Download failed")
            } finally {
                hideLoading()
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.cache",
                apkFile
            )
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start install intent: ${e.message}")
            toastError("Failed to start installer")
        }
    }
}