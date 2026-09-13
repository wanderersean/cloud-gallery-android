package org.fossify.gallery.cloud

import android.content.Context
import android.util.Base64
import org.fossify.gallery.BuildConfig
import org.json.JSONObject

object CloudConfig {
    // 后端服务地址由构建时注入（debug: 47.98.124.235:8080，release: 121.196.160.64:80）
    // 配置串中的 server_url 字段保留解析但不再使用
    private var _serverUrl: String = BuildConfig.SERVER_BASE_URL
    private var _accessKeyId: String = ""
    private var _accessKeySecret: String = ""
    private var _securityToken: String = ""
    private var _credentialExpiresAt: Long = 0L
    private var _bucketName: String = ""
    private var _endpoint: String = ""
    private var _region: String = ""
    private var _loginMode: String = LOGIN_MODE_AKSK

    // 粘贴配置串登录：客户端自带长期 AK/SK，请求时通过 X-Master-AK-* 头带给服务端。
    const val LOGIN_MODE_AKSK = "aksk"

    // 邮箱登录：用户手里没有任何 AK/SK，直传用的凭证由服务端扮演家庭角色换来的
    // STS 临时凭证下发，会过期，必须在过期前重新拉取。
    const val LOGIN_MODE_EMAIL = "email"

    // 提前刷新的余量：上传大文件可能持续几分钟，别等到过期那一刻才换凭证。
    private const val CREDENTIAL_REFRESH_SKEW_MILLIS = 3 * 60 * 1000L

    val OSS_ACCESS_KEY_ID: String get() = _accessKeyId
    val OSS_ACCESS_KEY_SECRET: String get() = _accessKeySecret
    val OSS_SECURITY_TOKEN: String get() = _securityToken
    val OSS_BUCKET_NAME: String get() = _bucketName
    val OSS_ENDPOINT: String get() = _endpoint
    val OSS_REGION: String get() = _region
    val SERVER_BASE_URL: String get() = _serverUrl
    val LOGIN_MODE: String get() = _loginMode

    // 邮箱（家庭）登录：凭证是服务端下发的 STS 临时凭证。
    val usesStsCredentials: Boolean get() = _loginMode == LOGIN_MODE_EMAIL

    val isConfigured: Boolean
        get() = _serverUrl.isNotEmpty() && _accessKeyId.isNotEmpty()

    // 临时凭证是否已经（或即将）过期，过期就必须重新向服务端要一次。
    fun isCredentialExpiring(): Boolean {
        if (!usesStsCredentials) return false
        return System.currentTimeMillis() >= _credentialExpiresAt - CREDENTIAL_REFRESH_SKEW_MILLIS
    }

    fun loadFromConfigString(context: Context, configString: String): Boolean {
        return try {
            val jsonString = String(Base64.decode(configString, Base64.DEFAULT))
            val json = JSONObject(jsonString)

            // server_url 字段保留解析但不使用，后端地址由 BuildConfig.SERVER_BASE_URL 决定
            _accessKeyId = json.getString("access_key_id")
            _accessKeySecret = json.getString("access_key_secret")
            _bucketName = json.getString("bucket")
            _endpoint = json.getString("endpoint")
            _region = json.getString("region")
            _securityToken = ""
            _credentialExpiresAt = 0L
            _loginMode = LOGIN_MODE_AKSK

            saveToPreferences(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 邮箱登录成功后保存家庭存储信息，OSS 凭证随后由 saveStsCredentials 补上。
    fun saveFamilyConfig(context: Context, bucket: String, endpoint: String, region: String) {
        _bucketName = bucket
        _endpoint = endpoint
        _region = region
        _loginMode = LOGIN_MODE_EMAIL
        saveToPreferences(context)
    }

    // 保存服务端下发的 STS 临时凭证。expiresAtMillis 为 0 表示过期时间解析失败，
    // 此时 isCredentialExpiring() 会一直返回 true，下次使用前会重新拉取。
    fun saveStsCredentials(
        context: Context,
        accessKeyId: String,
        accessKeySecret: String,
        securityToken: String,
        expiresAtMillis: Long,
        bucket: String,
        endpoint: String,
        region: String
    ) {
        _accessKeyId = accessKeyId
        _accessKeySecret = accessKeySecret
        _securityToken = securityToken
        _credentialExpiresAt = expiresAtMillis
        if (bucket.isNotEmpty()) _bucketName = bucket
        if (endpoint.isNotEmpty()) _endpoint = endpoint
        if (region.isNotEmpty()) _region = region
        _loginMode = LOGIN_MODE_EMAIL
        saveToPreferences(context)
    }

    fun loadFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE)
        // server_url 不再从持久化存储读取，始终使用 BuildConfig.SERVER_BASE_URL
        _accessKeyId = prefs.getString("access_key_id", "") ?: ""
        _accessKeySecret = prefs.getString("access_key_secret", "") ?: ""
        _securityToken = prefs.getString("security_token", "") ?: ""
        _credentialExpiresAt = prefs.getLong("credential_expires_at", 0L)
        _bucketName = prefs.getString("bucket", "") ?: ""
        _endpoint = prefs.getString("endpoint", "") ?: ""
        _region = prefs.getString("region", "") ?: ""
        _loginMode = prefs.getString("login_mode", LOGIN_MODE_AKSK) ?: LOGIN_MODE_AKSK
    }

    private fun saveToPreferences(context: Context) {
        context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).edit().apply {
            // server_url 不再持久化，由 BuildConfig.SERVER_BASE_URL 提供
            putString("access_key_id", _accessKeyId)
            putString("access_key_secret", _accessKeySecret)
            putString("security_token", _securityToken)
            putLong("credential_expires_at", _credentialExpiresAt)
            putString("bucket", _bucketName)
            putString("endpoint", _endpoint)
            putString("region", _region)
            putString("login_mode", _loginMode)
            apply()
        }
    }

    fun clearConfig(context: Context) {
        // server_url 重置为构建时注入的地址，而非清空
        _serverUrl = BuildConfig.SERVER_BASE_URL
        _accessKeyId = ""
        _accessKeySecret = ""
        _securityToken = ""
        _credentialExpiresAt = 0L
        _bucketName = ""
        _endpoint = ""
        _region = ""
        _loginMode = LOGIN_MODE_AKSK
        context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
