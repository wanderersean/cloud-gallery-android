package org.fossify.gallery.cloud

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.common.auth.OSSPlainTextAKSKCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.alibaba.sdk.android.oss.model.PutObjectTaggingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class OssUploader(context: Context) {
    private val appContext = context.applicationContext

    // 不使用 lazy，每次上传时按当前 CloudConfig 实时创建 OSSClient，避免登录切换后缓存旧 AKSK
    private fun buildOssClient(): OSSClient? {
        val endpoint = CloudConfig.OSS_ENDPOINT
        val akId = CloudConfig.OSS_ACCESS_KEY_ID
        val akSecret = CloudConfig.OSS_ACCESS_KEY_SECRET
        if (endpoint.isEmpty() || akId.isEmpty() || akSecret.isEmpty()) return null
        Log.d("OssUploader", "buildOssClient: endpoint=$endpoint, akId=${akId.take(8)}..., akSecret=${akSecret.take(4)}****")
        val credentialProvider = OSSPlainTextAKSKCredentialProvider(akId, akSecret)
        val conf = ClientConfiguration().apply {
            connectionTimeout = 60 * 1000
            socketTimeout = 60 * 1000
            maxConcurrentRequest = 5
            maxErrorRetry = 2
        }
        return OSSClient(appContext, "https://$endpoint", credentialProvider, conf)
    }

    suspend fun uploadFile(file: File, md5: String, onProgress: ((Int) -> Unit)? = null): Result<String> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val objectKey = md5
            val put = PutObjectRequest(CloudConfig.OSS_BUCKET_NAME, objectKey, file.absolutePath)

            put.progressCallback = { _, currentSize, totalSize ->
                val progress = if (totalSize > 0) (currentSize * 100 / totalSize).toInt() else 0
                // Log.d("OssUploader", "upload progress: $progress%")
                onProgress?.invoke(progress)
            }

            try {
                val client = buildOssClient()
                if (client == null) {
                    continuation.resume(Result.failure(Exception("OSS not configured")))
                    return@suspendCancellableCoroutine
                }
                Log.d("OssUploader", "uploadFile: bucket=${CloudConfig.OSS_BUCKET_NAME}, objectKey=$objectKey, akId=${CloudConfig.OSS_ACCESS_KEY_ID.take(8)}...")
                val result = client.putObject(put)
                if (result.statusCode == 200) {
                    val ossPath = "oss://${CloudConfig.OSS_BUCKET_NAME}/$objectKey"
                    continuation.resume(Result.success(ossPath))
                } else {
                    continuation.resume(Result.failure(Exception("upload failed: ${result.statusCode}")))
                }
            } catch (e: Exception) {
                Log.e("OssUploader", "upload error", e)
                continuation.resume(Result.failure(e))
            }
        }
    }

    /**
     * 设置 OSS 对象的 tag
     * @param objectKey OSS 对象键（MD5）
     * @param title 标题
     */
    suspend fun setObjectTag(objectKey: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = buildOssClient()
            if (client == null) {
                return@withContext Result.failure(Exception("OSS not configured"))
            }

            // 使用 Map 格式设置标签
            val tagging = mapOf("title" to title)
            val request = PutObjectTaggingRequest(CloudConfig.OSS_BUCKET_NAME, objectKey, tagging)
            val result = client.putObjectTagging(request)

            if (result.statusCode == 200) {
                Log.d("OssUploader", "set tag success: $objectKey, title=$title")
                Result.success(Unit)
            } else {
                Log.e("OssUploader", "set tag failed: ${result.statusCode}")
                Result.failure(Exception("set tag failed: ${result.statusCode}"))
            }
        } catch (e: Exception) {
            Log.e("OssUploader", "set tag error", e)
            Result.failure(e)
        }
    }
}
