package org.fossify.gallery.helpers

import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudApiService
import org.fossify.gallery.cloud.CloudStatusManager
import org.fossify.gallery.cloud.CloudUploadManager
import org.fossify.gallery.models.Medium

object CloudActionsHelper {
    fun showCloudActionsMenu(activity: BaseSimpleActivity, medium: Medium, callback: () -> Unit) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_cloud_actions, null)

        val textColor = activity.getProperTextColor()
        val backgroundColor = activity.getProperBackgroundColor()
        
        val supportsBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        val alpha = if (supportsBlur) 100 else 245

        val shapeDrawable = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bottom_sheet_bg)?.mutate()
        val transparentBackgroundColor = androidx.core.graphics.ColorUtils.setAlphaComponent(backgroundColor, alpha)
        shapeDrawable?.setTint(transparentBackgroundColor)
        view.background = shapeDrawable
        
        view.findViewById<TextView>(R.id.cloud_actions_title).apply {
            setTextColor(textColor)
        }
        
        view.findViewById<TextView>(R.id.edit_cloud_title).apply {
            setOnClickListener {
                bottomSheet.dismiss()
                showEditCloudTitleDialog(activity, medium, callback)
            }
            setTextColor(textColor)
            compoundDrawablesRelative.getOrNull(0)?.mutate()?.setTint(textColor)
        }

        val cloudStatusManager = CloudStatusManager.getInstance(activity)
        val isFavorite = cloudStatusManager.isFavorite(medium.path) == true

        view.findViewById<TextView>(R.id.cloud_favorite_action).apply {
            text = if (isFavorite) activity.getString(R.string.cloud_unfavorite) else activity.getString(R.string.cloud_favorite)
            setOnClickListener {
                bottomSheet.dismiss()
                toggleCloudFavorite(activity, medium, !isFavorite, callback)
            }
            setTextColor(textColor)
            compoundDrawablesRelative.getOrNull(0)?.mutate()?.setTint(textColor)
        }

        view.findViewById<TextView>(R.id.delete_cloud_photo).apply {
            setOnClickListener {
                bottomSheet.dismiss()
                showDeleteCloudPhotoConfirmation(activity, medium, callback)
            }
            setTextColor(textColor)
            compoundDrawablesRelative.getOrNull(0)?.mutate()?.setTint(textColor)
        }
        
        if (supportsBlur) {
            bottomSheet.window?.apply {
                addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 64
                attributes = attributes
            }
        }
        
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun showEditCloudTitleDialog(activity: BaseSimpleActivity, medium: Medium, callback: () -> Unit) {
        val cloudStatusManager = CloudStatusManager.getInstance(activity)
        val isUploading = CloudUploadManager.getInstance(activity).getActiveTask(medium.path) != null

        // If uploading, we might already have a pending title saved locally
        val currentTitle = if (isUploading) {
            cloudStatusManager.consumePendingTitle(medium.path) ?: medium.name
        } else {
            cloudStatusManager.getCloudTitle(medium.path) ?: medium.name
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_edit_cloud_title, null)
        val input = view.findViewById<EditText>(R.id.cloud_title_input)
        val clearBtn = view.findViewById<ImageButton>(R.id.clear_title_btn)
        input.setText(currentTitle)
        // If there was a pending title, putting it back in case user cancels or just wants to see it
        if (isUploading && currentTitle != medium.name) {
            cloudStatusManager.setPendingTitle(medium.path, currentTitle)
        }

        // 清空按钮点击事件：清空编辑框内容
        clearBtn.setOnClickListener {
            input.text?.clear()
        }

        activity.getAlertDialogBuilder()
            .setView(view)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val newTitle = input.text.toString()
                if (newTitle.isNotEmpty()) {
                    val isCurrentlyUploading = CloudUploadManager.getInstance(activity).getActiveTask(medium.path) != null
                    if (isCurrentlyUploading) {
                        cloudStatusManager.setPendingTitle(medium.path, newTitle)
                        activity.toast(R.string.cloud_title_pending_toast)
                        callback()
                    } else {
                        updateCloudTitle(activity, medium, newTitle, callback)
                    }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, R.string.edit_cloud_title) { }
            }
    }

    private fun updateCloudTitle(activity: BaseSimpleActivity, medium: Medium, newTitle: String, callback: () -> Unit) {
        activity.toast(R.string.updating_cloud_titles)
        val cloudStatusManager = CloudStatusManager.getInstance(activity)

        CoroutineScope(Dispatchers.IO).launch {
            val accountManager = CloudAccountManager.getInstance(activity)
            val apiService = CloudApiService(accountManager)
            val result = apiService.updateCloudTitle(medium.path, newTitle)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    activity.toast(R.string.cloud_title_updated)
                    cloudStatusManager.updateTitle(medium.path, newTitle)

                    // 同步标题到 OSS tag
                    syncTitleToOssTag(activity, medium.path, newTitle, callback)
                } else {
                    activity.toast(R.string.cloud_title_update_failed)
                }
            }
        }
    }

    private fun syncTitleToOssTag(activity: BaseSimpleActivity, fullPath: String, title: String, callback: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 需要从数据库或其他方式获取文件的 MD5
                // 这里通过查询云端元数据获取 md5
                val accountManager = CloudAccountManager.getInstance(activity)
                val apiService = CloudApiService(accountManager)

                // 查询元数据获取 md5
                val metadataResult = apiService.getBatchMetadata(listOf(fullPath))
                if (metadataResult.isSuccess) {
                    val md5 = metadataResult.getOrNull()?.results?.get(fullPath)?.md5
                    if (!md5.isNullOrEmpty()) {
                        val ossUploader = CloudUploadManager.getInstance(activity).ossUploader
                        val tagResult = ossUploader.setObjectTag(md5, title)
                        withContext(Dispatchers.Main) {
                            if (tagResult.isFailure) {
                                val errorMsg = tagResult.exceptionOrNull()?.message ?: "未知错误"
                                activity.toast("${activity.getString(R.string.cloud_title_update_failed_tag)}: $errorMsg")
                            } else {
                                callback()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            activity.toast(activity.getString(R.string.cloud_title_update_failed_tag))
                            callback()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        activity.toast(activity.getString(R.string.cloud_title_update_failed_tag))
                        callback()
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudActionsHelper", "sync oss tag error", e)
                withContext(Dispatchers.Main) {
                    activity.toast("${activity.getString(R.string.cloud_title_update_failed_tag)}: ${e.message}")
                    callback()
                }
            }
        }
    }

    private fun showDeleteCloudPhotoConfirmation(activity: BaseSimpleActivity, medium: Medium, callback: () -> Unit) {
        val messageView = android.widget.TextView(activity).apply {
            setText(R.string.delete_cloud_photo_confirm)
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            setTextColor(activity.getProperTextColor())
        }

        activity.getAlertDialogBuilder()
            .setView(messageView)
            .setPositiveButton(org.fossify.commons.R.string.delete) { _, _ ->
                deleteCloudPhoto(activity, medium, callback)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(messageView, this, R.string.delete_cloud_photo) { }
            }
    }

    private fun deleteCloudPhoto(activity: BaseSimpleActivity, medium: Medium, callback: () -> Unit) {
        activity.toast(R.string.deleting_cloud_photo)
        CoroutineScope(Dispatchers.IO).launch {
            val accountManager = CloudAccountManager.getInstance(activity)
            val apiService = CloudApiService(accountManager)
            val result = apiService.deleteCloudPhoto(medium.path)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    activity.toast(R.string.delete_cloud_photo_success)
                    CloudStatusManager.getInstance(activity).updateUploadedStatus(medium.path, false)
                    callback()
                } else {
                    activity.toast(R.string.delete_cloud_photo_failed)
                }
            }
        }
    }

    private fun toggleCloudFavorite(activity: BaseSimpleActivity, medium: Medium, isFavorite: Boolean, callback: () -> Unit) {
        val cloudStatusManager = CloudStatusManager.getInstance(activity)
        
        CoroutineScope(Dispatchers.IO).launch {
            val accountManager = CloudAccountManager.getInstance(activity)
            val apiService = CloudApiService(accountManager)
            val result = apiService.updateFavoriteStatus(medium.path, isFavorite)
            
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    activity.toast(R.string.cloud_favorite_success)
                    cloudStatusManager.updateFavorite(medium.path, isFavorite)
                    callback()
                } else {
                    activity.toast(R.string.cloud_favorite_failed)
                }
            }
        }
    }
}
