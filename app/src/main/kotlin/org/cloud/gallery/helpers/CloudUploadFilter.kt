package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudApiService
import org.fossify.gallery.cloud.CloudStatusManager
import org.fossify.gallery.models.Medium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object CloudUploadFilterState {
    @Volatile
    var showOnlyUnuploadedPhotos: Boolean = false

    fun reset() {
        showOnlyUnuploadedPhotos = false
    }
}

object CloudUploadFilter {
    private const val BATCH_SIZE = 30

    fun filterMedia(
        media: List<Medium>,
        uploadedStatuses: Map<String, Boolean?>,
        enabled: Boolean = CloudUploadFilterState.showOnlyUnuploadedPhotos
    ): ArrayList<Medium> {
        if (!enabled) {
            return ArrayList(media)
        }

        val filtered = ArrayList<Medium>(media.size)
        media.forEach { item ->
            val status = uploadedStatuses[item.path.lowercase()]
            if (status != true) {
                filtered.add(item)
            }
        }
        return filtered
    }

    fun filterMedia(context: Context, media: List<Medium>): ArrayList<Medium> {
        if (!CloudUploadFilterState.showOnlyUnuploadedPhotos) {
            return ArrayList(media)
        }

        val accountManager = CloudAccountManager.getInstance(context)
        if (!accountManager.isLoggedIn || media.isEmpty()) {
            return ArrayList(media)
        }

        val uploadedStatuses = fetchUploadedStatuses(context, media)
        return filterMedia(media, uploadedStatuses, true)
    }

    private fun fetchUploadedStatuses(context: Context, media: List<Medium>): Map<String, Boolean?> {
        val accountManager = CloudAccountManager.getInstance(context)
        val apiService = CloudApiService(accountManager)
        val statusManager = CloudStatusManager.getInstance(context)
        val statuses = mutableMapOf<String, Boolean?>()
        val normalizedPaths = media.map { it.path.lowercase() }.distinct()

        normalizedPaths.chunked(BATCH_SIZE).forEach { batch ->
            val result = runBlocking {
                withContext(Dispatchers.IO) {
                    apiService.getBatchMetadata(batch)
                }
            }
            if (result.isSuccess) {
                val response = result.getOrNull() ?: return@forEach
                batch.forEach { path ->
                    val metaInfo = response.results[path]
                    val status = metaInfo?.exists
                    statuses[path] = status
                    if (status != null) {
                        statusManager.updateUploadedStatus(path, status)
                    }
                }
            } else {
                batch.forEach { path ->
                    statuses[path] = null
                }
            }
        }

        return statuses
    }
}
