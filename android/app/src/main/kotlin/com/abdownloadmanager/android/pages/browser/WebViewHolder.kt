package com.abdownloadmanager.android.pages.browser

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.abdownloadmanager.android.ui.widget.AccompanistWebChromeClient
import com.abdownloadmanager.android.ui.widget.AccompanistWebViewClient
import com.abdownloadmanager.android.ui.widget.WebContent
import com.abdownloadmanager.android.ui.widget.WebViewNavigator
import com.abdownloadmanager.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.launch
import java.util.UUID

class WebViewRegistry(
    private val scope: CoroutineScope,
    private val browserComponent: BrowserComponent,
) : WebViewFactory {
    val viewHolders = mutableMapOf<ABDMBrowserTabId, WebViewHolder>()
    fun onTabsUpdated(
        webViewStates: ABDMTabs,
    ) {
        val webViewStateIds = webViewStates.tabs.map { it.tabId }.toSet()
        for (viewHolderKey in viewHolders.keys.toList()) {
            if (viewHolderKey !in webViewStateIds) {
                removeViewHolder(viewHolderKey)
            }
        }
    }

    fun getWebViewHolder(
        tab: ABDMBrowserTab
    ): WebViewHolder {
        return viewHolders.getOrPut(tab.tabId, {
            WebViewHolder(
                tab = tab,
                navigator = WebViewNavigator(scope),
                webView = null,
                client = ABDMWebViewClient(
                    requestInterceptor = browserComponent.downloadInterceptor,
                    mediaCatcher = browserComponent.mediaCatcher,
                    scope = scope,
                ),
                chromeClient = ABDMChromeClient(browserComponent, ::getWebViewHolder),
                webViewFactory = this,
            )
        })
    }

    fun removeViewHolder(id: String) {
        viewHolders.remove(id)?.release()
    }

    fun disposeAll() {
        viewHolders.forEach { (_, holder) ->
            holder.release()
        }
        viewHolders.clear()
    }

    override fun createWebView(
        context: Context,
        tab: ABDMBrowserTab,
    ): ABDMWebView {
        return ABDMWebView(context).apply {
            val webView = this
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = false
            webView.settings.setSupportMultipleWindows(true)
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webView.isLongClickable = true
            webView.setOnLongClickListener {
                val hit = webView.hitTestResult

                if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                ) {
                    val url = hit.extra ?: return@setOnLongClickListener false

                    browserComponent.onLinkSelected(
                        url,
                        tab,
                    )
                    true
                } else {
                    false
                }
            }
            webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                scope.launch(Dispatchers.Main) {
                    if (url == null) {
                        return@launch
                    }

                    val isHtmlInterstitial =
                        mimeType?.startsWith("text/html", ignoreCase = true) == true ||
                            mimeType?.startsWith("application/xhtml+xml", ignoreCase = true) == true ||
                            contentDisposition?.contains(".html", ignoreCase = true) == true ||
                            contentDisposition?.contains(".htm", ignoreCase = true) == true ||
                            Uri.parse(url).lastPathSegment?.endsWith(".html", ignoreCase = true) == true ||
                            Uri.parse(url).lastPathSegment?.endsWith(".htm", ignoreCase = true) == true

                    if (isHtmlInterstitial && isHttpWebUrl(url)) {
                        Toast.makeText(
                            webView.context,
                            Res.string.link_intermediate_resolving.asStringSource().getString(),
                            Toast.LENGTH_SHORT,
                        ).show()

                        val referer = webView.url ?: webView.originalUrl ?: webView.openedBy
                        val result = IntermediateLinkResolver().resolve(
                            url = url,
                            userAgent = userAgent ?: webView.settings.userAgentString,
                            referer = referer,
                        )

                        result.fold(
                            onSuccess = { resolved ->
                                when (resolved) {
                                    is IntermediateLinkResolution.HtmlPage -> {
                                        webView.loadDataWithBaseURL(
                                            resolved.url,
                                            resolved.html,
                                            "text/html",
                                            "UTF-8",
                                            resolved.url,
                                        )
                                    }

                                    is IntermediateLinkResolution.Navigate -> {
                                        extractHttpWebFallback(resolved.url)?.let { webTarget ->
                                            webView.loadUrl(webTarget)
                                        } ?: webView.loadUrl(resolved.url)
                                    }

                                    is IntermediateLinkResolution.DirectDownload -> {
                                        browserComponent.downloadInterceptor.onDownloadStart(
                                            resolved.url,
                                            userAgent ?: webView.settings.userAgentString,
                                            referer,
                                            tab,
                                        )
                                    }
                                }
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    webView.context,
                                    Res.string.link_intermediate_failed
                                        .asStringSource()
                                        .getString(
                                            mapOf(
                                                "error" to (
                                                    error.message
                                                        ?: Res.string.media_unknown_error
                                                            .asStringSource()
                                                            .getString()
                                                )
                                            )
                                        ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                        return@launch
                    }

                    if (!webView.canGoBack() && webView.originalUrl == null) {
                        browserComponent.closeTab(tab.tabId)
                    }
                    browserComponent.downloadInterceptor.onDownloadStart(
                        url,
                        userAgent,
                        webView.originalUrl ?: webView.openedBy,
                        tab,
                    )
                }
            }
            webView.tabId = tab.tabId
        }
    }

}

data class WebViewHolder(
    val tab: ABDMBrowserTab,
    var webView: ABDMWebView? = null,
    val navigator: WebViewNavigator,
    val client: ABDMWebViewClient,
    val chromeClient: ABDMChromeClient,
    private val webViewFactory: WebViewFactory,
) {

    fun activate(context: Context): ABDMWebView {
        return if (webView != null) {
            (webView!!).also {
                it.onResume()
            }
        } else {
            webViewFactory.createWebView(context, tab).also { webView = it }
        }
    }

    fun deactivate() {
        webView?.onPause()
        // prevent reloading after activated again
        tab.tabState.content = WebContent.NavigatorOnly
    }

    fun release() {
        webView?.onPause()
        webView?.destroy()
        webView = null
    }
}

interface WebViewFactory {
    fun createWebView(
        context: Context,
        tab: ABDMBrowserTab,
    ): ABDMWebView
}

class ABDMWebViewClient(
    private val requestInterceptor: DownloadInterceptor,
    private val mediaCatcher: MediaCatcher,
    private val scope: CoroutineScope,
) : AccompanistWebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request != null) {
            scope.launch(Dispatchers.Main) {
                val pageUrl = view?.url ?: view?.originalUrl
                val headers = request.requestHeaders.toMutableMap()
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    view?.settings?.userAgentString
                        ?.takeIf { it.isNotBlank() }
                        ?.let { headers["User-Agent"] = it }
                }
                if (
                    pageUrl != null &&
                    headers.keys.none { it.equals("Referer", ignoreCase = true) }
                ) {
                    headers["Referer"] = pageUrl
                }
                val webRequest = ABDMWebRequest(
                    url = request.url.toString(),
                    headers = headers,
                    page = pageUrl,
                )
                requestInterceptor.interceptRequest(webRequest)
                (view as? ABDMWebView)?.tabId?.let { tabId ->
                    mediaCatcher.interceptRequest(
                        tabId = tabId,
                        request = webRequest,
                    )
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(
        view: WebView,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        if (url != null) {
            (view as? ABDMWebView)?.tabId?.let { tabId ->
                mediaCatcher.onPageNavigation(tabId, url)
            }
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {

        val url = request.url.toString()

        // Let WebView load normal web pages.
        if (isHttpWebUrl(url)) {
            return false
        }

        // Shorteners and ad/interstitial pages often attempt to hand navigation
        // to Chrome through intent:// or googlechrome://. Prefer the embedded
        // HTTP(S) destination so the redirect chain remains inside ABDM and
        // keeps the current cookies/session.
        extractHttpWebFallback(url)?.let { webTarget ->
            view.loadUrl(webTarget)
            return true
        }

        // No usable web fallback was carried by the URI. Only now hand the
        // deep link to an external app as a last resort.
        try {
            val intent = if (url.startsWith("intent://", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            val pm = view.context.packageManager

            if (intent.resolveActivity(pm) != null) {
                view.context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}

class ABDMChromeClient(
    private val browserComponent: BrowserComponent,
    private val createWebViewHolder: (tab: ABDMBrowserTab) -> WebViewHolder,
) : AccompanistWebChromeClient() {
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (view == null) return false
        val transport = (resultMsg?.obj as? WebView.WebViewTransport) ?: return false
        val newTab = browserComponent.newTab(
            id = UUID.randomUUID().toString(),
            switch = true,
            url = null,
            openedBy = (view as? ABDMWebView)?.tabId
        )
        val newWebView = createWebViewHolder(newTab).activate(view.context)
        newWebView.openedBy = view.originalUrl ?: view.url
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }
}

class ABDMWebView(
    context: Context,
) : WebView(context) {
    var openedBy: String? = null
    var tabId: String? = null
}
