package org.fossify.gallery.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CloudStatusManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountManager = CloudAccountManager.getInstance(context)
    private val apiService = CloudApiService(accountManager)
    private val uploadedPaths = ConcurrentHashMap<String, Boolean>()
    private val cloudTitles = ConcurrentHashMap<String, String>()
    private val cloudFavorites = ConcurrentHashMap<String, Boolean>()
    private val pendingQueries = ConcurrentHashMap<String, Boolean>()
    private val pendingTitles = ConcurrentHashMap<String, String>()

    private val statusListeners = CopyOnWriteArrayList<StatusCallback>()

    // 所有路径键统一使用小写，适配 emulated storage 大小写不敏感特性
    private fun normalize(path: String) = path.lowercase()

    interface StatusCallback {
        fun onStatusUpdated(path: String, isUploaded: Boolean?)
    }

    fun addStatusListener(listener: StatusCallback) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusCallback) {
        statusListeners.remove(listener)
    }

    fun isUploaded(path: String): Boolean? = uploadedPaths[normalize(path)]

    fun getCloudTitle(path: String): String? = cloudTitles[normalize(path)]

    fun updateTitle(path: String, title: String) {
        cloudTitles[normalize(path)] = title
    }

    fun isFavorite(path: String): Boolean? = cloudFavorites[normalize(path)]

    fun updateFavorite(path: String, isFavorite: Boolean) {
        cloudFavorites[normalize(path)] = isFavorite
    }

    fun setPendingTitle(path: String, title: String) {
        pendingTitles[normalize(path)] = title
    }

    fun consumePendingTitle(path: String): String? = pendingTitles.remove(normalize(path))

    fun isPending(path: String): Boolean = pendingQueries[normalize(path)] == true

    fun queryUploadStatus(paths: List<String>, callback: StatusCallback) {
        if (!accountManager.isLoggedIn) return

        val normalizedPaths = paths.map { normalize(it) }
        val pathsToQuery = normalizedPaths.filter { !uploadedPaths.containsKey(it) && !pendingQueries.containsKey(it) }
        if (pathsToQuery.isEmpty()) return

        pathsToQuery.forEach { pendingQueries[it] = true }

        scope.launch {
            try {
                val batchSize = 30
                pathsToQuery.chunked(batchSize).forEach { batch ->
                    val result = apiService.getBatchMetadata(batch)
                    if (result.isSuccess) {
                        val response = result.getOrNull()!!
                        batch.forEach { path ->
                            val metaInfo = response.results[path]

                            if (metaInfo != null) {
                                val isUploaded = metaInfo.exists
                                uploadedPaths[path] = isUploaded

                                if (isUploaded) {
                                    if (!metaInfo.title.isNullOrEmpty()) {
                                        cloudTitles[path] = metaInfo.title!!
                                    }
                                    cloudFavorites[path] = metaInfo.isFavorite
                                }
                                pendingQueries.remove(path)
                                callback.onStatusUpdated(path, isUploaded)
                                statusListeners.forEach { it.onStatusUpdated(path, isUploaded) }
                            } else {
                                pendingQueries.remove(path)
                                callback.onStatusUpdated(path, null)
                                statusListeners.forEach { it.onStatusUpdated(path, null) }
                            }
                        }
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message
                        Log.e("CloudStatusManager", "query failed: $errorMsg")
                        batch.forEach { path ->
                            pendingQueries.remove(path)
                            callback.onStatusUpdated(path, null)
                            statusListeners.forEach { it.onStatusUpdated(path, null) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudStatusManager", "query error", e)
                pathsToQuery.forEach { pendingQueries.remove(it) }
                pathsToQuery.forEach { path ->
                    callback.onStatusUpdated(path, null)
                    statusListeners.forEach { it.onStatusUpdated(path, null) }
                }
            }
        }
    }

    fun markAsUploaded(path: String) {
        val n = normalize(path)
        uploadedPaths[n] = true
        statusListeners.forEach { it.onStatusUpdated(path, true) }
    }

    fun updateUploadedStatus(path: String, isUploaded: Boolean) {
        val n = normalize(path)
        uploadedPaths[n] = isUploaded
        if (!isUploaded) {
            cloudTitles.remove(n)
            cloudFavorites.remove(n)
        }
        statusListeners.forEach { it.onStatusUpdated(path, isUploaded) }
    }

    fun clearCache() {
        uploadedPaths.clear()
        cloudTitles.clear()
        cloudFavorites.clear()
        pendingQueries.clear()
        pendingTitles.clear()
    }

    companion object {
        @Volatile
        private var instance: CloudStatusManager? = null

        fun getInstance(context: Context): CloudStatusManager {
            return instance ?: synchronized(this) {
                instance ?: CloudStatusManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
