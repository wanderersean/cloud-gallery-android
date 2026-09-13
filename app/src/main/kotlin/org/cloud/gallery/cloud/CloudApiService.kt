package org.fossify.gallery.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 服务端返回的业务错误。message 已经是可以直接展示给用户的中文文案，
 * 弹窗直接把它 Toast 出来即可，不需要再翻译。
 */
class CloudApiException(val statusCode: Int, message: String) : Exception(message)

class CloudApiService(private val accountManager: CloudAccountManager) {
    private val baseUrl: String
        get() = CloudConfig.SERVER_BASE_URL

    data class LoginResponse(val token: String, val accountId: Long, val displayName: String)

    /**
     * 邮箱注册/登录返回的会话。
     *
     * mode = login：该邮箱已经绑定家庭，token 是完整令牌，可以直接访问照片；
     * mode = bind ：刚注册或还没加入家庭，token 是 15 分钟的临时令牌，
     *               只能用来调 /api/auth/family/claim 或 /api/auth/family/join。
     */
    data class MobileSession(
        val mode: String,
        val token: String,
        val userId: Long,
        val groupId: Long,
        val accountId: Long,
        val displayName: String,
        val email: String,
        val role: String,
        val bucket: String,
        val region: String,
        val endpoint: String,
        val ownerName: String,
        val message: String
    ) {
        val hasFamily: Boolean get() = mode == "login" && token.isNotEmpty()
    }

    /** 服务端下发的 OSS 直传凭证。邮箱登录时是 STS 临时凭证，会过期。 */
    data class OssCredential(
        val accessKeyId: String,
        val accessKeySecret: String,
        val securityToken: String,
        val expiration: String,
        val bucket: String,
        val endpoint: String,
        val region: String
    ) {
        // expiration 是 RFC3339 的 UTC 时间；解析不出来时返回 0，调用方会当成「需要立刻刷新」。
        fun expiresAtMillis(): Long {
            if (expiration.isEmpty()) return 0L
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(expiration)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    data class MetadataInfo(
        val exists: Boolean,
        val title: String? = null,
        val md5: String? = null,
        val memo: String? = null,
        val isFavorite: Boolean = false
    )

    data class BatchMetadataResponse(val results: Map<String, MetadataInfo>)

    /**
     * 添加 AK/SK 请求头到连接
     */
    private fun addAKSKHeaders(conn: HttpURLConnection) {
        // 邮箱登录的用户手里没有长期 AK/SK，OSS 凭证由服务端扮演家庭角色现取，
        // 这里再带上过期的 STS 密钥反而会让服务端签名失败，所以直接跳过。
        if (CloudConfig.usesStsCredentials) return
        conn.setRequestProperty("X-Master-AK-ID", CloudConfig.OSS_ACCESS_KEY_ID)
        conn.setRequestProperty("X-Master-AK-Secret", CloudConfig.OSS_ACCESS_KEY_SECRET)
    }

    suspend fun login(): Result<LoginResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/login")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("accessKeyId", CloudConfig.OSS_ACCESS_KEY_ID)
                put("accessKeySecret", CloudConfig.OSS_ACCESS_KEY_SECRET)
                put("bucket", CloudConfig.OSS_BUCKET_NAME)
                put("endpoint", CloudConfig.OSS_ENDPOINT)
                put("region", CloudConfig.OSS_REGION)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                Result.success(LoginResponse(
                    token = json.getString("token"),
                    accountId = json.getLong("account_id"),
                    displayName = json.getString("display_name")
                ))
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("login failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "login error", e)
            Result.failure(e)
        }
    }

    suspend fun getBatchMetadata(paths: List<String>): Result<BatchMetadataResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/metadata/check-batch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            val token = accountManager.jwtToken
            conn.setRequestProperty("Authorization", "Bearer $token")
            addAKSKHeaders(conn)
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("paths", JSONArray(paths))
            }

            // 日志：发送 metadata 查询请求
            Log.d("CloudMetadata", "===== 查询云端元数据 =====")
            Log.d("CloudMetadata", "URL: $url")
            Log.d("CloudMetadata", "Method: POST")
            Log.d("CloudMetadata", "Authorization: Bearer $token")
            Log.d("CloudMetadata", "请求路径数：${paths.size}")
            paths.forEach { path ->
                Log.d("CloudMetadata", "  - $path")
            }
            Log.d("CloudMetadata", "请求体：${requestBody.toString()}")

            // 打印 curl 命令
            val curlCmd = "curl -X POST \"$url\" \\\n  -H \"Content-Type: application/json\" \\\n  -H \"Authorization: Bearer $token\" \\\n  -d '${requestBody.toString()}'"
            Log.d("CloudMetadata", "curl 命令:\n$curlCmd")

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                Log.d("CloudMetadata", "响应状态码：${conn.responseCode}")
                Log.d("CloudMetadata", "响应体：$response")

                val json = JSONObject(response)
                val resultsJson = json.getJSONObject("results")
                val results = mutableMapOf<String, MetadataInfo>()

                paths.forEach { path ->
                    if (resultsJson.has(path)) {
                        val metaJson = resultsJson.getJSONObject(path)
                        val exists = metaJson.getBoolean("exists")
                        val title = if (metaJson.has("title")) metaJson.getString("title") else null
                        Log.d("CloudMetadata", "  $path: exists=$exists, title=$title")
                        results[path] = MetadataInfo(
                            exists = exists,
                            title = title,
                            md5 = if (metaJson.has("md5")) metaJson.getString("md5") else null,
                            memo = if (metaJson.has("memo")) metaJson.getString("memo") else null,
                            isFavorite = if (metaJson.has("is_favorite")) metaJson.getBoolean("is_favorite") else false
                        )
                    } else {
                        Log.d("CloudMetadata", "  $path: 服务器未返回数据")
                    }
                }

                Log.d("CloudMetadata", "========================")
                Result.success(BatchMetadataResponse(results))
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Log.e("CloudMetadata", "HTTP 错误：${conn.responseCode}, $error")
                Result.failure(Exception("batch metadata check failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "batch metadata error", e)
            Result.failure(e)
        }
    }

    suspend fun updateCloudTitle(fullPath: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/metadata/update-title")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("full_path", fullPath)
                put("title", title)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("update title failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "update title error", e)
            Result.failure(e)
        }
    }

    suspend fun updateFavoriteStatus(fullPath: String, isFavorite: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/metadata/update-favorite")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("full_path", fullPath)
                put("is_favorite", isFavorite)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("update favorite failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "update favorite error", e)
            Result.failure(e)
        }
    }

    suspend fun deleteCloudPhoto(fullPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/metadata/delete")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("full_path", fullPath)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("delete failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "delete error", e)
            Result.failure(e)
        }
    }

    suspend fun searchCloudMetadata(query: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = URL("$baseUrl/api/app/metadata/search?q=$encodedQuery")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                val pathsArray = json.getJSONArray("paths")
                val paths = mutableListOf<String>()
                for (i in 0 until pathsArray.length()) {
                    paths.add(pathsArray.getString(i))
                }
                Result.success(paths)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("search failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "search error", e)
            Result.failure(e)
        }
    }




    suspend fun checkFileExists(md5: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/files/exists/$md5")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                Result.success(json.getBoolean("exists"))
            } else {
                Result.failure(Exception("check file failed"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "check file error", e)
            Result.failure(e)
        }
    }

    suspend fun uploadComplete(
        md5: String,
        filename: String,
        size: Long,
        fileType: String,
        ossPath: String,
        fullPath: String,
        title: String,
        fileCreatedAt: Long?,
        properties: String,
        isFavorite: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/app/upload/complete")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("md5", md5)
                put("filename", filename)
                put("size", size)
                put("fileType", fileType)
                put("ossPath", ossPath)
                put("username", accountManager.displayName)
                put("title", title)
                put("fullPath", fullPath)
                if (fileCreatedAt != null) {
                    put("fileCreatedAt", fileCreatedAt)
                }
                put("properties", properties)
                put("isFavorite", isFavorite)
                put("group", "")
                put("tags", JSONArray())
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("upload complete failed: $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "upload complete error", e)
            Result.failure(e)
        }
    }

    // ==================== 邮箱注册 / 登录（服务端代理 Casdoor） ====================

    /** 请求服务端向邮箱发送注册验证码。60 秒内只能发一次，由 Casdoor 限流。 */
    suspend fun sendSignupCode(email: String): Result<Unit> {
        val body = JSONObject().put("email", email)
        return postJson("/api/auth/mobile/send-code", body).map { Unit }
    }

    /** 用邮箱 + 验证码 + 密码注册，成功后服务端直接返回图库令牌。 */
    suspend fun signup(email: String, code: String, password: String, displayName: String): Result<MobileSession> {
        val body = JSONObject()
            .put("email", email)
            .put("code", code)
            .put("password", password)
            .put("display_name", displayName)
        return postJson("/api/auth/mobile/signup", body).mapCatching { parseSession(it) }
    }

    /** 邮箱 + 密码登录。 */
    suspend fun emailLogin(email: String, password: String): Result<MobileSession> {
        val body = JSONObject().put("email", email).put("password", password)
        return postJson("/api/auth/mobile/login", body).mapCatching { parseSession(it) }
    }

    /** 用户主的邀请码加入家庭，返回带完整令牌的会话。 */
    suspend fun joinFamily(pendingToken: String, code: String, displayName: String): Result<MobileSession> {
        val body = JSONObject().put("code", code).put("display_name", displayName)
        return postJson("/api/auth/family/join", body, pendingToken).mapCatching { parseSession(it) }
    }

    /** 户主用一次性阿里云 AK/SK 开通家庭。AK/SK 只在这次请求里用，不会存在本地。 */
    suspend fun claimFamily(
        pendingToken: String,
        accessKeyId: String,
        accessKeySecret: String,
        region: String,
        bucketName: String,
        displayName: String
    ): Result<MobileSession> {
        val body = JSONObject()
            .put("access_key_id", accessKeyId)
            .put("access_key_secret", accessKeySecret)
            .put("region", region)
            .put("bucket_name", bucketName)
            .put("display_name", displayName)
        return postJson("/api/auth/family/claim", body, pendingToken).mapCatching { parseSession(it) }
    }

    /** 拉取 OSS 直传凭证；邮箱登录时拿到的是 STS 临时凭证，过期前需要重新拉取。 */
    suspend fun fetchOssCredential(): Result<OssCredential> {
        return getJson("/api/app/oss-credential").mapCatching { json ->
            OssCredential(
                accessKeyId = json.optString("accessKeyId"),
                accessKeySecret = json.optString("accessKeySecret"),
                securityToken = json.optString("securityToken"),
                expiration = json.optString("expiration"),
                bucket = json.optString("bucket"),
                endpoint = json.optString("endpoint"),
                region = json.optString("region")
            )
        }
    }

    private fun parseSession(json: JSONObject) = MobileSession(
        mode = json.optString("mode"),
        token = json.optString("token"),
        userId = json.optLong("user_id"),
        groupId = json.optLong("group_id"),
        accountId = json.optLong("account_id"),
        displayName = json.optString("display_name"),
        email = json.optString("email"),
        role = json.optString("role"),
        bucket = json.optString("bucket"),
        region = json.optString("region"),
        endpoint = json.optString("endpoint"),
        ownerName = json.optString("owner_name"),
        message = json.optString("message")
    )

    // ==================== 通用请求封装 ====================

    private suspend fun postJson(path: String, body: JSONObject, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                if (!token.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                addAKSKHeaders(conn)
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { writer -> writer.write(body.toString()) }

                val text = readBody(conn)
                if (conn.responseCode in 200..299) {
                    Result.success(JSONObject(text))
                } else {
                    Result.failure(errorOf(conn.responseCode, text))
                }
            } catch (e: CloudApiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Log.e("CloudApiService", "POST $path failed", e)
                Result.failure(e)
            }
        }

    private suspend fun getJson(path: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            addAKSKHeaders(conn)

            val text = readBody(conn)
            if (conn.responseCode in 200..299) {
                Result.success(JSONObject(text))
            } else {
                Result.failure(errorOf(conn.responseCode, text))
            }
        } catch (e: CloudApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("CloudApiService", "GET $path failed", e)
            Result.failure(e)
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    // 服务端统一用 {"error": "..."} 返回中文提示，取不到就把原始响应带出来方便排查。
    private fun errorOf(statusCode: Int, body: String): Exception {
        val message = try {
            JSONObject(body).optString("error")
        } catch (e: Exception) {
            ""
        }
        val text = message.ifEmpty { body.trim() }.ifEmpty { "HTTP $statusCode" }
        return CloudApiException(statusCode, text)
    }

    companion object {
        fun calculateMD5(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Confirm a QR web login session by delivering the local config string to the server.
     * Called after the App user scans the Web QR code.
     * Requires the App to be logged in (JWT token is attached automatically).
     */
    suspend fun confirmQrSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 邮箱登录的家庭账号手里只有服务端下发的 STS 临时凭证，一小时后就失效，
            // 塞进配置串交给网页端只会得到一个很快过期的登录。网页端现在支持直接用同一个
            // 邮箱登录，所以这里拦下来并给出明确指引。
            if (CloudConfig.usesStsCredentials) {
                return@withContext Result.failure(Exception("邮箱登录的账号请直接在网页端用邮箱登录，不需要扫码授权"))
            }
            // Build the config string from current CloudConfig (same format as login)
            val configJson = org.json.JSONObject().apply {
                put("access_key_id", CloudConfig.OSS_ACCESS_KEY_ID)
                put("access_key_secret", CloudConfig.OSS_ACCESS_KEY_SECRET)
                put("bucket", CloudConfig.OSS_BUCKET_NAME)
                put("endpoint", CloudConfig.OSS_ENDPOINT)
                put("region", CloudConfig.OSS_REGION)
            }
            val configString = android.util.Base64.encodeToString(
                configJson.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            val url = URL("$baseUrl/api/app/qr-confirm")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${accountManager.jwtToken}")
            conn.doOutput = true

            val requestBody = org.json.JSONObject().apply {
                put("session_id", sessionId)
                put("config_string", configString)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Result.failure(Exception("qr confirm failed (${ conn.responseCode }): $error"))
            }
        } catch (e: Exception) {
            Log.e("CloudApiService", "confirmQrSession error", e)
            Result.failure(e)
        }
    }
}
