package org.fossify.gallery.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import org.fossify.gallery.R

class CloudUploadManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 确保 CloudConfig 已从 SharedPreferences 加载
        // 这是因为上传可能在未加载 CloudConfig 的 Activity 中被触发（如 ViewPagerActivity）
        CloudConfig.loadFromPreferences(context)
    }

    private val accountManager = CloudAccountManager.getInstance(context)
    private val apiService = CloudApiService(accountManager)
    internal val ossUploader = OssUploader(context)

    private val uploadJobs = ConcurrentHashMap<String, Job>()
    private val _uploadTasks = CopyOnWriteArrayList<UploadTask>()
    val uploadTasks: List<UploadTask> get() = _uploadTasks.toList()

    private val taskListeners = CopyOnWriteArrayList<TaskListListener>()

    data class UploadTask(
        val path: String,
        val filename: String,
        var status: UploadStatus = UploadStatus.PENDING,
        var progress: Int = 0,
        var errorMessage: String? = null
    )

    enum class UploadStatus {
        PENDING, UPLOADING, SUCCESS, FAILED, CANCELLED
    }

    interface UploadCallback {
        fun onUploadStart(path: String)
        fun onUploadProgress(path: String, progress: Int)
        fun onUploadSuccess(path: String)
        fun onUploadError(path: String, error: String)
    }

    interface TaskListListener {
        fun onTaskListUpdated()
    }

    fun addTaskListListener(listener: TaskListListener) {
        taskListeners.add(listener)
    }

    fun removeTaskListListener(listener: TaskListListener) {
        taskListeners.remove(listener)
    }

    private fun notifyTaskListUpdated() {
        taskListeners.forEach { it.onTaskListUpdated() }
    }

    fun uploadFile(filePath: String, callback: UploadCallback, title: String? = null) {
        if (!accountManager.isLoggedIn) {
            callback.onUploadError(filePath, "请先登录云端账号")
            return
        }

        val activeStatuses = listOf(UploadStatus.PENDING, UploadStatus.UPLOADING)
        if (_uploadTasks.any { it.path.lowercase() == filePath.lowercase() && it.status in activeStatuses }) {
            Log.d("CloudUploadManager", "file already in upload queue, skip: $filePath")
            return
        }

        val file = File(filePath)
        val task = UploadTask(filePath, file.name, UploadStatus.PENDING)
        _uploadTasks.add(task)
        notifyTaskListUpdated()

        val job = scope.launch {
            try {
                task.status = UploadStatus.UPLOADING
                notifyTaskListUpdated()
                callback.onUploadStart(filePath)

                if (!file.exists()) {
                    task.status = UploadStatus.FAILED
                    task.errorMessage = "文件不存在"
                    notifyTaskListUpdated()
                    callback.onUploadError(filePath, "文件不存在")
                    return@launch
                }

                val md5 = CloudApiService.calculateMD5(file)
                Log.d("CloudUploadManager", "file md5: $md5")

                val isFavorite = org.fossify.gallery.databases.GalleryDatabase.getInstance(context).FavoritesDao().isFavorite(filePath)
                val statusManager = CloudStatusManager.getInstance(context)

                val existsResult = apiService.checkFileExists(md5)
                if (existsResult.isSuccess && existsResult.getOrNull() == true) {
                    Log.d("CloudUploadManager", "file exists, skip upload")
                    task.progress = 100
                    notifyTaskListUpdated()
                    callback.onUploadProgress(filePath, 100)

                    val finalTitle = statusManager.consumePendingTitle(filePath) ?: title ?: ""
                    val uploadCompleteResult = apiService.uploadComplete(
                        md5 = md5,
                        filename = file.name,
                        size = file.length(),
                        fileType = getFileType(file),
                        ossPath = "oss://${CloudConfig.OSS_BUCKET_NAME}/$md5",
                        fullPath = filePath,
                        title = finalTitle,
                        fileCreatedAt = getFileCreationTime(file),
                        properties = getAllProperties(file),
                        isFavorite = isFavorite
                    )
                    if (uploadCompleteResult.isSuccess) {
                        task.status = UploadStatus.SUCCESS
                        if (finalTitle.isNotEmpty()) {
                            statusManager.updateTitle(filePath, finalTitle)
                            // 秒传情况下也同步标题到 OSS tag
                            scope.launch {
                                val tagResult = ossUploader.setObjectTag(md5, finalTitle)
                                withContext(Dispatchers.Main) {
                                    if (tagResult.isSuccess) {
                                        notifyTaskListUpdated()
                                        callback.onUploadSuccess(filePath)
                                    } else {
                                        val errorMsg = tagResult.exceptionOrNull()?.message ?: "未知错误"
                                        notifyTaskListUpdated()
                                        callback.onUploadError(filePath, "${context.getString(R.string.cloud_title_update_failed_tag)}: $errorMsg")
                                    }
                                }
                            }
                            return@launch
                        }
                        notifyTaskListUpdated()
                        callback.onUploadSuccess(filePath)
                    } else {
                        task.status = UploadStatus.FAILED
                        task.errorMessage = uploadCompleteResult.exceptionOrNull()?.message ?: "秒传失败"
                        notifyTaskListUpdated()
                        callback.onUploadError(filePath, task.errorMessage!!)
                    }
                    return@launch
                }

                task.progress = 10
                notifyTaskListUpdated()
                callback.onUploadProgress(filePath, 10)

                val uploadResult = ossUploader.uploadFile(file, md5) { uploadProgress ->
                    // Map upload progress (0-100) to overall progress (10-90)
                    val overallProgress = 10 + (uploadProgress * 80 / 100)
                    if (task.progress != overallProgress) {
                        task.progress = overallProgress
                        notifyTaskListUpdated()
                        callback.onUploadProgress(filePath, overallProgress)
                    }
                }
                
                if (uploadResult.isFailure) {
                    task.status = UploadStatus.FAILED
                    task.errorMessage = uploadResult.exceptionOrNull()?.message ?: "上传失败"
                    notifyTaskListUpdated()
                    callback.onUploadError(filePath, task.errorMessage!!)
                    return@launch
                }

                task.progress = 90
                notifyTaskListUpdated()
                callback.onUploadProgress(filePath, 90)

                val ossPath = uploadResult.getOrNull() ?: ""
                val finalTitle = statusManager.consumePendingTitle(filePath) ?: title ?: ""
                val completeResult = apiService.uploadComplete(
                    md5 = md5,
                    filename = file.name,
                    size = file.length(),
                    fileType = getFileType(file),
                    ossPath = ossPath,
                    fullPath = filePath,
                    title = finalTitle,
                    fileCreatedAt = getFileCreationTime(file),
                    properties = getAllProperties(file),
                    isFavorite = isFavorite
                )

                if (completeResult.isSuccess) {
                    task.status = UploadStatus.SUCCESS
                    if (finalTitle.isNotEmpty()) {
                        statusManager.updateTitle(filePath, finalTitle)
                        // 同步标题到 OSS tag
                        scope.launch {
                            val tagResult = ossUploader.setObjectTag(md5, finalTitle)
                            withContext(Dispatchers.Main) {
                                if (tagResult.isSuccess) {
                                    callback.onUploadSuccess(filePath)
                                } else {
                                    val errorMsg = tagResult.exceptionOrNull()?.message ?: "未知错误"
                                    callback.onUploadError(filePath, "${context.getString(R.string.cloud_title_update_failed_tag)}: $errorMsg")
                                }
                            }
                        }
                        return@launch
                    }
                    task.progress = 100
                    notifyTaskListUpdated()
                    callback.onUploadSuccess(filePath)
                } else {
                    task.status = UploadStatus.FAILED
                    task.errorMessage = completeResult.exceptionOrNull()?.message ?: "上传完成回调失败"
                    notifyTaskListUpdated()
                    callback.onUploadError(filePath, task.errorMessage!!)
                }
            } catch (e: Exception) {
                Log.e("CloudUploadManager", "upload error", e)
                task.status = UploadStatus.FAILED
                task.errorMessage = e.message ?: "未知错误"
                notifyTaskListUpdated()
                callback.onUploadError(filePath, task.errorMessage!!)
            } finally {
                uploadJobs.remove(filePath)
            }
        }
        uploadJobs[filePath] = job
    }

    fun cancelUpload(filePath: String) {
        uploadJobs[filePath]?.cancel()
        uploadJobs.remove(filePath)
        _uploadTasks.find { it.path == filePath }?.let {
            it.status = UploadStatus.CANCELLED
            notifyTaskListUpdated()
        }
    }

    fun clearCompletedTasks() {
        _uploadTasks.removeAll { it.status in listOf(UploadStatus.SUCCESS, UploadStatus.FAILED, UploadStatus.CANCELLED) }
        notifyTaskListUpdated()
    }

    fun getActiveTaskCount(): Int {
        return _uploadTasks.count { it.status in listOf(UploadStatus.PENDING, UploadStatus.UPLOADING) }
    }

    fun getActiveTask(path: String): UploadTask? {
        return _uploadTasks.find { it.path == path && it.status in listOf(UploadStatus.PENDING, UploadStatus.UPLOADING) }
    }

    private fun getFileType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    companion object {
        @Volatile
        private var instance: CloudUploadManager? = null

        fun getInstance(context: Context): CloudUploadManager {
            return instance ?: synchronized(this) {
                instance ?: CloudUploadManager(context.applicationContext).also { instance = it }
            }
        }
    }


    private fun getFileCreationTime(file: File): Long {
        try {
            val exif = ExifInterface(file.absolutePath)
            val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

            if (dateString != null) {
                // Usually "yyyy:MM:dd HH:mm:ss"
                val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                val date = format.parse(dateString)
                if (date != null) {
                    return date.time / 1000
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        return file.lastModified() / 1000
    }



    private fun getAllProperties(file: File): String {
        try {
            val exif = ExifInterface(file.absolutePath)
            val props = org.json.JSONObject()
            
            fun putStr(tag: String, key: String) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrEmpty()) {
                    props.put(key, value)
                }
            }
            
            fun putDouble(tag: String, key: String) {
                val value = exif.getAttributeDouble(tag, Double.MIN_VALUE)
                if (value != Double.MIN_VALUE) {
                    props.put(key, value)
                }
            }

            fun putInt(tag: String, key: String) {
                val value = exif.getAttributeInt(tag, Int.MIN_VALUE)
                if (value != Int.MIN_VALUE) {
                    props.put(key, value)
                }
            }
            
            // Device
            putStr(ExifInterface.TAG_MAKE, "make")
            putStr(ExifInterface.TAG_MODEL, "model")
            putStr(ExifInterface.TAG_SOFTWARE, "software")
            
            // Dimensions
            putInt(ExifInterface.TAG_IMAGE_WIDTH, "width")
            putInt(ExifInterface.TAG_IMAGE_LENGTH, "height")
            putInt(ExifInterface.TAG_ORIENTATION, "orientation")

            // Camera Settings
            putDouble(ExifInterface.TAG_F_NUMBER, "fNumber")
            putDouble(ExifInterface.TAG_EXPOSURE_TIME, "exposureTime")
            putStr(ExifInterface.TAG_ISO_SPEED_RATINGS, "iso") 
            putDouble(ExifInterface.TAG_FOCAL_LENGTH, "focalLength")
            putInt(ExifInterface.TAG_FLASH, "flash")
            putInt(ExifInterface.TAG_WHITE_BALANCE, "whiteBalance")
            
            // GPS
            val latLong = exif.latLong
            if (latLong != null) {
                props.put("latitude", latLong[0])
                props.put("longitude", latLong[1])
            }
            putDouble(ExifInterface.TAG_GPS_ALTITUDE, "altitude")
            
            // Date
            putStr(ExifInterface.TAG_DATETIME_ORIGINAL, "dateTimeOriginal")
            putStr(ExifInterface.TAG_DATETIME_DIGITIZED, "dateTimeDigitized")
            putStr(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "offsetTimeOriginal")
            
            return props.toString()
        } catch (e: Exception) {
            return "{}"
        }
    }
}
