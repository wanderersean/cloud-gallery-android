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
    private var _bucketName: String = ""
    private var _endpoint: String = ""
    private var _region: String = ""

    val OSS_ACCESS_KEY_ID: String get() = _accessKeyId
    val OSS_ACCESS_KEY_SECRET: String get() = _accessKeySecret
    val OSS_BUCKET_NAME: String get() = _bucketName
    val OSS_ENDPOINT: String get() = _endpoint
    val OSS_REGION: String get() = _region
    val SERVER_BASE_URL: String get() = _serverUrl

    val isConfigured: Boolean
        get() = _serverUrl.isNotEmpty() && _accessKeyId.isNotEmpty()

    fun loadFromConfigString(context: Context, configString: String): Boolean {
        return try {
            val jsonString = String(Base64.decode(configString, Base64.DEFAULT))
            val json = JSONObject(jsonString)

            // server_url 字段保留解析但不使用，后端地址由 BuildConfig.SERVER_BASE_URL 决定
            // val ignoredServerUrl = json.optString("server_url")
            _accessKeyId = json.getString("access_key_id")
            _accessKeySecret = json.getString("access_key_secret")
            _bucketName = json.getString("bucket")
            _endpoint = json.getString("endpoint")
            _region = json.getString("region")

            saveToPreferences(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE)
        // server_url 不再从持久化存储读取，始终使用 BuildConfig.SERVER_BASE_URL
        _accessKeyId = prefs.getString("access_key_id", "") ?: ""
        _accessKeySecret = prefs.getString("access_key_secret", "") ?: ""
        _bucketName = prefs.getString("bucket", "") ?: ""
        _endpoint = prefs.getString("endpoint", "") ?: ""
        _region = prefs.getString("region", "") ?: ""
    }

    private fun saveToPreferences(context: Context) {
        context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).edit().apply {
            // server_url 不再持久化，由 BuildConfig.SERVER_BASE_URL 提供
            putString("access_key_id", _accessKeyId)
            putString("access_key_secret", _accessKeySecret)
            putString("bucket", _bucketName)
            putString("endpoint", _endpoint)
            putString("region", _region)
            apply()
        }
    }

    fun clearConfig(context: Context) {
        // server_url 重置为构建时注入的地址，而非清空
        _serverUrl = BuildConfig.SERVER_BASE_URL
        _accessKeyId = ""
        _accessKeySecret = ""
        _bucketName = ""
        _endpoint = ""
        _region = ""
        context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
