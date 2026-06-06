package org.fossify.gallery.helpers

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.gallery.R

object CloudToolbarMenuHelper {
    fun show(
        activity: BaseSimpleActivity,
        anchor: View,
        isLoggedIn: Boolean,
        showFilterItem: Boolean,
        onOpenCloudAccount: () -> Unit,
        onFilterChanged: (Boolean) -> Unit,
        onQrWebLogin: (() -> Unit)? = null
    ) {
        // 使用 getPopupMenuTheme() 获取与当前主题匹配的 popup 主题
        // 支持深色/浅色/系统主题/自定义颜色等所有模式
        val popupTheme = activity.getPopupMenuTheme()
        val popupContext = ContextThemeWrapper(anchor.context, popupTheme)
        val popup = PopupMenu(popupContext, anchor)

        val idCloudConfig = 1
        val idFilter = 2
        val idQrWebLogin = 3

        popup.menu.add(0, idCloudConfig, 0, activity.getString(R.string.cloud_account_config))
        // TODO: 暂时隐藏筛选功能，待后续优化
        // if (showFilterItem) {
        //     popup.menu.add(0, idFilter, 1, activity.getString(R.string.show_only_unuploaded_photos)).apply {
        //         isCheckable = true
        //         isChecked = CloudUploadFilterState.showOnlyUnuploadedPhotos
        //         isEnabled = isLoggedIn
        //     }
        // }
        if (isLoggedIn && onQrWebLogin != null) {
            popup.menu.add(0, idQrWebLogin, 2, activity.getString(R.string.cloud_qr_web_login))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                idCloudConfig -> {
                    onOpenCloudAccount()
                    true
                }
                idFilter -> {
                    val newState = !item.isChecked
                    CloudUploadFilterState.showOnlyUnuploadedPhotos = newState
                    onFilterChanged(newState)
                    true
                }
                idQrWebLogin -> {
                    onQrWebLogin?.invoke()
                    true
                }
                else -> false
            }
        }

        popup.show()
    }
}
