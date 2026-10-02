package com.abdownloadmanager.android.pages.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Stable
import com.abdownloadmanager.android.pages.add.multiple.AddMultiDownloadActivity
import com.abdownloadmanager.android.pages.add.single.AddSingleDownloadActivity
import com.abdownloadmanager.android.pages.browser.bookmark.EditBookmarkState
import com.abdownloadmanager.android.storage.BrowserBookmark
import com.abdownloadmanager.android.storage.BrowserBookmarksStorage
import com.abdownloadmanager.android.ui.widget.WebContent
import com.abdownloadmanager.android.ui.widget.WebViewState
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadConfig
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.pages.adddownload.SilentImportOptions
import com.abdownloadmanager.shared.util.BaseComponent
import com.abdownloadmanager.shared.util.ClipboardUtil
import com.abdownloadmanager.shared.util.mvi.ContainsEffects
import com.abdownloadmanager.shared.util.mvi.supportEffects
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import com.arkivanov.decompose.ComponentContext
import ir.amirab.util.HttpUrlUtils
import ir.amirab.util.compose.action.AnAction
import ir.amirab.util.compose.action.MenuItem
import ir.amirab.util.compose.action.buildMenu
import ir.amirab.util.compose.action.simpleAction
import ir.amirab.util.compose.asStringSource
import ir.amirab.util.ifThen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.text.orEmpty

class BrowserComponent(
    componentContext: ComponentContext,
    private val context: Context,
    private val json: Json,
    private val browserBookmarksStorage: BrowserBookmarksStorage,
) : BaseComponent(
    componentContext,
), ContainsEffects<BrowserComponent.Effects> by supportEffects() {
    val mediaCatcher = MediaCatcher()
    private val mediaPageDownloader = MediaPageDownloader(context)

    val downloadInterceptor = DownloadInterceptor(
        scope, {
            val intent = when (it.size) {
                0 -> null
                1 -> AddSingleDownloadActivity.createIntent(
                    context,
                    AddDownloadConfig.SingleAddConfig(
                        newDownload = it.first(),
                        importOptions = ImportOptions(
                            silentImport = SilentImportOptions(
                                silentDownload = true,
                            ),
                        ),
                    ),
                    json,
                )

                else -> AddMultiDownloadActivity.createIntent(
                    context,
                    AddDownloadConfig.MultipleAddConfig(
                        it
                    ),
                    json
                )
            }
            intent?.let { intent ->
                sendEffect(Effects.StartActivity(intent))
            }
        }
    )
    private val currentSearchEngine = MutableStateFlow(
        SearchEngines.DuckDuckGo
    )
    val tabs = MutableStateFlow(
        ABDMTabs.createDefault()
    )
    val bookmarks = browserBookmarksStorage.bookmarksFlow
    private val _mainMenu: MutableStateFlow<MenuItem.SubMenu?> = MutableStateFlow(null)
    val mainMenu = _mainMenu.asStateFlow()
    fun openMainMenu() {
        val tab = tabs.value.activeTab
        val url = tab?.tabState?.lastLoadedUrl
        val title = tab?.tabState?.pageTitle
        _mainMenu.value = MenuItem.SubMenu(
            title = title?.asStringSource() ?: Res.string.menu.asStringSource(),
            items = buildMenu {
                +createNewTabAction()
                separator()
                +createShowBookmarksAction()
                if (url != null) {
                    if (isPageMediaActionsEligible(url)) {
                        separator()
                        +createDownloadPageVideoAction(url)
                        +createDownloadPageVideoSpanishCaptionsAction(url)
                        +createDownloadPageAudioAction(url)
                        +createDownloadPageAllCaptionsAction(url)
                    }
                    if (isBookmarked(url)) {
                        +createRemoveFromBookmarkAction(url)
                    } else {
                        +createAddToBookmarkAction(url, title)
                    }
                }
                tab?.let { activeTab ->
                    val mediaCandidates = mediaCatcher.getDownloadableCandidates(activeTab.tabId)
                    if (mediaCandidates.isNotEmpty()) {
                        separator()
                        +createDownloadDetectedMediaAction(
                            tab = activeTab,
                            candidates = mediaCandidates,
                        )
                    }
                    separator()
                    +createCloseTabAction(activeTab)
                }
            }
        )
    }

    fun closeMainMenu() {
        _mainMenu.value = null
    }

    fun newTab(
        url: String? = ABDMBrowserTab.blankPage,
        switch: Boolean = true,
        id: String = UUID.randomUUID().toString(),
        openedBy: ABDMBrowserTabId? = null,
    ): ABDMBrowserTab {
        val browserTab = ABDMBrowserTab(
            tabId = id,
            tabState = WebViewState(WebContent.fromNullableUrl(url)),
        )
        tabs.update { currentTabState ->
            val newTabPosition = openedBy?.let {
                // index of openedBy + 1 or null if not found
                currentTabState.tabs
                    .indexOfFirst { it.tabId == openedBy }
                    .takeIf { it >= 0 }
                    ?.plus(1)
            } ?: currentTabState.tabs.size
            val newItems = buildList {
                addAll(currentTabState.tabs)
                add(newTabPosition, browserTab)
            }
            val newIndex = if (switch) {
                newTabPosition
            } else {
                currentTabState.activeTabIndex
            }
            currentTabState.copy(
                tabs = newItems,
                activeTabIndex = newIndex
            )
        }
        return browserTab
    }

    fun closeTab(tabId: ABDMBrowserTabId) {
        mediaCatcher.clearTab(tabId)
        ShortLinkTrace.unregisterTab(tabId)
        tabs.update {
            val newItems = it.tabs.filterNot { it.tabId == tabId }
            it.copy(
                tabs = newItems,
                activeTabIndex = runCatching {
                    it.activeTabIndex.coerceIn(newItems.indices)
                }.getOrElse { -1 },
            )
        }
    }

    fun addToBookmarks(
        bookmark: BrowserBookmark,
        replaceWith: BrowserBookmark?,
    ) {
        browserBookmarksStorage.bookmarksFlow.update { currentBookmarks ->
            if (replaceWith != null) {
                currentBookmarks.map { item ->
                    item.ifThen(item == replaceWith) {
                        bookmark
                    }
                }
            } else {
                currentBookmarks.plus(bookmark)
            }
        }
    }

    private val _showBookmarkList: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showBookmarkList = _showBookmarkList.asStateFlow()
    fun setShowBookmarkList(show: Boolean) {
        _showBookmarkList.value = show
    }

    private val _editBookmarkState = MutableStateFlow<EditBookmarkState?>(null)
    val editBookmarkState = _editBookmarkState.asStateFlow()
    fun promptAddBookmark(
        bookmark: BrowserBookmark,
    ) {
        _editBookmarkState.value = EditBookmarkState(
            initialValue = bookmark,
            editMode = false,
        )
    }

    fun promptEditBookmark(
        bookmark: BrowserBookmark
    ) {
        _editBookmarkState.value = EditBookmarkState(
            initialValue = bookmark,
            editMode = true,
        )
    }

    fun dismissEditBookmark() {
        _editBookmarkState.value = null
    }

    fun removeBookmark(url: String) {
        browserBookmarksStorage.bookmarksFlow.update {
            it.filterNot { bookmark -> bookmark.url == url }
        }
    }

    fun clearBookmarks() {
        browserBookmarksStorage.bookmarksFlow.value = emptyList()
    }

    fun isBookmarked(url: String): Boolean {
        return browserBookmarksStorage.bookmarksFlow.value.find {
            it.url == url
        } != null
    }

    fun switchTab(tabId: ABDMBrowserTabId) {
        tabs.update {
            val tabIndex = it.tabs.indexOfFirst { it.tabId == tabId }
            val newIndex = if (tabIndex < 0) {
                it.activeTabIndex
            } else {
                tabIndex
            }
            it.copy(
                activeTabIndex = newIndex,
            )
        }
    }

    private val websiteAndTLD by lazy {
        """^[\w.-]+\.[a-zA-Z]{2,}(:\d{1,5})?$""".toRegex()
    }

    fun createNewUrlFor(urlOrSearch: String): String {
        val value = urlOrSearch.trim()
        if (value.contains(' ')) {
            return createSearchEngineUrl(value)
        }
        if (HttpUrlUtils.isValidUrl(value)) {
            return value
        }
        if (websiteAndTLD.matches(value)) {
            val withHttpScheme = "https://$value"
            if (HttpUrlUtils.isValidUrl(withHttpScheme)) {
                return withHttpScheme
            }
        }
        return createSearchEngineUrl(value)
    }

    private fun createSearchEngineUrl(searchText: String): String {
        return currentSearchEngine.value.createSearchUrl(searchText)
    }

    val contextMenu: MutableStateFlow<MenuItem.SubMenu?> = MutableStateFlow(null)

    fun closeContextMenu() {
        contextMenu.value = null
    }

    fun onLinkSelected(
        link: String,
        tab: ABDMBrowserTab,
    ) {
        contextMenu.value = MenuItem.SubMenu(
            title = link.asStringSource(),
            items = buildMenu {
                +simpleAction(
                    Res.string.browser_open_in_new_tab.asStringSource(),
                    MyIcons.file,
                ) {
                    newTab(
                        url = link,
                        switch = true,
                        openedBy = tab.tabId,
                    )
                }
                +simpleAction(
                    Res.string.browser_open_in_new_background_tab.asStringSource(),
                    MyIcons.file,
                ) {
                    newTab(
                        url = link,
                        switch = false,
                        openedBy = tab.tabId,
                    )
                }
                +simpleAction(
                    Res.string.share.asStringSource(),
                    MyIcons.share,
                ) {
                    sendEffect(Effects.ShareText(link))
                }
                +simpleAction(
                    Res.string.copy.asStringSource(),
                    MyIcons.copy,
                ) {
                    ClipboardUtil.copy(link)
                }
                +simpleAction(
                    Res.string.download.asStringSource(),
                    MyIcons.download,
                ) {
                    downloadInterceptor.onDownloadStart(
                        url = link,
                        userAgent = null,
                        page = null,
                        tab = tab,
                    )
                }
                if (isBookmarked(link)) {
                    +createRemoveFromBookmarkAction(link)
                } else {
                    +createAddToBookmarkAction(link, null)
                }
            }
        )
    }

    fun createNewTabAction(): AnAction {
        return simpleAction(
            title = Res.string.browser_new_tab.asStringSource(),
            icon = MyIcons.file,
        ) {
            newTab(
                url = null,
                switch = true,
            )
        }
    }

    fun createAddToBookmarkAction(
        url: String,
        title: String?,
    ): AnAction {
        return simpleAction(
            Res.string.browser_add_to_bookmarks.asStringSource(),
            MyIcons.add,
        ) {
            promptAddBookmark(
                BrowserBookmark(
                    url = url,
                    title = title.orEmpty(),
                )
            )
        }
    }

    fun createRemoveFromBookmarkAction(
        url: String,
    ): AnAction {
        return simpleAction(
            Res.string.browser_remove_from_bookmarks.asStringSource(),
            MyIcons.remove,
        ) {
            removeBookmark(url)
        }
    }

    fun downloadDetectedMedia(
        tab: ABDMBrowserTab,
    ) {
        val candidates = mediaCatcher.getDownloadableCandidates(tab.tabId)
        if (candidates.isEmpty()) return

        downloadInterceptor.onDownloadMediaCandidates(
            candidates = candidates,
            userAgent = null,
            tab = tab,
        )
    }

    private fun startPageMediaDownload(
        url: String,
        mode: MediaPageDownloadMode,
    ) {
        closeMainMenu()
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    when (mode) {
                        MediaPageDownloadMode.AUDIO_ONLY ->
                            Res.string.media_preparing_audio.asStringSource().getString()
                        MediaPageDownloadMode.ALL_CAPTIONS ->
                            Res.string.media_preparing_captions.asStringSource().getString()
                        MediaPageDownloadMode.BEST_VIDEO_SPANISH_CAPTIONS ->
                            Res.string.media_preparing_video_spanish_captions.asStringSource().getString()
                        MediaPageDownloadMode.BEST_VIDEO ->
                            Res.string.media_preparing_video.asStringSource().getString()
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }

            val result = when (mode) {
                MediaPageDownloadMode.BEST_VIDEO -> mediaPageDownloader.downloadBestVideo(url)
                MediaPageDownloadMode.AUDIO_ONLY -> mediaPageDownloader.downloadAudioOnly(url)
                MediaPageDownloadMode.BEST_VIDEO_SPANISH_CAPTIONS ->
                    mediaPageDownloader.downloadBestVideoWithSpanishCaptions(url)
                MediaPageDownloadMode.ALL_CAPTIONS ->
                    mediaPageDownloader.downloadAllCaptions(url)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = {
                            Res.string.media_download_saved.asStringSource().getString()
                        },
                        onFailure = {
                            val error = it.message
                                ?: Res.string.media_unknown_error.asStringSource().getString()
                            Res.string.media_download_failed
                                .asStringSource()
                                .getString(mapOf("error" to error))
                        },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun createDownloadPageVideoAction(url: String): AnAction {
        return simpleAction(
            title = Res.string.media_download_page_video_best.asStringSource(),
            icon = MyIcons.videoFile,
        ) {
            startPageMediaDownload(url, MediaPageDownloadMode.BEST_VIDEO)
        }
    }

    fun createDownloadPageVideoSpanishCaptionsAction(url: String): AnAction {
        return simpleAction(
            title = Res.string.media_download_video_spanish_captions.asStringSource(),
            icon = MyIcons.videoFile,
        ) {
            startPageMediaDownload(
                url,
                MediaPageDownloadMode.BEST_VIDEO_SPANISH_CAPTIONS,
            )
        }
    }

    fun createDownloadPageAudioAction(url: String): AnAction {
        return simpleAction(
            title = Res.string.media_download_page_audio_mp3.asStringSource(),
            icon = MyIcons.musicFile,
        ) {
            startPageMediaDownload(url, MediaPageDownloadMode.AUDIO_ONLY)
        }
    }

    fun createDownloadPageAllCaptionsAction(url: String): AnAction {
        return simpleAction(
            title = Res.string.media_download_all_captions_srt.asStringSource(),
            icon = MyIcons.documentFile,
        ) {
            startPageMediaDownload(
                url,
                MediaPageDownloadMode.ALL_CAPTIONS,
            )
        }
    }

    fun createDownloadDetectedMediaAction(
        tab: ABDMBrowserTab,
        candidates: List<MediaCandidate>,
    ): AnAction {
        return simpleAction(
            title = Res.string.media_detected_count
                .asStringSource()
                .getString(mapOf("count" to candidates.size.toString()))
                .asStringSource(),
            icon = MyIcons.videoFile,
        ) {
            downloadDetectedMedia(tab)
            closeMainMenu()
        }
    }

    fun createCloseTabAction(tab: ABDMBrowserTab): AnAction {
        return simpleAction(
            title = Res.string.browser_close_tab.asStringSource(),
            icon = MyIcons.close,
        ) {
            closeTab(tab.tabId)
        }
    }

    fun createShowBookmarksAction(): AnAction {
        return simpleAction(
            title = Res.string.browser_bookmarks.asStringSource(),
            icon = MyIcons.hearth,
        ) {
            setShowBookmarkList(true)
        }
    }

    sealed interface Effects {
        data class StartActivity(
            val intent: Intent
        ) : Effects

        data class ShareText(
            val text: String,
        ) : Effects
    }
}

private fun isPageMediaActionsEligible(url: String): Boolean {
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }

    val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
    val extension = path
        .substringAfterLast('/', missingDelimiterValue = path)
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()

    // Do not offer yt-dlp page actions for ordinary downloadable files.
    // Direct files are handled automatically by the browser download flow.
    return extension !in setOf(
        "apk", "apks", "xapk", "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz",
        "pdf", "txt", "csv", "json", "xml", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "exe", "msi", "dmg", "iso", "deb", "rpm", "jar", "aab",
    )
}

typealias ABDMBrowserTabId = String

@Stable
data class ABDMBrowserTab(
    val tabId: ABDMBrowserTabId,
    val tabState: WebViewState,
) {
    companion object {
        fun createDefaultTab(
            page: String = blankPage
        ) = ABDMBrowserTab(
            tabId = UUID.randomUUID().toString(),
            tabState = WebViewState(WebContent.Url(page)),
        )

        val blankPage = "about:blank"
    }
}

@Stable
data class ABDMTabs(
    val tabs: List<ABDMBrowserTab>,
    val activeTabIndex: Int,
) {
    val tabsSize = tabs.size
    val activeTab get() = if (activeTabIndex == -1) null else tabs[activeTabIndex]

    companion object {
        fun createDefault(): ABDMTabs = ABDMTabs(
            listOf(),
            -1,
        )
    }
}

