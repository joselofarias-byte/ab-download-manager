package com.abdownloadmanager.android.pages.browser

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Observes requests made by the embedded browser and remembers URLs that look
 * like downloadable media.
 *
 * This deliberately does not attempt to decrypt DRM-protected streams. HLS and
 * DASH manifests are recorded for the future extractor engine but are not
 * handed to the regular HTTP downloader as if they were complete media files.
 */
class MediaCatcher {
    private val _candidates =
        MutableStateFlow<Map<ABDMBrowserTabId, List<MediaCandidate>>>(emptyMap())
    val candidates = _candidates.asStateFlow()

    private val currentPageByTab = mutableMapOf<ABDMBrowserTabId, String>()

    fun interceptRequest(
        tabId: ABDMBrowserTabId,
        request: ABDMWebRequest,
    ) {
        val kind = MediaKind.detect(request.url) ?: return
        val candidate = MediaCandidate(
            request = request,
            kind = kind,
        )

        _candidates.update { current ->
            val items = current[tabId].orEmpty()
            if (items.any { it.request.url == candidate.request.url }) {
                current
            } else {
                current + (
                    tabId to (items + candidate)
                        .takeLast(MAX_CANDIDATES_PER_TAB)
                )
            }
        }
    }

    fun onPageNavigation(
        tabId: ABDMBrowserTabId,
        url: String,
    ) {
        val normalized = url.substringBefore('#')
        val previous = currentPageByTab.put(tabId, normalized)
        if (previous != null && previous != normalized) {
            clearTabCandidates(tabId)
        }
    }

    fun getDownloadableCandidates(tabId: ABDMBrowserTabId): List<MediaCandidate> {
        return _candidates.value[tabId]
            .orEmpty()
            .filter { it.kind.isDirectlyDownloadable }
            .distinctBy { it.request.url }
            .sortedWith(
                compareBy<MediaCandidate> { it.kind.sortOrder }
                    .thenBy { it.request.url }
            )
    }

    fun getExtractorCandidates(tabId: ABDMBrowserTabId): List<MediaCandidate> {
        return _candidates.value[tabId]
            .orEmpty()
            .filterNot { it.kind.isDirectlyDownloadable }
            .distinctBy { it.request.url }
    }

    fun clearTab(tabId: ABDMBrowserTabId) {
        currentPageByTab.remove(tabId)
        clearTabCandidates(tabId)
    }

    private fun clearTabCandidates(tabId: ABDMBrowserTabId) {
        _candidates.update { it - tabId }
    }

    companion object {
        private const val MAX_CANDIDATES_PER_TAB = 200
    }
}

data class MediaCandidate(
    val request: ABDMWebRequest,
    val kind: MediaKind,
)

enum class MediaKind(
    val isDirectlyDownloadable: Boolean,
    val isManifest: Boolean,
    val sortOrder: Int,
) {
    VIDEO_FILE(true, false, 0),
    AUDIO_FILE(true, false, 1),
    VIDEO_TRACK(false, false, 2),
    AUDIO_TRACK(false, false, 3),
    HLS_MANIFEST(true, true, 4),
    DASH_MANIFEST(false, true, 5);

    companion object {
        private val videoExtensions = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "3g2", "ogv"
        )
        private val audioExtensions = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba"
        )

        fun detect(url: String): MediaKind? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null

            val path = uri.path.orEmpty().lowercase()
            val extension = path.substringAfterLast('.', missingDelimiterValue = "")

            when {
                extension == "m3u8" -> return HLS_MANIFEST
                extension == "mpd" -> return DASH_MANIFEST
                extension in videoExtensions -> return VIDEO_FILE
                extension in audioExtensions -> return AUDIO_FILE
            }

            val query = uri.encodedQuery.orEmpty().lowercase()
            val decodedQuery = runCatching { Uri.decode(query) }.getOrDefault(query)

            if (hasMimeHint(decodedQuery, "video/")) {
                return VIDEO_TRACK
            }
            if (hasMimeHint(decodedQuery, "audio/")) {
                return AUDIO_TRACK
            }

            // Common manifest hints used when the URL itself has no extension.
            if (
                decodedQuery.contains("application/vnd.apple.mpegurl") ||
                decodedQuery.contains("application/x-mpegurl")
            ) {
                return HLS_MANIFEST
            }
            if (decodedQuery.contains("application/dash+xml")) {
                return DASH_MANIFEST
            }

            return null
        }

        private fun hasMimeHint(
            query: String,
            mimePrefix: String,
        ): Boolean {
            return query.contains("mime=$mimePrefix") ||
                query.contains("type=$mimePrefix") ||
                query.contains("content_type=$mimePrefix")
        }
    }
}
