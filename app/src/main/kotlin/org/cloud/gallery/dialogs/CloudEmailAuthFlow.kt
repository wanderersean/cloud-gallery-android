package org.fossify.gallery.dialogs

import android.app.Activity
import android.os.CountDownTimer
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudApiService
import org.fossify.gallery.cloud.CloudConfig
import org.fossify.gallery.databinding.DialogCloudEmailLoginBinding
import org.fossify.gallery.databinding.DialogCloudEmailSignupBinding
import org.fossify.gallery.databinding.DialogCloudFamilyBindBinding

/**
 * App 端的邮箱登录流程：登录 → 注册 → 加入/开通家庭。
 *
 * 三个弹窗串成一条链，全部走图库后端（后端再代理 Casdoor），App 不需要内置浏览器：
 *   1. 登录弹窗：邮箱 + 密码；已有家庭直接进入云盘，没有家庭则进入第 3 步；
 *   2. 注册弹窗：邮箱 + 验证码 + 密码 + 昵称，注册成功后自动登录；
 *   3. 家庭弹窗：用户主邀请码加入家庭，或用户主阿里云 AK/SK 开通新家庭。
 *
 * 登录成功后本地不再保存任何长期 AK/SK：照片直传用的 STS 临时凭证由
 * [CloudApiService.fetchOssCredential] 按需拉取，过期前自动续期。
 */
class CloudEmailAuthFlow(
    private val activity: Activity,
    private val onLegacyLogin: () -> Unit,
    private val onFinished: () -> Unit
) {
    private val accountManager = CloudAccountManager.getInstance(activity)
    private val apiService = CloudApiService(accountManager)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var dialog: AlertDialog? = null
    private var countdown: CountDownTimer? = null

    fun start() {
        showLoginDialog(accountManager.email)
    }

    private fun showLoginDialog(prefillEmail: String) {
        dismissCurrent()
        val binding = DialogCloudEmailLoginBinding.inflate(activity.layoutInflater)
        binding.cloudEmailInput.setText(prefillEmail)
        if (prefillEmail.isNotEmpty()) {
            binding.cloudPasswordInput.requestFocus()
        }

        binding.cloudEmailLegacyBtn.setOnClickListener {
            dismissCurrent()
            onLegacyLogin()
        }

        binding.cloudEmailSignupBtn.setOnClickListener {
            val email = binding.cloudEmailInput.text.toString().trim()
            dismissCurrent()
            showSignupDialog(email)
        }

        binding.cloudEmailLoginBtn.setOnClickListener {
            val email = binding.cloudEmailInput.text.toString().trim()
            val password = binding.cloudPasswordInput.text.toString()
            if (!isValidEmail(email)) {
                toast(R.string.cloud_email_invalid)
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                toast(R.string.cloud_email_password_required)
                return@setOnClickListener
            }

            setButtonState(binding.cloudEmailLoginBtn, R.string.cloud_email_logging_in, false)
            scope.launch {
                apiService.emailLogin(email, password)
                    .onSuccess { session -> handleSession(session) }
                    .onFailure { error ->
                        setButtonState(binding.cloudEmailLoginBtn, R.string.cloud_email_login, true)
                        toastText(errorMessage(error))
                    }
            }
        }

        showDialog(binding.root, R.string.cloud_email_login_title)
    }

    private fun showSignupDialog(prefillEmail: String) {
        dismissCurrent()
        val binding = DialogCloudEmailSignupBinding.inflate(activity.layoutInflater)
        binding.cloudSignupEmailInput.setText(prefillEmail)

        binding.cloudSignupSendCodeBtn.setOnClickListener {
            val email = binding.cloudSignupEmailInput.text.toString().trim()
            if (!isValidEmail(email)) {
                toast(R.string.cloud_email_invalid)
                return@setOnClickListener
            }
            setButtonState(binding.cloudSignupSendCodeBtn, R.string.cloud_email_sending_code, false)
            scope.launch {
                apiService.sendSignupCode(email)
                    .onSuccess {
                        toast(R.string.cloud_email_code_sent)
                        startResendCountdown(binding)
                    }
                    .onFailure { error ->
                        setButtonState(binding.cloudSignupSendCodeBtn, R.string.cloud_email_send_code, true)
                        toastText(errorMessage(error))
                    }
            }
        }

        binding.cloudSignupBackBtn.setOnClickListener {
            val email = binding.cloudSignupEmailInput.text.toString().trim()
            dismissCurrent()
            showLoginDialog(email)
        }

        binding.cloudSignupSubmitBtn.setOnClickListener {
            val email = binding.cloudSignupEmailInput.text.toString().trim()
            val code = binding.cloudSignupCodeInput.text.toString().trim()
            val password = binding.cloudSignupPasswordInput.text.toString()
            val nickname = binding.cloudSignupNicknameInput.text.toString().trim()
            if (!isValidEmail(email)) {
                toast(R.string.cloud_email_invalid)
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                toast(R.string.cloud_email_code_required)
                return@setOnClickListener
            }
            if (password.length < MIN_PASSWORD_LENGTH) {
                toast(R.string.cloud_password_placeholder)
                return@setOnClickListener
            }

            setButtonState(binding.cloudSignupSubmitBtn, R.string.cloud_email_submitting, false)
            scope.launch {
                apiService.signup(email, code, password, nickname)
                    .onSuccess { session ->
                        handleSession(session, email)
                    }
                    .onFailure { error ->
                        setButtonState(binding.cloudSignupSubmitBtn, R.string.cloud_email_signup_submit, true)
                        toastText(errorMessage(error))
                    }
            }
        }

        showDialog(binding.root, R.string.cloud_email_signup_title)
    }

    private fun showBindDialog(session: CloudApiService.MobileSession) {
        dismissCurrent()
        val binding = DialogCloudFamilyBindBinding.inflate(activity.layoutInflater)
        val who = session.email.ifEmpty { session.displayName }
        binding.cloudFamilyBindHint.text = activity.getString(R.string.cloud_family_bind_hint, who)

        binding.cloudFamilyJoinBtn.setOnClickListener {
            val code = binding.cloudFamilyInviteInput.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                toast(R.string.cloud_family_invite_required)
                return@setOnClickListener
            }
            setButtonState(binding.cloudFamilyJoinBtn, R.string.cloud_email_submitting, false)
            scope.launch {
                apiService.joinFamily(session.token, code, session.displayName)
                    .onSuccess { joined -> finishLogin(joined) }
                    .onFailure { error ->
                        setButtonState(binding.cloudFamilyJoinBtn, R.string.cloud_family_join, true)
                        toastText(errorMessage(error))
                    }
            }
        }

        binding.cloudFamilyClaimBtn.setOnClickListener {
            val accessKeyId = binding.cloudFamilyAkIdInput.text.toString().trim()
            val accessKeySecret = binding.cloudFamilyAkSecretInput.text.toString().trim()
            if (accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
                toast(R.string.cloud_family_ak_required)
                return@setOnClickListener
            }
            val region = binding.cloudFamilyRegionInput.text.toString().trim()
            val bucket = binding.cloudFamilyBucketInput.text.toString().trim()
            setButtonState(binding.cloudFamilyClaimBtn, R.string.cloud_email_submitting, false)
            scope.launch {
                apiService.claimFamily(session.token, accessKeyId, accessKeySecret, region, bucket, session.displayName)
                    .onSuccess { claimed -> finishLogin(claimed) }
                    .onFailure { error ->
                        setButtonState(binding.cloudFamilyClaimBtn, R.string.cloud_family_claim, true)
                        toastText(errorMessage(error))
                    }
            }
        }

        showDialog(binding.root, R.string.cloud_family_bind_title)
    }

    /**
     * mode=login 表示已经绑定家庭，可以正式登录；mode=bind 则要先加入/开通家庭。
     * 注册成功但自动登录没拿到令牌（mode=registered）时，回登录页让用户自己输一次密码。
     */
    private fun handleSession(session: CloudApiService.MobileSession, email: String = "") {
        when {
            session.hasFamily -> finishLogin(session)
            session.token.isNotEmpty() -> showBindDialog(session)
            else -> {
                val fallback = activity.getString(R.string.cloud_email_signup_success)
                toastText(session.message.ifEmpty { fallback })
                dismissCurrent()
                showLoginDialog(email.ifEmpty { session.email })
            }
        }
    }

    private fun finishLogin(session: CloudApiService.MobileSession) {
        accountManager.saveLoginInfo(
            session.token,
            session.accountId,
            session.displayName,
            session.email,
            CloudConfig.LOGIN_MODE_EMAIL
        )
        CloudConfig.saveFamilyConfig(activity, session.bucket, session.endpoint, session.region)

        scope.launch {
            // 先拿一次直传凭证；失败也不影响登录，上传前还会自动重试。
            apiService.fetchOssCredential()
                .onSuccess { credential ->
                    CloudConfig.saveStsCredentials(
                        activity,
                        credential.accessKeyId,
                        credential.accessKeySecret,
                        credential.securityToken,
                        credential.expiresAtMillis(),
                        credential.bucket,
                        credential.endpoint,
                        credential.region
                    )
                }
                .onFailure { error ->
                    Log.w(TAG, "获取上传凭证失败，上传时会重试: " + error.message)
                }
            dismissCurrent()
            toast(R.string.cloud_login_success)
            onFinished()
        }
    }

    private fun startResendCountdown(binding: DialogCloudEmailSignupBinding) {
        countdown?.cancel()
        countdown = object : CountDownTimer(RESEND_INTERVAL_MILLIS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.cloudSignupSendCodeBtn.isEnabled = false
                binding.cloudSignupSendCodeBtn.text =
                    activity.getString(R.string.cloud_email_resend_code, (millisUntilFinished / 1000).toInt())
            }

            override fun onFinish() {
                setButtonState(binding.cloudSignupSendCodeBtn, R.string.cloud_email_send_code, true)
            }
        }.start()
    }

    private fun showDialog(view: android.view.View, titleRes: Int) {
        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, titleRes) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun dismissCurrent() {
        countdown?.cancel()
        countdown = null
        dialog?.dismiss()
        dialog = null
    }

    private fun setButtonState(button: Button, textRes: Int, enabled: Boolean) {
        button.setText(textRes)
        button.isEnabled = enabled
    }

    private fun isValidEmail(email: String): Boolean {
        return email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // 服务端已经把错误翻译成了中文，直接展示；拿不到文案时用兜底提示。
    private fun errorMessage(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return message.ifEmpty { activity.getString(R.string.cloud_login_failed) }
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toastText(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "CloudEmailAuthFlow"
        private const val MIN_PASSWORD_LENGTH = 8
        private const val RESEND_INTERVAL_MILLIS = 60_000L
    }
}
