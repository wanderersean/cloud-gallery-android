package org.fossify.gallery.dialogs

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.adapters.UploadTaskAdapter
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudApiService
import org.fossify.gallery.cloud.CloudConfig
import org.fossify.gallery.cloud.CloudUploadManager
import org.fossify.gallery.databinding.DialogCloudAccountBinding
import org.fossify.gallery.databinding.DialogCloudUploadTasksBinding

class CloudAccountDialog(val activity: Activity, private var configStringOverride: String? = null, val callback: () -> Unit) {
    private val accountManager = CloudAccountManager.getInstance(activity)
    private val apiService = CloudApiService(accountManager)
    private var dialog: AlertDialog? = null

    companion object {
        const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        const val QR_SCAN_REQUEST_CODE = 49374 // IntentIntegrator default request code

        /**
         * Show the dialog with a pre-filled config string (e.g., from QR scan)
         */
        fun showWithConfig(activity: Activity, configString: String, callback: () -> Unit): CloudAccountDialog {
            val accountManager = CloudAccountManager.getInstance(activity)
            return if (accountManager.isLoggedIn) {
                // Already logged in, show upload tasks dialog
                CloudAccountDialog(activity, callback = callback)
            } else {
                // Not logged in, show login dialog with pre-filled config
                CloudAccountDialog(activity, configString, callback)
            }
        }

        /**
         * Parse QR scan result from activity result
         */
        fun parseQrScanResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): String? {
            val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            if (result != null && result.contents != null) {
                return result.contents
            }
            return null
        }
    }

    init {
        if (accountManager.isLoggedIn) {
            showUploadTasksDialog()
        } else if (configStringOverride != null) {
            showLoginDialogWithConfig(configStringOverride!!)
        } else {
            showLoginDialog()
        }
    }

    // 默认入口是邮箱登录；「改用配置串登录」再回到下面的老流程。
    private fun showLoginDialog() {
        CloudEmailAuthFlow(
            activity = activity,
            onLegacyLogin = { showLegacyLoginDialog() },
            onFinished = {
                dialog?.dismiss()
                callback()
            }
        ).start()
    }

    private fun showLegacyLoginDialog() {
        val binding = DialogCloudAccountBinding.inflate(activity.layoutInflater)
        applyThemeColors(binding)

        binding.cloudLoginGroup.visibility = View.VISIBLE
        binding.cloudLoggedInGroup.visibility = View.GONE

        CloudConfig.loadFromPreferences(activity)

        binding.cloudScanQrBtn.setOnClickListener {
            checkCameraPermissionAndScan()
        }

        binding.cloudLoginBtn.setOnClickListener {
            val configString = binding.cloudConfigInput.text.toString().trim()

            if (configString.isEmpty()) {
                Toast.makeText(activity, R.string.cloud_config_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            attemptLogin(configString, binding)
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cloud_account_config) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            startQrScan()
        }
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(activity)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    private fun showLoginDialogWithConfig(configString: String) {
        val binding = DialogCloudAccountBinding.inflate(activity.layoutInflater)
        applyThemeColors(binding)

        binding.cloudLoginGroup.visibility = View.VISIBLE
        binding.cloudLoggedInGroup.visibility = View.GONE

        // Pre-fill the config string
        binding.cloudConfigInput.setText(configString)

        CloudConfig.loadFromPreferences(activity)

        binding.cloudScanQrBtn.setOnClickListener {
            checkCameraPermissionAndScan()
        }

        binding.cloudLoginBtn.setOnClickListener {
            val inputConfigString = binding.cloudConfigInput.text.toString().trim()

            if (inputConfigString.isEmpty()) {
                Toast.makeText(activity, R.string.cloud_config_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            attemptLogin(inputConfigString, binding)
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cloud_account_config) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun attemptLogin(configString: String, binding: DialogCloudAccountBinding) {
        if (!CloudConfig.loadFromConfigString(activity, configString)) {
            Toast.makeText(activity, R.string.cloud_config_invalid, Toast.LENGTH_LONG).show()
            return
        }

        if (!CloudConfig.isConfigured) {
            Toast.makeText(activity, R.string.cloud_config_invalid, Toast.LENGTH_LONG).show()
            return
        }

        binding.cloudLoginBtn.isEnabled = false
        binding.cloudLoginBtn.text = activity.getString(R.string.cloud_uploading)

        CoroutineScope(Dispatchers.Main).launch {
            val result = apiService.login()
            if (result.isSuccess) {
                val response = result.getOrNull()!!
                accountManager.saveLoginInfo(response.token, response.accountId, response.displayName)
                Toast.makeText(activity, R.string.cloud_login_success, Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
                callback()
            } else {
                binding.cloudLoginBtn.isEnabled = true
                binding.cloudLoginBtn.text = activity.getString(R.string.cloud_login)
                val errorMsg = result.exceptionOrNull()?.message ?: "unknown error"
                Toast.makeText(activity, "${activity.getString(R.string.cloud_login_failed)}: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUploadTasksDialog() {
        val binding = DialogCloudUploadTasksBinding.inflate(activity.layoutInflater)
        val uploadManager = CloudUploadManager.getInstance(activity)
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        binding.cloudDisplayNameLabel.setTextColor(textColor)
        binding.cloudDisplayName.setTextColor(textColor)
        binding.uploadTasksTitle.setTextColor(textColor)
        binding.uploadTasksEmpty.setTextColor(textColor)
        binding.cloudHeaderDivider.setBackgroundColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 64)
        )
        val primaryContrastColor = primaryColor.getContrastColor()
        binding.cloudLogoutBtn.setTextColor(primaryContrastColor)
        binding.clearCompletedBtn.setTextColor(primaryContrastColor)

        binding.cloudDisplayName.text = accountManager.displayName

        val adapter = UploadTaskAdapter(uploadManager.uploadTasks) { path ->
            uploadManager.cancelUpload(path)
        }
        binding.uploadTasksList.layoutManager = LinearLayoutManager(activity)
        binding.uploadTasksList.adapter = adapter

        val taskListener = object : CloudUploadManager.TaskListListener {
            override fun onTaskListUpdated() {
                activity.runOnUiThread {
                    val tasks = uploadManager.uploadTasks
                    adapter.updateTasks(tasks)
                    updateEmptyState(binding, tasks)
                }
            }
        }
        uploadManager.addTaskListListener(taskListener)

        updateEmptyState(binding, uploadManager.uploadTasks)

        binding.cloudLogoutBtn.setOnClickListener {
            accountManager.logout()
            if (CloudConfig.usesStsCredentials) {
                // 邮箱登录没有长期密钥，退出时把服务端下发的 STS 临时凭证一起清掉
                CloudConfig.clearConfig(activity)
            }
            dialog?.dismiss()
            Toast.makeText(activity, R.string.cloud_logout, Toast.LENGTH_SHORT).show()
            callback()
        }

        binding.clearCompletedBtn.setOnClickListener {
            uploadManager.clearCompletedTasks()
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setOnDismissListener {
                uploadManager.removeTaskListListener(taskListener)
            }
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cloud_account_config) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun updateEmptyState(binding: DialogCloudUploadTasksBinding, tasks: List<CloudUploadManager.UploadTask>) {
        if (tasks.isEmpty()) {
            binding.uploadTasksEmpty.visibility = View.VISIBLE
            binding.uploadTasksList.visibility = View.GONE
            binding.clearCompletedBtn.visibility = View.GONE
        } else {
            binding.uploadTasksEmpty.visibility = View.GONE
            binding.uploadTasksList.visibility = View.VISIBLE
            val hasCompleted = tasks.any {
                it.status in listOf(
                    CloudUploadManager.UploadStatus.SUCCESS,
                    CloudUploadManager.UploadStatus.FAILED,
                    CloudUploadManager.UploadStatus.CANCELLED
                )
            }
            binding.clearCompletedBtn.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        }
    }

    private fun applyThemeColors(binding: DialogCloudAccountBinding) {
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        binding.cloudConfigHintText.setTextColor(textColor)
        binding.cloudDisplayNameLabel.setTextColor(textColor)
        binding.cloudDisplayName.setTextColor(textColor)
        binding.cloudScanQrBtn.setTextColor(primaryColor)
        binding.cloudQrWebLoginBtn.setTextColor(primaryColor)
        binding.cloudConfigInput.setTextColor(textColor)
        binding.cloudConfigInput.setHintTextColor(
            androidx.core.graphics.ColorUtils.setAlphaComponent(textColor, 160)
        )
        binding.cloudConfigInput.backgroundTintList =
            android.content.res.ColorStateList.valueOf(primaryColor)
    }
}
