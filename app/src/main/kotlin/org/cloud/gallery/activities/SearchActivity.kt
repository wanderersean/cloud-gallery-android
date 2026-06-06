package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databinding.ActivitySearchBinding
import org.fossify.gallery.extensions.*
import org.fossify.gallery.helpers.CloudUploadFilterState
import org.fossify.gallery.helpers.GridSpacingItemDecoration
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_CLOUD_ONLY
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_HIDE_ALL
import org.fossify.gallery.helpers.TITLE_DISPLAY_MODE_SHOW_MERGED
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File

class SearchActivity : SimpleActivity(), MediaOperationsListener {
    override var isSearchBarEnabled = true
    
    private var mLastSearchedText = ""

    private var mCurrAsyncTask: GetMediaAsynctask? = null
    private var mAllMedia = ArrayList<ThumbnailItem>()

    private val binding by viewBinding(ActivitySearchBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.searchMenu),
            padBottomImeAndSystem = listOf(binding.searchGrid)
        )
        binding.searchEmptyTextPlaceholder.setTextColor(getProperTextColor())
        getAllMedia()
        binding.searchFastscroller.updateColors(getProperPrimaryColor())
        binding.searchFastscroller.handleWidth = (36 * resources.displayMetrics.density).toInt()
        binding.searchFastscroller.handleHeight = (56 * resources.displayMetrics.density).toInt()
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
    }

    override fun onDestroy() {
        super.onDestroy()
        mCurrAsyncTask?.stopFetching()
    }

    private fun setupOptionsMenu() {
        binding.searchMenu.requireToolbar().inflateMenu(R.menu.menu_search)
        binding.searchMenu.toggleHideOnScroll(true)
        binding.searchMenu.setupMenu()
        binding.searchMenu.toggleForceArrowBackIcon(true)
        binding.searchMenu.focusView()
        binding.searchMenu.updateHintText(getString(org.fossify.commons.R.string.search_files))

        binding.searchMenu.onNavigateBackClickListener = {
            if (binding.searchMenu.getCurrentQuery().isEmpty()) {
                finish()
            } else {
                binding.searchMenu.closeSearch()
            }
        }

        binding.searchMenu.onSearchTextChangedListener = { text ->
            mLastSearchedText = text
            textChanged(text)
        }

        binding.searchMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.toggle_filename -> toggleFilenameVisibility()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.searchMenu.updateColors()
    }

    private fun textChanged(text: String) {
        ensureBackgroundThread {
            try {
                // 1. 本地过滤
                val filtered = mAllMedia.filter { it is Medium && it.name.contains(text, true) } as ArrayList
                
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
                                val cloudMatches = mAllMedia.filter { 
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
                
                val grouped = MediaFetcher(applicationContext).groupMedia(distinctFiltered as ArrayList<Medium>, "")
                
                runOnUiThread {
                    // Prevent race condition: ensure we are displaying results for the latest query
                    if (text == mLastSearchedText) {
                        if (grouped.isEmpty()) {
                            binding.searchEmptyTextPlaceholder.text = getString(org.fossify.commons.R.string.no_items_found)
                            binding.searchEmptyTextPlaceholder.beVisible()
                        } else {
                            binding.searchEmptyTextPlaceholder.beGone()
                        }
                        handleGridSpacing(grouped)
                        getMediaAdapter()?.updateMedia(grouped)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun setupAdapter() {
        val currAdapter = binding.searchGrid.adapter
        if (currAdapter == null) {
            MediaAdapter(this, mAllMedia, this, false, false, "", binding.searchGrid) {
                if (it is Medium) {
                    itemClicked(it.path)
                }
            }.apply {
                binding.searchGrid.adapter = this
            }
            setupLayoutManager()
            handleGridSpacing(mAllMedia)
        } else if (mLastSearchedText.isEmpty()) {
            (currAdapter as MediaAdapter).updateMedia(mAllMedia)
            handleGridSpacing(mAllMedia)
        } else {
            textChanged(mLastSearchedText)
        }

        setupScrollDirection()
    }

    private fun handleGridSpacing(media: ArrayList<ThumbnailItem>) {
        val viewType = config.getFolderViewType(SHOW_ALL)
        if (viewType == VIEW_TYPE_GRID) {
            if (binding.searchGrid.itemDecorationCount > 0) {
                binding.searchGrid.removeItemDecorationAt(0)
            }

            val spanCount = config.mediaColumnCnt
            val spacing = config.thumbnailSpacing
            val decoration = GridSpacingItemDecoration(spanCount, spacing, config.scrollHorizontally, config.fileRoundedCorners, media, true)
            binding.searchGrid.addItemDecoration(decoration)
        }
    }

    private fun getMediaAdapter() = binding.searchGrid.adapter as? MediaAdapter

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

    private fun itemClicked(path: String) {
        val isVideo = path.isVideoFast()
        if (isVideo) {
            openPath(path, false)
        } else {
            Intent(this, ViewPagerActivity::class.java).apply {
                putExtra(PATH, path)
                putExtra(SHOW_ALL, false)
                startActivity(this)
            }
        }
    }

    private fun setupLayoutManager() {
        val viewType = config.getFolderViewType(SHOW_ALL)
        if (viewType == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.searchGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.searchGrid.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.searchGrid.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val layoutManager = binding.searchGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
    }

    private fun setupScrollDirection() {
        val viewType = config.getFolderViewType(SHOW_ALL)
        val scrollHorizontally = config.scrollHorizontally && viewType == VIEW_TYPE_GRID
        binding.searchFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun getAllMedia() {
        getCachedMedia("", filterUploadedPhotos = CloudUploadFilterState.showOnlyUnuploadedPhotos) {
            if (it.isNotEmpty()) {
                mAllMedia = it.clone() as ArrayList<ThumbnailItem>
            }
            runOnUiThread {
                setupAdapter()
            }
            startAsyncTask(false)
        }
    }

    private fun startAsyncTask(updateItems: Boolean) {
        mCurrAsyncTask?.stopFetching()
        mCurrAsyncTask = GetMediaAsynctask(applicationContext, "", showAll = true, filterUploadedPhotos = CloudUploadFilterState.showOnlyUnuploadedPhotos) { newMedia, isFinal ->
            mAllMedia = newMedia.clone() as ArrayList<ThumbnailItem>
            if (updateItems) {
                textChanged(mLastSearchedText)
            }
        }

        mCurrAsyncTask!!.execute()
    }

    override fun refreshItems() {
        startAsyncTask(true)
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        val filtered = fileDirItems.filter { File(it.path).isFile && it.path.isMediaFile() } as ArrayList
        if (filtered.isEmpty()) {
            return
        }

        if (config.useRecycleBin && !skipRecycleBin && !filtered.first().path.startsWith(recycleBinPath)) {
            val movingItems = resources.getQuantityString(org.fossify.commons.R.plurals.moving_items_into_bin, filtered.size, filtered.size)
            toast(movingItems)

            movePathsInRecycleBin(filtered.map { it.path } as ArrayList<String>) {
                if (it) {
                    deleteFilteredFiles(filtered)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            val deletingItems = resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, filtered.size, filtered.size)
            toast(deletingItems)
            deleteFilteredFiles(filtered)
        }
    }

    private fun deleteFilteredFiles(filtered: ArrayList<FileDirItem>) {
        deleteFiles(filtered) {
            if (!it) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@deleteFiles
            }

            mAllMedia.removeAll { filtered.map { it.path }.contains((it as? Medium)?.path) }

            ensureBackgroundThread {
                val useRecycleBin = config.useRecycleBin
                filtered.forEach {
                    if (it.path.startsWith(recycleBinPath) || !useRecycleBin) {
                        deleteDBPath(it.path)
                    }
                }
            }
        }
    }

    override fun selectedPaths(paths: ArrayList<String>) {}

    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) {}
}
