package org.fossify.gallery.activities

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.net.toUri
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getIsPathDirectory
import org.fossify.commons.extensions.getLatestMediaByDateId
import org.fossify.commons.extensions.getLatestMediaId
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleHiddenFolderPasswordProtection
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FAVORITES
import org.fossify.commons.helpers.IS_FROM_GALLERY
import org.fossify.commons.helpers.REQUEST_EDIT_IMAGE
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.databinding.ActivityMediaBinding
import org.fossify.gallery.dialogs.ChangeGroupingDialog
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.dialogs.ChangeViewTypeDialog
import org.fossify.gallery.dialogs.FilterMediaDialog
import org.fossify.gallery.dialogs.GrantAllFilesDialog
import org.fossify.gallery.dialogs.CloudAccountDialog
import org.fossify.gallery.dialogs.CloudAccountDialog.Companion.parseQrScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.gallery.dialogs.QrWebLoginDialog
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.emptyAndDisableTheRecycleBin
import org.fossify.gallery.extensions.emptyTheRecycleBin
import org.fossify.gallery.extensions.favoritesDB
import org.fossify.gallery.extensions.getCachedMedia
import org.fossify.gallery.extensions.getHumanizedFilename
import org.fossify.gallery.extensions.isDownloadsFolder
import org.fossify.gallery.extensions.launchAbout
import org.fossify.gallery.extensions.launchCamera
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.openPath
import org.fossify.gallery.extensions.openRecycleBin
import org.fossify.gallery.extensions.restoreRecycleBinPaths
import org.fossify.gallery.extensions.showRecycleBinEmptyingDialog
import org.fossify.gallery.extensions.showRestoreConfirmationDialog
import org.fossify.gallery.extensions.tryDeleteFileDirItem
import org.fossify.gallery.extensions.updateWidgets
import org.fossify.gallery.helpers.CloudToolbarMenuHelper
import org.fossify.gallery.helpers.CloudUploadFilterState
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.GET_ANY_INTENT
import org.fossify.gallery.helpers.GET_IMAGE_INTENT
import org.fossify.gallery.helpers.GET_VIDEO_INTENT
import org.fossify.gallery.helpers.GridSpacingItemDecoration
import org.fossify.gallery.helpers.IS_IN_RECYCLE_BIN
import org.fossify.gallery.helpers.MAX_COLUMN_COUNT
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.PICKED_PATHS
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.SHOW_FAVORITES
import org.fossify.gallery.helpers.SHOW_RECYCLE_BIN
import org.fossify.gallery.helpers.SHOW_TEMP_HIDDEN_DURATION
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.SLIDESHOW_START_ON_ENTER
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_CLOUD_ONLY
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_HIDE_ALL
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_SHOW_MERGED
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.models.ThumbnailSection
import org.fossify.gallery.cloud.CloudAccountManager
import org.fossify.gallery.cloud.CloudStatusManager
import java.io.File
import java.io.IOException

class MediaActivity : SimpleActivity(), MediaOperationsListener, CloudAccountManager.OnLoginStateChangeListener {
    override var isSearchBarEnabled = true

    private val LAST_MEDIA_CHECK_PERIOD = 20000L

    private var mPath = ""
    private var mIsGetImageIntent = false
    private var mIsGetVideoIntent = false
    private var mIsGetAnyIntent = false
    private var mIsGettingMedia = false
    private var mAllowPickingMultiple = false
    private var mShowAll = false
    private var mLoadedInitialPhotos = false
    private var mShowLoadingIndicator = true
    private var mWasFullscreenViewOpen = false
    private var mLastSearchedText = ""
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L
    private var mLastMediaHandler = Handler()
    private var mTempShowHiddenHandler = Handler()
    private var mCurrAsyncTask: GetMediaAsynctask? = null
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredShowFileTypes = true
    private var mStoredRoundedCorners = false
    private var mStoredMarkFavoriteItems = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredThumbnailSpacing = 0

    private val binding by viewBinding(ActivityMediaBinding::inflate)

    companion object {
        var mMedia = ArrayList<ThumbnailItem>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // 注册登录状态变化监听器
        CloudAccountManager.getInstance(this).addListener(this)

        intent.apply {
            mIsGetImageIntent = getBooleanExtra(GET_IMAGE_INTENT, false)
            mIsGetVideoIntent = getBooleanExtra(GET_VIDEO_INTENT, false)
            mIsGetAnyIntent = getBooleanExtra(GET_ANY_INTENT, false)
            mAllowPickingMultiple = getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }

        binding.mediaRefreshLayout.setOnRefreshListener { getMedia() }
        try {
            mPath = intent.getStringExtra(DIRECTORY) ?: ""
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
            return
        }

        setupOptionsMenu()
        refreshMenuItems()
        storeStateVariables()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.mediaMenu),
            padBottomImeAndSystem = listOf(binding.mediaGrid)
        )

        if (mShowAll) {
            registerFileUpdateListener()
        }

        binding.mediaEmptyTextPlaceholder2.setOnClickListener {
            showFilterMediaDialog()
        }

        updateWidgets()
    }

    override fun onStart() {
        super.onStart()
        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("MediaActivity", "onResume 开始")
        updateMenuColors()
        if (mStoredAnimateGifs != config.animateGifs) {
            getMediaAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getMediaAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != config.scrollHorizontally) {
            mLoadedInitialPhotos = false
            binding.mediaGrid.adapter = null
            getMedia()
        }

        if (mStoredShowFileTypes != config.showThumbnailFileTypes) {
            getMediaAdapter()?.updateShowFileTypes(config.showThumbnailFileTypes)
        }

        if (mStoredTextColor != getProperTextColor()) {
            getMediaAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getMediaAdapter()?.updatePrimaryColor()
        }

        if (
            mStoredThumbnailSpacing != config.thumbnailSpacing
            || mStoredRoundedCorners != config.fileRoundedCorners
            || mStoredMarkFavoriteItems != config.markFavoriteItems
        ) {
            binding.mediaGrid.adapter = null
            setupAdapter()
        }

        refreshMenuItems()

        binding.mediaFastscroller.updateColors(primaryColor)
        binding.mediaFastscroller.handleWidth = (36 * resources.displayMetrics.density).toInt()
        binding.mediaFastscroller.handleHeight = (56 * resources.displayMetrics.density).toInt()
        binding.mediaRefreshLayout.isEnabled = config.enablePullToRefresh
        getMediaAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.loadingIndicator.setIndicatorColor(getProperPrimaryColor())
        binding.mediaEmptyTextPlaceholder.setTextColor(getProperTextColor())
        binding.mediaEmptyTextPlaceholder2.setTextColor(getProperPrimaryColor())
        binding.mediaEmptyTextPlaceholder2.bringToFront()

        // do not refresh Random sorted files after opening a fullscreen image and going Back
        val isRandomSorting = config.getFolderSorting(mPath) and SORT_BY_RANDOM != 0
        android.util.Log.d("MediaActivity", "onResume: mMedia.size=${mMedia.size}, isRandomSorting=$isRandomSorting, mWasFullscreenViewOpen=$mWasFullscreenViewOpen")
        if (mMedia.isEmpty() || !isRandomSorting || (isRandomSorting && !mWasFullscreenViewOpen)) {
            android.util.Log.d("MediaActivity", "onResume: 调用 tryLoadGallery()")
            if (shouldSkipAuthentication()) {
                tryLoadGallery()
            } else {
                handleLockedFolderOpening(mPath) { success ->
                    if (success) {
                        tryLoadGallery()
                    } else {
                        finish()
                    }
                }
            }
        } else {
            android.util.Log.d("MediaActivity", "onResume: 跳过 tryLoadGallery()")
        }
    }

    override fun onPause() {
        super.onPause()
        mIsGettingMedia = false
        binding.mediaRefreshLayout.isRefreshing = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)

        if (!mMedia.isEmpty()) {
            mCurrAsyncTask?.stopFetching()
        }
    }

    override fun onStop() {
        super.onStop()

        if (config.temporarilyShowHidden || config.tempSkipDeleteConfirmation) {
            mTempShowHiddenHandler.postDelayed({
                config.temporarilyShowHidden = false
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, SHOW_TEMP_HIDDEN_DURATION)
        } else {
            mTempShowHiddenHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除登录状态变化监听器
        CloudAccountManager.getInstance(this).removeListener(this)
        getMediaAdapter()?.cleanup()
        if (config.showAll && !isChangingConfigurations) {
            config.temporarilyShowHidden = false
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            unregisterFileUpdateListener()
            GalleryDatabase.destroyInstance()
        }

        mTempShowHiddenHandler.removeCallbacksAndMessages(null)
    }

    override fun onLoginStateChanged(isLoggedIn: Boolean) {
        // 登录状态变化时，刷新菜单图标
        runOnUiThread {
            refreshMenuItems()
            getMediaAdapter()?.notifyDataSetChanged()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mediaMenu.isSearchOpen) {
            binding.mediaMenu.closeSearch()
            true
        } else {
            if (config.showAll) {
                appLockManager.lock()
            }

            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            if (resultCode == RESULT_OK && resultData != null) {
                mMedia.clear()
                refreshItems()
            }
        } else {
            val qrResult = parseQrScanResult(requestCode, resultCode, resultData)
            if (qrResult != null) {
                val sessionId = QrWebLoginDialog.parseSessionId(qrResult)
                if (sessionId != null) {
                    // Web QR login: confirm session with server
                    val accountManager = org.fossify.gallery.cloud.CloudAccountManager.getInstance(this)
                    val apiService = org.fossify.gallery.cloud.CloudApiService(accountManager)
                    CoroutineScope(Dispatchers.Main).launch {
                        val result = apiService.confirmQrSession(sessionId)
                        if (result.isSuccess) {
                            android.widget.Toast.makeText(this@MediaActivity, R.string.cloud_qr_web_login_success, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val errorMsg = result.exceptionOrNull()?.message ?: "unknown error"
                            android.widget.Toast.makeText(this@MediaActivity, "${getString(R.string.cloud_qr_web_login_failed)}: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    CloudAccountDialog.showWithConfig(this, qrResult) {
                        getMediaAdapter()?.notifyDataSetChanged()
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        val isDefaultFolder = !config.defaultFolder.isEmpty()
                && File(config.defaultFolder).compareTo(File(mPath)) == 0

        binding.mediaMenu.requireToolbar().menu.apply {
            findItem(R.id.group).isVisible = !config.scrollHorizontally

            findItem(R.id.empty_recycle_bin).isVisible = mPath == RECYCLE_BIN
            findItem(R.id.empty_disable_recycle_bin).isVisible = mPath == RECYCLE_BIN
            findItem(R.id.restore_all_files).isVisible = mPath == RECYCLE_BIN

            findItem(R.id.folder_view).isVisible = mShowAll
            findItem(R.id.open_camera).isVisible = mShowAll
            findItem(R.id.about).isVisible = mShowAll
            findItem(R.id.create_new_folder).isVisible =
                !mShowAll && mPath != RECYCLE_BIN && mPath != FAVORITES
            findItem(R.id.open_recycle_bin).isVisible = config.useRecycleBin && mPath != RECYCLE_BIN

            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden
            findItem(R.id.stop_showing_hidden).isVisible =
                (!isRPlus() || isExternalStorageManager()) && config.temporarilyShowHidden

            findItem(R.id.set_as_default_folder).isVisible = !isDefaultFolder
            findItem(R.id.unset_as_default_folder).isVisible = isDefaultFolder

            val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
            findItem(R.id.column_count).isVisible = viewType == VIEW_TYPE_GRID
            findItem(R.id.toggle_filename).isVisible = viewType == VIEW_TYPE_GRID

            val isLoggedIn = org.fossify.gallery.cloud.CloudAccountManager.getInstance(this@MediaActivity).isLoggedIn
            val cloudIcon = if (isLoggedIn) {
                R.drawable.ic_cloud_done_vector
            } else {
                R.drawable.ic_cloud_upload_vector
            }
            findItem(R.id.cloud_account)?.apply {
                setIcon(cloudIcon)
                // 清除系统 tint，让 drawable 自带颜色生效（绿色/灰色）
                iconTintList = null
            }
        }
    }

    private fun setupOptionsMenu() {
        binding.mediaMenu.requireToolbar().inflateMenu(R.menu.menu_media)
        binding.mediaMenu.toggleHideOnScroll(!config.scrollHorizontally)
        binding.mediaMenu.setupMenu()

        binding.mediaMenu.onSearchTextChangedListener = { text ->
            mLastSearchedText = text
            searchQueryChanged(text)
            binding.mediaRefreshLayout.isEnabled = text.isEmpty() && config.enablePullToRefresh
        }

        binding.mediaMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.filter -> showFilterMediaDialog()
                R.id.empty_recycle_bin -> emptyRecycleBin()
                R.id.empty_disable_recycle_bin -> emptyAndDisableRecycleBin()
                R.id.restore_all_files -> restoreAllFiles()
                R.id.toggle_filename -> toggleFilenameVisibility()
                R.id.open_camera -> launchCamera()
                R.id.folder_view -> switchToFolderView()
                R.id.change_view_type -> changeViewType()
                R.id.group -> showGroupByDialog()
                R.id.create_new_folder -> createNewFolder()
                R.id.open_recycle_bin -> openRecycleBin()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.column_count -> changeColumnCount()
                R.id.set_as_default_folder -> setAsDefaultFolder()
                R.id.unset_as_default_folder -> unsetAsDefaultFolder()
                R.id.slideshow -> startSlideshow()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                R.id.cloud_account -> showCloudAccountMenu()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun showCloudAccountMenu() {
        val anchor = binding.mediaMenu.requireToolbar().findViewById<android.view.View>(R.id.cloud_account)
            ?: binding.mediaMenu.requireToolbar()
        CloudToolbarMenuHelper.show(
            activity = this,
            anchor = anchor,
            isLoggedIn = org.fossify.gallery.cloud.CloudAccountManager.getInstance(this).isLoggedIn,
            showFilterItem = true,
            onOpenCloudAccount = { showCloudAccountDialog() },
            onFilterChanged = {
                mLoadedInitialPhotos = false
                mMedia.clear()
                getMedia()
                refreshMenuItems()
            },
            onQrWebLogin = {
                startQrWebLogin()
            }
        )
    }

    private fun startQrWebLogin() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                org.fossify.gallery.dialogs.CloudAccountDialog.CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            val integrator = com.google.zxing.integration.android.IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
            integrator.setPrompt("")
            integrator.setBeepEnabled(false)
            integrator.setOrientationLocked(true)
            integrator.initiateScan()
        }
    }

    private fun startSlideshow() {
        if (mMedia.isNotEmpty()) {
            hideKeyboard()
            Intent(this, ViewPagerActivity::class.java).apply {
                val item = mMedia.firstOrNull { it is Medium } as? Medium ?: return
                putExtra(SKIP_AUTHENTICATION, shouldSkipAuthentication())
                putExtra(PATH, item.path)
                putExtra(SHOW_ALL, mShowAll)
                putExtra(SLIDESHOW_START_ON_ENTER, true)
                startActivity(this)
            }
        }
    }

    private fun updateMenuColors() {
        binding.mediaMenu.updateColors()
    }

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredShowFileTypes = showThumbnailFileTypes
            mStoredMarkFavoriteItems = markFavoriteItems
            mStoredThumbnailSpacing = thumbnailSpacing
            mStoredRoundedCorners = fileRoundedCorners
            mShowAll = showAll && mPath != RECYCLE_BIN
        }
    }

    private fun searchQueryChanged(text: String) {
        ensureBackgroundThread {
            try {
                // 1. 本地过滤
                val filtered = mMedia.filter { it is Medium && it.name.contains(text, true) } as ArrayList
                
                // 2. 云端搜索 (只在有搜索文本时进行)
                if (text.isNotEmpty()) {
                    try {
                        val apiService = org.fossify.gallery.cloud.CloudApiService(
                            org.fossify.gallery.cloud.CloudAccountManager.getInstance(applicationContext)
                        )
                        
                        // 在当前线程阻塞等待结果
                        val cloudResult = kotlinx.coroutines.runBlocking {
                             apiService.searchCloudMetadata(text)
                        }
                        
                        if (cloudResult.isSuccess) {
                            val cloudPaths = cloudResult.getOrDefault(emptyList()).toSet()
                            if (cloudPaths.isNotEmpty()) {
                                // 找出那些路径在 cloudPaths 中的媒体文件
                                val cloudMatches = mMedia.filter { 
                                    it is Medium && cloudPaths.contains(it.path) 
                                }
                                filtered.addAll(cloudMatches as Collection<ThumbnailItem>)
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }

                // 3. 去重
                val distinctFiltered = filtered.distinctBy { (it as Medium).path } as ArrayList<ThumbnailItem>
                
                // 4. 排序：精确匹配优先
                distinctFiltered.sortBy { it is Medium && !it.name.startsWith(text, true) }
                
                val grouped = MediaFetcher(applicationContext).groupMedia(
                    media = distinctFiltered as ArrayList<Medium>, path = mPath
                )
                
                runOnUiThread {
                    // Prevent race condition: ensure we are displaying results for the latest query
                    if (text == mLastSearchedText) {
                        if (grouped.isEmpty()) {
                            binding.mediaEmptyTextPlaceholder.text =
                                getString(org.fossify.commons.R.string.no_items_found)
                            binding.mediaEmptyTextPlaceholder.beVisible()
                            binding.mediaFastscroller.beGone()
                        } else {
                            binding.mediaEmptyTextPlaceholder.beGone()
                            binding.mediaFastscroller.beVisible()
                        }
    
                        handleGridSpacing(grouped)
                        getMediaAdapter()?.updateMedia(grouped)
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun tryLoadGallery() {
        requestMediaPermissions {
            val dirName = when (mPath) {
                FAVORITES -> getString(org.fossify.commons.R.string.favorites)
                RECYCLE_BIN -> getString(org.fossify.commons.R.string.recycle_bin)
                config.OTGPath -> getString(org.fossify.commons.R.string.usb)
                else -> getHumanizedFilename(mPath)
            }

            val searchHint = if (mShowAll) {
                getString(org.fossify.commons.R.string.search_files)
            } else {
                getString(org.fossify.commons.R.string.search_in_placeholder, dirName)
            }

            binding.mediaMenu.updateHintText(searchHint)
            if (!mShowAll) {
                binding.mediaMenu.toggleForceArrowBackIcon(true)
                binding.mediaMenu.onNavigateBackClickListener = {
                    performDefaultBack()
                }
            }

            if (mShowLoadingIndicator) {
                binding.loadingIndicator.show()
                mShowLoadingIndicator = false
            }

            getMedia()
            setupLayoutManager()
        }
    }

    private fun getMediaAdapter() = binding.mediaGrid.adapter as? MediaAdapter

    private fun setupAdapter() {
        if (!mShowAll && isDirEmpty()) {
            return
        }

        val currAdapter = binding.mediaGrid.adapter
        if (currAdapter == null) {
            initZoomListener()
            MediaAdapter(
                activity = this,
                media = mMedia.clone() as ArrayList<ThumbnailItem>,
                listener = this,
                isAGetIntent = mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent,
                allowMultiplePicks = mAllowPickingMultiple,
                path = mPath,
                recyclerView = binding.mediaGrid
            ) {
                if (it is Medium && !isFinishing) {
                    itemClicked(it.path)
                }
            }.apply {
                setupZoomListener(mZoomListener)
                binding.mediaGrid.adapter = this
            }

            val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
            if (viewType == VIEW_TYPE_LIST && areSystemAnimationsEnabled) {
                binding.mediaGrid.scheduleLayoutAnimation()
            }

            setupLayoutManager()
            handleGridSpacing()
        } else if (mLastSearchedText.isEmpty()) {
            (currAdapter as MediaAdapter).updateMedia(mMedia, mWasFullscreenViewOpen)
            mWasFullscreenViewOpen = false
            handleGridSpacing()
        } else {
            searchQueryChanged(mLastSearchedText)
        }

        setupScrollDirection()
    }

    private fun setupScrollDirection() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        val scrollHorizontally = config.scrollHorizontally && viewType == VIEW_TYPE_GRID
        binding.mediaFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed || config.getFolderSorting(mPath) and SORT_BY_RANDOM != 0) {
            return
        }

        mLastMediaHandler.removeCallbacksAndMessages(null)
        mLastMediaHandler.postDelayed({
            android.util.Log.d("MediaActivity", "轮询检查触发")
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                android.util.Log.d("MediaActivity", "mediaId=$mediaId, mediaDateId=$mediaDateId, mLatestMediaId=$mLatestMediaId, mLatestMediaDateId=$mLatestMediaDateId")
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    android.util.Log.d("MediaActivity", "开始刷新")
                    runOnUiThread {
                        getMedia()
                    }
                } else {
                    android.util.Log.d("MediaActivity", "媒体无变化，继续轮询")
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, false, true, mPath) {
            mLoadedInitialPhotos = false
            binding.mediaGrid.adapter = null
            getMedia()
        }
    }

    private fun showFilterMediaDialog() {
        FilterMediaDialog(this) {
            mLoadedInitialPhotos = false
            binding.mediaRefreshLayout.isRefreshing = true
            binding.mediaGrid.adapter = null
            getMedia()
        }
    }

    private fun emptyRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyTheRecycleBin {
                finish()
            }
        }
    }

    private fun emptyAndDisableRecycleBin() {
        showRecycleBinEmptyingDialog {
            emptyAndDisableTheRecycleBin {
                finish()
            }
        }
    }

    private fun restoreAllFiles() {
        val paths = mMedia.filter { it is Medium }.map { (it as Medium).path } as ArrayList<String>
        showRestoreConfirmationDialog(paths.size) {
            restoreRecycleBinPaths(paths) {
                ensureBackgroundThread {
                    directoryDB.deleteDirPath(RECYCLE_BIN)
                }
                finish()
            }
        }
    }

    private fun toggleFilenameVisibility() {
        val nextMode = when (config.titleDisplayMode) {
            TITLE_DISPLAY_MODE_HIDE_ALL -> TITLE_DISPLAY_MODE_SHOW_MERGED
            TITLE_DISPLAY_MODE_SHOW_MERGED -> TITLE_DISPLAY_MODE_CLOUD_ONLY
            TITLE_DISPLAY_MODE_CLOUD_ONLY -> TITLE_DISPLAY_MODE_HIDE_ALL
            else -> TITLE_DISPLAY_MODE_HIDE_ALL
        }
        config.titleDisplayMode = nextMode
        getMediaAdapter()?.updateTitleDisplayMode(nextMode)
    }

    private fun switchToFolderView() {
        hideKeyboard()
        config.showAll = false
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, false, mPath) {
            refreshMenuItems()
            setupLayoutManager()
            binding.mediaGrid.adapter = null
            setupAdapter()
        }
    }

    private fun showGroupByDialog() {
        ChangeGroupingDialog(this, mPath) {
            mLoadedInitialPhotos = false
            binding.mediaGrid.adapter = null
            getMedia()
        }
    }

    private fun deleteDirectoryIfEmpty() {
        if (config.deleteEmptyFolders) {
            val fileDirItem = FileDirItem(mPath, mPath.getFilenameFromPath(), true)
            if (!fileDirItem.isDownloadsFolder() && fileDirItem.isDirectory) {
                ensureBackgroundThread {
                    if (fileDirItem.getProperFileCount(this, true) == 0) {
                        tryDeleteFileDirItem(fileDirItem, true, true)
                    }
                }
            }
        }
    }

    private fun getMedia() {
        if (mIsGettingMedia) {
            return
        }

        android.util.Log.d("MediaActivity", "getMedia 调用，mPath=$mPath, mLoadedInitialPhotos=$mLoadedInitialPhotos")
        mIsGettingMedia = true
        if (mLoadedInitialPhotos) {
            android.util.Log.d("MediaActivity", "getMedia: 调用 startAsyncTask()")
            startAsyncTask()
        } else {
            getCachedMedia(
                mPath,
                mIsGetVideoIntent && !mIsGetImageIntent,
                mIsGetImageIntent && !mIsGetVideoIntent,
                CloudUploadFilterState.showOnlyUnuploadedPhotos
            ) { cachedMedia ->
                android.util.Log.d("MediaActivity", "getCachedMedia 回调，cachedMedia.size=${cachedMedia.size}")
                if (cachedMedia.isEmpty()) {
                    runOnUiThread {
                        binding.mediaRefreshLayout.isRefreshing = true
                    }
                } else {
                    gotMedia(cachedMedia, true)
                }
                android.util.Log.d("MediaActivity", "getMedia: 调用 startAsyncTask()")
                startAsyncTask()
            }
        }

        mLoadedInitialPhotos = true
    }

    private fun startAsyncTask() {
        mCurrAsyncTask?.stopFetching()
        mCurrAsyncTask = GetMediaAsynctask(
            context = applicationContext,
            mPath = mPath,
            isPickImage = mIsGetImageIntent && !mIsGetVideoIntent,
            isPickVideo = mIsGetVideoIntent && !mIsGetImageIntent,
            showAll = mShowAll,
            filterUploadedPhotos = CloudUploadFilterState.showOnlyUnuploadedPhotos
        ) { newMedia, isFinal ->
            android.util.Log.d("MediaActivity", "startAsyncTask 回调，newMedia.size=${newMedia.size}, isFinal=$isFinal")
            ensureBackgroundThread {
                val oldMedia = mMedia.clone() as ArrayList<ThumbnailItem>
                try {
                    val newPathsSet = newMedia.mapNotNull { it as? Medium }.map { it.path.lowercase() }.toHashSet()

                    // Find items that were in the old display but not found by the async task
                    val missingMedia = oldMedia
                        .mapNotNull { it as? Medium }
                        .filter { !newPathsSet.contains(it.path.lowercase()) }

                    // Single partition to avoid TOCTOU race (each file checked only once)
                    val (stillExisting, trulyDeleted) = missingMedia.partition { getDoesFilePathExist(it.path) }

                    // For FAVORITES, "missing from async result but still on disk" means
                    // the file was unfavorited — respect that intent, don't merge back.
                    // For normal folders, merge back files the scanner missed.
                    val finalMedia = if (mPath != FAVORITES && stillExisting.isNotEmpty()) {
                        val allMedia = ArrayList<Medium>()
                        allMedia.addAll(newMedia.filterIsInstance<Medium>())
                        allMedia.addAll(stillExisting)
                        val pathToUse = if (mShowAll) SHOW_ALL else mPath
                        MediaFetcher(applicationContext).groupMedia(allMedia, pathToUse)
                    } else {
                        newMedia
                    }

                    android.util.Log.d("MediaActivity", "startAsyncTask: finalMedia.size=${finalMedia.size}, stillExisting.size=${stillExisting.size}, trulyDeleted.size=${trulyDeleted.size}")

                    gotMedia(finalMedia, false)

                    // Clean up DB entries
                    if (mPath == FAVORITES) {
                        // For FAVORITES: all missing items should be unfavorited,
                        // whether they were deleted or intentionally removed from favorites
                        missingMedia.forEach {
                            favoritesDB.deleteFavoritePath(it.path)
                            mediaDB.updateFavorite(it.path, false)
                        }
                    } else {
                        // For normal folders: only delete DB entries for truly deleted files
                        trulyDeleted.forEach {
                            android.util.Log.d("MediaActivity", "删除 DB 条目：${it.path}")
                            mediaDB.deleteMediumPath(it.path)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaActivity", "startAsyncTask 异常", e)
                }
            }
        }

        mCurrAsyncTask!!.execute()
    }

    private fun isDirEmpty(): Boolean {
        return if (mMedia.isEmpty() && config.filterMedia > 0) {
            if (mPath != FAVORITES && mPath != RECYCLE_BIN) {
                deleteDirectoryIfEmpty()
                deleteDBDirectory()
            }

            if (mPath == FAVORITES) {
                ensureBackgroundThread {
                    directoryDB.deleteDirPath(FAVORITES)
                }
            }

            if (mPath == RECYCLE_BIN) {
                binding.mediaEmptyTextPlaceholder.setText(org.fossify.commons.R.string.no_items_found)
                binding.mediaEmptyTextPlaceholder.beVisible()
                binding.mediaEmptyTextPlaceholder2.beGone()
            } else {
                finish()
            }

            true
        } else {
            false
        }
    }

    private fun deleteDBDirectory() {
        ensureBackgroundThread {
            try {
                directoryDB.deleteDirPath(mPath)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun createNewFolder() {
        CreateNewFolderDialog(this, mPath) {
            config.tempFolderPath = it
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                handleHiddenFolderPasswordProtection {
                    toggleTemporarilyShowHidden(true)
                }
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        mLoadedInitialPhotos = false
        config.temporarilyShowHidden = show
        getMedia()
        refreshMenuItems()
    }

    private fun setupLayoutManager() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        layoutManager.spanCount = config.mediaColumnCnt
        val adapter = getMediaAdapter()
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter?.isASectionTitle(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.mediaRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun handleGridSpacing(media: ArrayList<ThumbnailItem> = mMedia) {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            val spanCount = config.mediaColumnCnt
            val spacing = config.thumbnailSpacing
            val useGridPosition = media.firstOrNull() is ThumbnailSection

            var currentGridDecoration: GridSpacingItemDecoration? = null
            if (binding.mediaGrid.itemDecorationCount > 0) {
                currentGridDecoration =
                    binding.mediaGrid.getItemDecorationAt(0) as GridSpacingItemDecoration
                currentGridDecoration.items = media
            }

            val newGridDecoration = GridSpacingItemDecoration(
                spanCount = spanCount,
                spacing = spacing,
                isScrollingHorizontally = config.scrollHorizontally,
                addSideSpacing = config.fileRoundedCorners,
                items = media,
                useGridPosition = useGridPosition
            )
            if (currentGridDecoration.toString() != newGridDecoration.toString()) {
                if (currentGridDecoration != null) {
                    binding.mediaGrid.removeItemDecoration(currentGridDecoration)
                }
                binding.mediaGrid.addItemDecoration(newGridDecoration)
            }
        }
    }

    private fun initZoomListener() {
        val viewType = config.getFolderViewType(if (mShowAll) SHOW_ALL else mPath)
        if (viewType == VIEW_TYPE_GRID) {
            val layoutManager = binding.mediaGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getMediaAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getMediaAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            mZoomListener = null
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        org.fossify.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount = (binding.mediaGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.mediaColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.mediaColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.mediaColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.mediaGrid.layoutManager as MyGridLayoutManager).spanCount = config.mediaColumnCnt
        handleGridSpacing()
        refreshMenuItems()
        getMediaAdapter()?.apply {
            notifyItemRangeChanged(0, media.size)
        }
    }

    private fun isSetWallpaperIntent() = intent.getBooleanExtra(SET_WALLPAPER_INTENT, false)

    private fun itemClicked(path: String) {
        hideKeyboard()
        if (isSetWallpaperIntent()) {
            toast(R.string.setting_wallpaper)

            val wantedWidth = wallpaperDesiredMinimumWidth
            val wantedHeight = wallpaperDesiredMinimumHeight
            val ratio = wantedWidth.toFloat() / wantedHeight

            val options = RequestOptions()
                .override((wantedWidth * ratio).toInt(), wantedHeight)
                .fitCenter()

            Glide.with(this)
                .asBitmap()
                .load(File(path))
                .apply(options)
                .into(object : SimpleTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        try {
                            WallpaperManager.getInstance(applicationContext).setBitmap(resource)
                            setResult(RESULT_OK)
                        } catch (ignored: IOException) {
                        }

                        finish()
                    }
                })
        } else if (mIsGetImageIntent || mIsGetVideoIntent || mIsGetAnyIntent) {
            Intent().apply {
                data = path.toUri()
                setResult(RESULT_OK, this)
            }
            finish()
        } else {
            mWasFullscreenViewOpen = true
            val isVideo = path.isVideoFast()
            if (isVideo) {
                val extras = HashMap<String, Boolean>()
                extras[SHOW_FAVORITES] = mPath == FAVORITES
                if (path.startsWith(recycleBinPath)) {
                    extras[IS_IN_RECYCLE_BIN] = true
                }

                if (shouldSkipAuthentication()) {
                    extras[SKIP_AUTHENTICATION] = true
                }
                openPath(path, false, extras)
            } else {
                Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, shouldSkipAuthentication())
                    putExtra(PATH, path)
                    putExtra(SHOW_ALL, mShowAll)
                    putExtra(SHOW_FAVORITES, mPath == FAVORITES)
                    putExtra(SHOW_RECYCLE_BIN, mPath == RECYCLE_BIN)
                    putExtra(IS_FROM_GALLERY, true)
                    startActivity(this)
                }
            }
        }
    }

    private fun gotMedia(media: ArrayList<ThumbnailItem>, isFromCache: Boolean) {
        mIsGettingMedia = false
        android.util.Log.d("MediaActivity", "gotMedia 调用，isFromCache=$isFromCache")
        checkLastMediaChanged()
        mMedia = media

        // 按小写化路径去重：Android emulated storage 大小写不敏感，
        // 同一物理文件可能被 MediaStore 返回不同大小写的路径（如 WeiXin vs weixin）
        val distinctMedia = ArrayList<ThumbnailItem>()
        val seenPaths = mutableSetOf<String>()
        for (item in media) {
            if (item is Medium) {
                val lowerPath = item.path.lowercase()
                if (!seenPaths.contains(lowerPath)) {
                    seenPaths.add(lowerPath)
                    distinctMedia.add(item)
                }
            } else {
                distinctMedia.add(item)
            }
        }
        mMedia = distinctMedia

        val filteredMedia = distinctMedia

        // 打印本次显示的图片信息
        val mediumList = filteredMedia
        val removedCount = media.size - filteredMedia.size
        if (removedCount > 0) {
            android.util.Log.d("MediaActivity", "去重移除 ${removedCount} 条重复记录")
        }
        android.util.Log.d("MediaActivity", "========== 媒体加载信息 ==========")
        android.util.Log.d("MediaActivity", "本次显示图片数量：${mediumList.size}")

        // 检测重复路径（去重后应无重复）
        val pathCount = mediumList.filterIsInstance<Medium>().groupBy { it.path.lowercase() }
            .filter { it.value.size > 1 }
            .mapValues { it.value.size }
        if (pathCount.isNotEmpty()) {
            android.util.Log.e("MediaActivity", "⚠️ 去重后仍有 ${pathCount.size} 个重复路径：")
            pathCount.forEach { (path, count) ->
                android.util.Log.e("MediaActivity", "  [重复 $count 次] $path")
            }
        }

        android.util.Log.d("MediaActivity", "前 15 张图片信息:")
        mediumList.filterIsInstance<Medium>().take(15).forEachIndexed { index, medium ->
            android.util.Log.d("MediaActivity", "  [$index] ${medium.name} | path: ${medium.path} | size: ${medium.size} | videoDuration: ${medium.videoDuration}")
        }
        android.util.Log.d("MediaActivity", "====================================")

        runOnUiThread {
            binding.loadingIndicator.hide()
            binding.mediaRefreshLayout.isRefreshing = false
            binding.mediaEmptyTextPlaceholder.beVisibleIf(filteredMedia.isEmpty() && !isFromCache)
            binding.mediaEmptyTextPlaceholder2.beVisibleIf(filteredMedia.isEmpty() && !isFromCache)

            if (binding.mediaEmptyTextPlaceholder.isVisible()) {
                binding.mediaEmptyTextPlaceholder.text = getString(R.string.no_media_with_filters)
            }
            binding.mediaFastscroller.beVisibleIf(binding.mediaEmptyTextPlaceholder.isGone())
            if (mPath != FAVORITES && mPath != RECYCLE_BIN) {
                mMedia = ArrayList(filteredMedia)
            }
            setupAdapter()
        }

        mLatestMediaId = getLatestMediaId()
        mLatestMediaDateId = getLatestMediaByDateId()
        if (!isFromCache) {
            val mediaToInsert = mMedia
                .filter { it is Medium && it.deletedTS == 0L }.map { it as Medium }
            Thread {
                try {
                    mediaDB.insertAll(mediaToInsert)
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        val filtered = fileDirItems
            .filter { !getIsPathDirectory(it.path) && it.path.isMediaFile() } as ArrayList
        if (filtered.isEmpty()) {
            return
        }

        if (
            config.useRecycleBin
            && !skipRecycleBin
            && !filtered.first().path.startsWith(recycleBinPath)
        ) {
            val movingItems = resources.getQuantityString(
                org.fossify.commons.R.plurals.moving_items_into_bin,
                filtered.size,
                filtered.size
            )
            toast(movingItems)

            movePathsInRecycleBin(filtered.map { it.path } as ArrayList<String>) {
                if (it) {
                    deleteFilteredFiles(filtered)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            val deletingItems = resources.getQuantityString(
                org.fossify.commons.R.plurals.deleting_items,
                filtered.size,
                filtered.size
            )
            toast(deletingItems)
            deleteFilteredFiles(filtered)
        }
    }

    private fun shouldSkipAuthentication(): Boolean {
        return intent.getBooleanExtra(SKIP_AUTHENTICATION, false)
    }

    private fun deleteFilteredFiles(filtered: ArrayList<FileDirItem>) {
        deleteFiles(filtered) {
            if (!it) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@deleteFiles
            }

            mMedia.removeAll { filtered.map { it.path }.contains((it as? Medium)?.path) }

            ensureBackgroundThread {
                val useRecycleBin = config.useRecycleBin
                filtered.forEach {
                    if (it.path.startsWith(recycleBinPath) || !useRecycleBin) {
                        deleteDBPath(it.path)
                    }
                }
            }

            if (mMedia.isEmpty()) {
                deleteDirectoryIfEmpty()
                deleteDBDirectory()
                finish()
            }
        }
    }

    override fun refreshItems() {
        getMedia()
    }

    override fun selectedPaths(paths: ArrayList<String>) {
        Intent().apply {
            putExtra(PICKED_PATHS, paths)
            setResult(RESULT_OK, this)
        }
        finish()
    }

    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) {
        var currentGridPosition = 0
        media.forEach {
            if (it is Medium) {
                it.gridPosition = currentGridPosition++
            } else if (it is ThumbnailSection) {
                currentGridPosition = 0
            }
        }

        if (binding.mediaGrid.itemDecorationCount > 0) {
            val currentGridDecoration =
                binding.mediaGrid.getItemDecorationAt(0) as GridSpacingItemDecoration
            currentGridDecoration.items = media
        }
    }

    private fun setAsDefaultFolder() {
        config.defaultFolder = mPath
        refreshMenuItems()
    }

    private fun unsetAsDefaultFolder() {
        config.defaultFolder = ""
        refreshMenuItems()
    }

    private fun showCloudAccountDialog() {
        CloudAccountDialog(this) {
            getMediaAdapter()?.notifyDataSetChanged()
            refreshMenuItems()
        }
    }

}
