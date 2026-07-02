package org.fossify.gallery.helpers

import android.util.Log
import org.apache.sanselan.common.byteSources.ByteSourceInputStream
import org.apache.sanselan.formats.jpeg.JpegImageParser
import java.io.File
import java.io.RandomAccessFile

data class MotionPhotoInfo(
    val videoOffset: Long,
    val videoLength: Long
)

object MotionPhotoDetector {
    private const val TAG = "MotionPhotoDetector"

    private val MOTION_PHOTO_PATTERNS = listOf(
        Regex("""GCamera:MotionPhoto\s*=\s*"1""""),
        Regex("""Camera:MotionPhoto\s*=\s*"1""""),
        Regex("""Camera:MicroVideo\s*=\s*"1""""),
    )

    private val OFFSET_PATTERNS = listOf(
        Regex("""GCamera:MotionPhotoOffset\s*=\s*"(\d+)""""),
        Regex("""Camera:MotionPhotoOffset\s*=\s*"(\d+)""""),
        Regex("""Camera:MicroVideoOffset\s*=\s*"(\d+)""""),
    )

    /**
     * 检测一个 JPEG 文件是否为 Motion Photo。
     * 返回 MotionPhotoInfo (含视频偏移量和长度), 非 Motion Photo 返回 null。
     *
     * 核心原则: 必须在文件中找到有效的 MP4 ftyp box 才算真正的 Motion Photo。
     * 仅有 XMP 标签但无内嵌 MP4 数据的文件 (如微信导出) 不算 Motion Photo。
     */
    fun detect(filePath: String): MotionPhotoInfo? {
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) return null

            val xmpXml = file.inputStream().use { stream ->
                JpegImageParser().getXmpXml(
                    ByteSourceInputStream(stream, file.name),
                    HashMap<String, Any>()
                )
            }

            if (xmpXml.isNullOrEmpty()) return null

            val isMotionPhoto = MOTION_PHOTO_PATTERNS.any { it.containsMatchIn(xmpXml) }
            if (!isMotionPhoto) return null

            val fileLength = file.length()

            // 核心策略: 扫描文件找到 MP4 ftyp box, 验证数据确实是 MP4
            val mp4Offset = findMp4Offset(file)
            if (mp4Offset != null) {
                val videoLength = fileLength - mp4Offset
                Log.d(TAG, "Found MP4 ftyp box at offset=$mp4Offset, length=$videoLength")
                return MotionPhotoInfo(videoOffset = mp4Offset, videoLength = videoLength)
            }

            // 备选: 解析 XMP offset 标签, 但必须验证偏移处有有效 MP4 数据
            val jpegEndOffset = findJpegEoiOffset(file)
            val xmpOffset = OFFSET_PATTERNS
                .firstNotNullOfOrNull { pattern ->
                    pattern.find(xmpXml)?.groupValues?.getOrNull(1)?.toLongOrNull()
                }

            if (xmpOffset != null && xmpOffset > 0) {
                val candidates = mutableListOf<Long>()
                // 尝试绝对偏移
                if (xmpOffset < fileLength) candidates.add(xmpOffset)
                // 尝试相对偏移 (从 JPEG 末尾算起)
                if (jpegEndOffset != null) {
                    val relative = jpegEndOffset + xmpOffset
                    if (relative < fileLength) candidates.add(relative)
                }

                for (offset in candidates) {
                    if (hasFtypAtOffset(file, offset)) {
                        Log.d(TAG, "Valid MP4 at XMP offset=$offset")
                        return MotionPhotoInfo(videoOffset = offset, videoLength = fileLength - offset)
                    }
                }
            }

            Log.d(TAG, "XMP has motion photo tags but no embedded MP4 found (WeChat export?): $filePath")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect motion photo: $filePath", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM during motion photo detection: $filePath")
            null
        }
    }

    /**
     * 检查文件在指定偏移处是否有 MP4 ftyp box 签名。
     * MP4 box 格式: [4 bytes size][4 bytes "ftyp"][brand...]
     */
    private fun hasFtypAtOffset(file: File, offset: Long): Boolean {
        if (offset < 0 || offset + 8 > file.length()) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset + 4) // 跳过 box size, 直接读 box type
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            typeBytes[0] == 0x66.toByte() && // 'f'
            typeBytes[1] == 0x74.toByte() && // 't'
            typeBytes[2] == 0x79.toByte() && // 'y'
            typeBytes[3] == 0x70.toByte()    // 'p'
        }
    }

    /**
     * 从文件尾部向前查找 JPEG EOI 标记 (FF D9)。
     * 返回 EOI 之后的位置 (即附加数据的起始位置)。
     */
    private fun findJpegEoiOffset(file: File): Long? {
        val fileLength = file.length()
        if (fileLength < 4) return null

        return RandomAccessFile(file, "r").use { raf ->
            // 搜索最后 64KB (JPEG EOI 通常在文件末尾附近)
            val searchSize = minOf(65536L, fileLength)
            val searchStart = fileLength - searchSize
            raf.seek(searchStart)
            val buffer = ByteArray(searchSize.toInt())
            raf.readFully(buffer)

            // 从后向前搜索 FF D9
            for (i in buffer.size - 2 downTo 0) {
                if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD9.toByte()) {
                    val eoiEnd = searchStart + i + 2
                    if (eoiEnd < fileLength) {
                        return@use eoiEnd
                    }
                }
            }
            null
        }
    }

    /**
     * 扫描文件中 MP4 ftyp box 签名 (作为最后的兜底策略)。
     * 搜索最后 10MB 的数据。
     */
    private fun findMp4Offset(file: File): Long? {
        val fileLength = file.length()
        if (fileLength < 12) return null

        return RandomAccessFile(file, "r").use { raf ->
            val searchSize = minOf(10_000_000L, fileLength)
            val searchStart = fileLength - searchSize
            raf.seek(searchStart)
            val buffer = ByteArray(searchSize.toInt())
            raf.readFully(buffer)

            val ftypSignature = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp"
            for (i in 4 until buffer.size - 4) {
                if (buffer[i] == ftypSignature[0] &&
                    buffer[i + 1] == ftypSignature[1] &&
                    buffer[i + 2] == ftypSignature[2] &&
                    buffer[i + 3] == ftypSignature[3]
                ) {
                    val offset = searchStart + i - 4
                    if (offset > 0 && offset < fileLength) {
                        return@use offset
                    }
                }
            }
            null
        }
    }
}
