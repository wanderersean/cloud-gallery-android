package org.fossify.gallery.helpers

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object MotionPhotoExtractor {
    private const val TAG = "MotionPhotoExtractor"
    private const val BUFFER_SIZE = 8192

    /**
     * 提取 Motion Photo 内嵌的视频到缓存目录。
     * 使用文件路径 + 偏移量的 MD5 做缓存 key, 避免重复提取。
     *
     * @param filePath JPEG 文件路径
     * @param info MotionPhotoInfo (含视频偏移量和长度)
     * @param cacheDir 缓存目录 (通常为 context.cacheDir)
     * @return 提取的 MP4 临时文件, 失败返回 null
     */
    fun extract(filePath: String, info: MotionPhotoInfo, cacheDir: File): File? {
        return try {
            val cacheKey = md5("$filePath:${info.videoOffset}")
            val cachedFile = File(cacheDir, "motion_photo_$cacheKey.mp4")

            // 缓存命中: 文件存在且大小匹配
            if (cachedFile.exists() && cachedFile.length() == info.videoLength) {
                Log.d(TAG, "Cache hit: ${cachedFile.name}")
                return cachedFile
            }

            // 提取视频
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) return null

            val actualLength = sourceFile.length() - info.videoOffset
            if (actualLength <= 0) {
                Log.w(TAG, "Invalid video offset: ${info.videoOffset}, file length: ${sourceFile.length()}")
                return null
            }

            // 确保缓存目录存在
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            RandomAccessFile(sourceFile, "r").use { raf ->
                raf.seek(info.videoOffset)
                cachedFile.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = actualLength
                    while (remaining > 0) {
                        val toRead = minOf(BUFFER_SIZE, remaining.toInt())
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }

            if (cachedFile.exists() && cachedFile.length() > 0) {
                Log.d(TAG, "Extracted motion video: ${cachedFile.name} (${cachedFile.length()} bytes)")
                cachedFile
            } else {
                Log.w(TAG, "Extraction produced empty file")
                cachedFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract motion video: $filePath", e)
            null
        }
    }

    /**
     * 清理过期的缓存文件。
     */
    fun cleanupCache(cacheDir: File, maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        cacheDir.listFiles()?.filter {
            it.name.startsWith("motion_photo_") && it.name.endsWith(".mp4") && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(input.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
