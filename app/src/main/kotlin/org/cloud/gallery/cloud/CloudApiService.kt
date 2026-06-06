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

class CloudApiService(private val accountManager: CloudAccountManager) {
    private val baseUrl: String
        get() = CloudConfig.SERVER_BASE_URL

    data class LoginResponse(val token: String, val accountId: Long, val displayName: String)

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
