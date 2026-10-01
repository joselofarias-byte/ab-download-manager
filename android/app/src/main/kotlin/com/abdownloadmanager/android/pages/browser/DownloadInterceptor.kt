package com.abdownloadmanager.android.pages.browser

import android.webkit.CookieManager
import com.abdownloadmanager.android.ui.widget.WebViewState
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.util.HttpUrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

typealias ABDMWebRequestId = String

data class ABDMWebRequest(
    val url: String,
    val headers: Map<String, String>,
    val page: String?,
) {
    val id: ABDMWebRequestId = url
}

interface RequestInterceptor {
    fun interceptRequest(request: ABDMWebRequest)
}

class DownloadInterceptor(
    private val scope: CoroutineScope,
    private val onNewDownload: (newDownloads: List<AddDownloadCredentialsInUiProps>) -> Unit,
) : RequestInterceptor {
    private val requests = mutableMapOf<String, ABDMWebRequest>()

    fun onDownloadStart(
        url: String?,
        userAgent: String?,
        page: String?,
        tab: ABDMBrowserTab,
    ) {
        if (url == null) {
            return
        }
        if (!HttpUrlUtils.isValidUrl(url)) {
            return
        }
        val webRequest = getWebRequestOrDefault(
            url = url,
            userAgent = userAgent,
            page = page,
            webViewState = tab.tabState,
        )
        onNewDownload(
            listOf(
                webRequest.toHttpDownloadCredentialsInUiProps()
            )
        )
    }

    /**
     * Opens the normal AB Download Manager add-download flow for media observed
     * inside the embedded browser. Direct files use the HTTP downloader while
     * HLS manifests use AB Download Manager's native HLS downloader.
     *
     * Request headers, Referer and browser cookies are preserved so media URLs
     * tied to the current browser session can still be fetched.
     */
    fun onDownloadMediaCandidates(
        candidates: List<MediaCandidate>,
        userAgent: String?,
        tab: ABDMBrowserTab,
    ) {
        val downloads = candidates
            .asSequence()
            .filter { HttpUrlUtils.isValidUrl(it.request.url) }
            .distinctBy { it.request.url }
            .mapNotNull { candidate ->
                val request = candidate.request
                    .copy(
                        page = candidate.request.page ?: getPageUrl(tab.tabState),
                    )
                    .withUserAgent(userAgent)
                    .withCookieManagerCookies()

                when (candidate.kind) {
                    MediaKind.HLS_MANIFEST -> request.toHlsDownloadCredentialsInUiProps()
                    MediaKind.VIDEO_FILE,
                    MediaKind.AUDIO_FILE -> request.toHttpDownloadCredentialsInUiProps()

                    MediaKind.VIDEO_TRACK,
                    MediaKind.AUDIO_TRACK,
                    MediaKind.DASH_MANIFEST -> null
                }
            }
            .toList()

        if (downloads.isNotEmpty()) {
            onNewDownload(downloads)
        }
    }

    override fun interceptRequest(
        request: ABDMWebRequest,
    ) {
        addToHeaders(request)
    }

    private fun addToHeaders(request: ABDMWebRequest) {
        requests[request.id] = request
        scope.launch {
            delay(REMOVE_REQUESTS_DELAY.milliseconds)
            requests.remove(request.id)
        }
    }

    private fun getWebRequestOrDefault(
        url: String,
        userAgent: String?,
        page: String?,
        webViewState: WebViewState,
    ): ABDMWebRequest {
        var request = requests[url]
        if (request == null) {
            request = ABDMWebRequest(
                url = url,
                headers = emptyMap(),
                page = getPageUrl(webViewState) ?: page,
            )
        }
        return request
            .withUserAgent(userAgent)
            .withCookieManagerCookies()
    }

    private fun ABDMWebRequest.toHttpDownloadCredentialsInUiProps(): AddDownloadCredentialsInUiProps {
        return AddDownloadCredentialsInUiProps(
            HttpDownloadCredentials(
                link = url,
                headers = headers,
                downloadPage = page,
                userAgent = headerValue(USER_AGENT_HEADER),
            ),
            AddDownloadCredentialsInUiProps.Configs()
        )
    }

    private fun ABDMWebRequest.toHlsDownloadCredentialsInUiProps(): AddDownloadCredentialsInUiProps {
        return AddDownloadCredentialsInUiProps(
            HLSDownloadCredentials(
                link = url,
                headers = headers,
                downloadPage = page,
                userAgent = headerValue(USER_AGENT_HEADER),
            ),
            AddDownloadCredentialsInUiProps.Configs()
        )
    }

    private fun ABDMWebRequest.headerValue(name: String): String? {
        val key = headers.keys.firstOrNull {
            it.equals(name, ignoreCase = true)
        }
        return key?.let(headers::get)
    }

    private fun ABDMWebRequest.withUserAgent(userAgent: String?): ABDMWebRequest {
        if (userAgent == null) {
            return this
        }
        val existingKey = headers.keys.firstOrNull {
            it.equals(USER_AGENT_HEADER, ignoreCase = true)
        }
        if (existingKey != null) {
            return this
        }
        return copy(
            headers = headers.plus(
                USER_AGENT_HEADER to userAgent
            )
        )
    }

    private fun ABDMWebRequest.withCookieManagerCookies(): ABDMWebRequest {
        val cookieFromCookieManager =
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() } ?: return this

        val currentKey = headers.keys.firstOrNull {
            it.equals(COOKIE_HEADER, ignoreCase = true)
        }
        val currentCookie = currentKey
            ?.let(headers::get)
            ?.takeIf { it.isNotBlank() }

        if (currentCookie?.contains(cookieFromCookieManager) == true) {
            return this
        }

        val cookieKey = currentKey ?: COOKIE_HEADER
        return copy(
            headers = headers.plus(
                cookieKey to if (currentCookie != null) {
                    "$currentCookie; $cookieFromCookieManager"
                } else {
                    cookieFromCookieManager
                }
            )
        )
    }

    private fun getPageUrl(state: WebViewState): String? {
        return state.lastLoadedUrl
    }

    companion object {
        private const val REMOVE_REQUESTS_DELAY = 20_000L
        private const val COOKIE_HEADER = "Cookie"
        private const val USER_AGENT_HEADER = "User-Agent"
    }
}
