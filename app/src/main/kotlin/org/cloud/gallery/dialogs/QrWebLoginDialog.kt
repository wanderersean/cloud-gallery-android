package org.fossify.gallery.dialogs

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudApiService

/**
 * Dialog that guides the logged-in App user through scanning a Web QR code to
 * authorize the Web browser session.
 *
 * Flow:
 *  1. User taps "授权 Web 登录" button (shown only when already logged in).
 *  2. This dialog opens, shows a brief explanation, then launches the camera.
 *  3. After scanning, the Activity's onActivityResult receives the QR content.
 *  4. The Activity calls [parseSessionId] to check if it is a Web QR login URI.
 *  5. If yes, call the server's confirmQrSession directly.
 */
class QrWebLoginDialog(
    private val activity: Activity,
    private val callback: () -> Unit = {}
) {
    private val accountManager = CloudAccountManager.getInstance(activity)
    private val apiService = CloudApiService(accountManager)
    private var dialog: AlertDialog? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1002
        // Same as IntentIntegrator default (49374); disambiguation done by QR content format
        const val QR_WEB_LOGIN_REQUEST_CODE = 49374

        /**
         * Parse the session_id from a QR content string.
         * Expected format: fossify://qr-login?session=<uuid>
         * Returns null if the content is not a valid QR web-login URI.
         */
        fun parseSessionId(qrContent: String): String? {
            return try {
                val uri = android.net.Uri.parse(qrContent)
                if (uri.scheme == "fossify" && uri.host == "qr-login") {
                    uri.getQueryParameter("session")?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    init {
        showExplanationAndScan()
    }

    private fun showExplanationAndScan() {
        activity.getAlertDialogBuilder()
            .setTitle(R.string.cloud_qr_web_login_title)
            .setMessage(R.string.cloud_qr_web_login_hint)
            .setPositiveButton(R.string.cloud_scan_qr) { _, _ ->
                checkCameraPermissionAndScan()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(activity.layoutInflater.inflate(
                    android.R.layout.simple_list_item_1, null, false
                ), this, R.string.cloud_qr_web_login_title) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
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

    fun confirmSession(sessionId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = apiService.confirmQrSession(sessionId)
            if (result.isSuccess) {
                Toast.makeText(activity, R.string.cloud_qr_web_login_success, Toast.LENGTH_SHORT).show()
                callback()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "unknown error"
                Toast.makeText(
                    activity,
                    "${activity.getString(R.string.cloud_qr_web_login_failed)}: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
