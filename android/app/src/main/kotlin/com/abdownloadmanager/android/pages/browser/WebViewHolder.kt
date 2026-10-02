package com.abdownloadmanager.android.pages.browser

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
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

private const val TAG = "ABDM_SHORTLINK"

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
                    closeTab = browserComponent::closeTab,
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
            webView.settings.javaScriptCanOpenWindowsAutomatically = true
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webView.addJavascriptInterface(
                ShortLinkJavascriptBridge(),
                "ABDMShortLink",
            )

            // --- ShortXLinks WebView-detection bypass ---
            // The default Android WebView User-Agent contains "; wv)" and
            // "Version/4.0" markers.  Link shorteners (e.g. ShortXLinks /
            // AdLinkFly) check navigator.userAgent for these and display
            // "Redirecting to Chrome" instead of continuing the redirect
            // chain.  Stripping the markers makes the WebView appear as
            // standard Chrome Mobile, which is what Quetta/Chrome present.
            val defaultUA = webView.settings.userAgentString
            val normalizedUA = normalizeWebViewUserAgent(defaultUA)
            if (normalizedUA != defaultUA) {
                webView.settings.userAgentString = normalizedUA
                Log.d(TAG, "UA normalized: $normalizedUA")
                ShortLinkTrace.record("UA normalized: $normalizedUA")
            } else {
                Log.d(TAG, "UA unchanged: $defaultUA")
                ShortLinkTrace.record("UA unchanged: $defaultUA")
            }

            // Cross-domain redirect chains (shortxlinks.in → .com →
            // destination) need third-party cookies.  On Android Lollipop+
            // the default is false.
            CookieManager.getInstance().let { cm ->
                cm.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setAcceptThirdPartyCookies(webView, true)
                }
            }

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
                    Log.d(TAG, "onDownloadStart url=$url mime=$mimeType disp=$contentDisposition")
                    ShortLinkTrace.record("onDownloadStart url=$url mime=$mimeType disp=$contentDisposition")

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
                    if (ShortLinkTrace.isTabActive(tab.tabId)) {
                        ShortLinkTrace.record("direct-download url=$url")
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
    private val closeTab: (String) -> Unit,
) : AccompanistWebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request != null) {
            scope.launch(Dispatchers.Main) {
                val pageUrl = view?.url ?: view?.originalUrl
                val headers = request.requestHeaders.toMutableMap()

                // Strip the X-Requested-With header that WebView adds
                // automatically (containing the app package name).  Link
                // shorteners use it as a secondary WebView detector.
                headers.keys.firstOrNull {
                    it.equals("X-Requested-With", ignoreCase = true)
                }?.let { headers.remove(it) }

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
        Log.d(TAG, "onPageStarted url=$url")
        ShortLinkTrace.record("onPageStarted url=$url")
        logCookiePresence(url)
        if (url != null) {
            (view as? ABDMWebView)?.tabId?.let { tabId ->
                mediaCatcher.onPageNavigation(tabId, url)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        Log.d(TAG, "onPageFinished url=$url title=${view.title}")
        ShortLinkTrace.record("onPageFinished url=$url title=${view.title}")

        val tabId = (view as? ABDMWebView)?.tabId
        if (ShortLinkTrace.isTabActive(tabId) && url != null) {
            injectShortLinkAutomation(view, url)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val message = "onReceivedError url=${request.url}" +
                " code=${error?.errorCode}" +
                " desc=${error?.description}"
            Log.w(TAG, message)
            ShortLinkTrace.record(message)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            val message = "onReceivedHttpError url=${request.url}" +
                " status=${errorResponse?.statusCode}" +
                " reason=${errorResponse?.reasonPhrase}"
            Log.w(TAG, message)
            ShortLinkTrace.record(message)
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {

        val url = request.url.toString()
        val scheme = request.url.scheme.orEmpty()
        val isMainFrame = request.isForMainFrame
        val hasGesture = request.hasGesture()

        val navigationMessage = "shouldOverrideUrlLoading" +
            " url=$url" +
            " scheme=$scheme" +
            " mainFrame=$isMainFrame" +
            " gesture=$hasGesture"
        Log.d(TAG, navigationMessage)
        ShortLinkTrace.record(navigationMessage)

        val shortLinkTabId = (view as? ABDMWebView)?.tabId
        val shortLinkMode = ShortLinkTrace.isTabActive(shortLinkTabId)
        if (shortLinkMode && isMainFrame && isHttpWebUrl(url)) {
            val host = request.url.host.orEmpty().lowercase()

            if (isKnownShortLinkAdHost(host)) {
                Log.d(TAG, "  -> blocked known ad host: $host")
                ShortLinkTrace.record("blocked ad host=$host url=$url")
                if (shortLinkTabId != null) {
                    scope.launch(Dispatchers.Main) {
                        closeTab(shortLinkTabId)
                    }
                }
                return true
            }

            extractShortXLinksFastForward(url)
                ?.takeIf { it != url }
                ?.let { target ->
                    Log.d(TAG, "  -> ShortXLinks fast-forward: $target")
                    ShortLinkTrace.record("fast-forward from=$url to=$target")
                    view.loadUrl(target)
                    return true
                }
        }

        // Let WebView load normal web pages.
        if (isHttpWebUrl(url)) {
            Log.d(TAG, "  -> allow (http/https)")
            ShortLinkTrace.record("allow http/https url=$url")
            return false
        }

        // Shorteners and ad/interstitial pages often attempt to hand navigation
        // to Chrome through intent:// or googlechrome://. Prefer the embedded
        // HTTP(S) destination so the redirect chain remains inside ABDM and
        // keeps the current cookies/session.
        extractHttpWebFallback(url)?.let { webTarget ->
            Log.d(TAG, "  -> extracted fallback: $webTarget")
            ShortLinkTrace.record("extracted fallback from=$url to=$webTarget")
            view.loadUrl(webTarget)
            return true
        }

        Log.d(TAG, "  -> external intent")
        ShortLinkTrace.record("external intent url=$url")

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
            Log.e(TAG, "Intent launch failed: $url", e)
            ShortLinkTrace.record("Intent launch failed url=$url error=${e.message}")
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
        val popupMessage = "onCreateWindow isDialog=$isDialog" +
            " isUserGesture=$isUserGesture" +
            " opener=${view?.url}"
        Log.d(TAG, popupMessage)
        ShortLinkTrace.record(popupMessage)
        if (view == null) return false

        val openerTabId = (view as? ABDMWebView)?.tabId
        val shortLinkMode = ShortLinkTrace.isTabActive(openerTabId)

        val transport = (resultMsg?.obj as? WebView.WebViewTransport) ?: return false
        val newTab = browserComponent.newTab(
            id = UUID.randomUUID().toString(),
            switch = !shortLinkMode,
            url = null,
            openedBy = openerTabId
        )
        if (shortLinkMode) {
            ShortLinkTrace.registerTab(newTab.tabId)
            ShortLinkTrace.record("popup opened hidden tab=${newTab.tabId} opener=${view.url}")
        }
        val newWebView = createWebViewHolder(newTab).activate(view.context)
        newWebView.openedBy = view.originalUrl ?: view.url
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        super.onReceivedTitle(view, title)
        Log.d(TAG, "onReceivedTitle title=$title url=${view.url}")
        ShortLinkTrace.record("onReceivedTitle title=$title url=${view.url}")
    }
}

class ABDMWebView(
    context: Context,
) : WebView(context) {
    var openedBy: String? = null
    var tabId: String? = null
}

/**
 * Strips the Android WebView markers from the default User-Agent string.
 *
 * The default WebView UA looks like:
 *   Mozilla/5.0 (Linux; Android 14; … Build/…; **wv**) AppleWebKit/537.36
 *   (KHTML, like Gecko) **Version/4.0** Chrome/130.0.0.0 Mobile Safari/537.36
 *
 * Link shorteners (ShortXLinks / AdLinkFly) test `navigator.userAgent` for
 * the `; wv)` and `Version/4.0` tokens and display a "Redirecting to Chrome"
 * interstitial when they detect an embedded WebView.
 *
 * This function produces a User-Agent that looks like standard Chrome Mobile:
 *   Mozilla/5.0 (Linux; Android 14; …) AppleWebKit/537.36 (KHTML, like Gecko)
 *   Chrome/130.0.0.0 Mobile Safari/537.36
 */
internal fun normalizeWebViewUserAgent(ua: String): String {
    var result = ua
    // Remove "; wv" (with any surrounding whitespace before the closing paren)
    result = result.replace(Regex("""\s*;\s*wv\b"""), "")
    // Remove "Version/4.0 " (legacy WebView identifier)
    result = result.replace(Regex("""Version/4\.0\s*"""), "")
    // Collapse any resulting double-spaces
    result = result.replace("  ", " ")
    return result.trim()
}

/**
 * Logs whether cookies exist for the given URL. Does NOT log values.
 */
private class ShortLinkJavascriptBridge {
    @JavascriptInterface
    fun log(message: String?) {
        if (!message.isNullOrBlank()) {
            ShortLinkTrace.record("js: " + message.take(500))
        }
    }
}

private fun injectShortLinkAutomation(view: WebView, rawUrl: String) {
    val host = runCatching {
        Uri.parse(rawUrl).host.orEmpty().lowercase()
    }.getOrDefault("")

    when {
        host.matches(Regex("""mtc\d+\..+""")) -> {
            view.evaluateJavascript(shortLinkMtcAutomationScript(), null)
        }

        host == "shortxlinks.com" || host.endsWith(".shortxlinks.com") -> {
            view.evaluateJavascript(shortLinksGoHookScript(), null)
        }

        host == "devuploads.com" || host.endsWith(".devuploads.com") -> {
            view.evaluateJavascript(devUploadsAutomationScript(), null)
        }
    }
}

private fun shortLinkMtcAutomationScript(): String = """
(() => {
  if (window.__abdmMtcAutomationInstalled) return;
  window.__abdmMtcAutomationInstalled = true;

  const log = (m) => {
    try { window.ABDMShortLink && window.ABDMShortLink.log(String(m)); } catch (_) {}
  };

  const decode = (s) => {
    try { return JSON.parse(atob(s)); } catch (_) { return null; }
  };

  const overlay = (() => {
    const root = document.createElement('div');
    root.id = 'abdm-shortlink-progress';
    root.style.cssText = [
      'position:fixed','inset:0','z-index:2147483647',
      'background:#111','color:#fff','display:flex',
      'align-items:center','justify-content:center',
      'font-family:sans-serif','text-align:center','padding:24px'
    ].join(';');
    const box = document.createElement('div');
    box.style.cssText = 'max-width:420px;font-size:18px;line-height:1.45';
    box.innerHTML = '<b>ABDM está preparando el enlace</b><div id="abdm-countdown" style="margin-top:12px;font-size:28px"></div><div style="margin-top:10px;font-size:13px;opacity:.7">Esperando el tiempo requerido por ShortXLinks…</div>';
    root.appendChild(box);
    return root;
  })();

  const showOverlay = (seconds) => {
    if (!document.body) return;
    if (!document.getElementById('abdm-shortlink-progress')) {
      document.body.appendChild(overlay);
    }
    const label = document.getElementById('abdm-countdown');
    if (label) label.textContent = Math.max(0, seconds) + ' s';
  };

  const redirectSafelinkParam = () => {
    try {
      const encoded = new URL(location.href).searchParams.get('safelink_redirect');
      if (!encoded) return false;
      const decoded = decode(encoded);
      if (decoded && /^https?:\/\//i.test(decoded.safelink || '')) {
        log('completed safelink wrapper -> ' + decoded.safelink);
        location.replace(decoded.safelink);
        return true;
      }
    } catch (_) {}
    return false;
  };

  if (redirectSafelinkParam()) return;

  let tries = 0;
  const probe = setInterval(() => {
    tries++;
    const node = document.getElementById('value') ||
      document.querySelector('input[name="newwpsafelink"]');
    const encoded = (node && node.value) || window.ad_mem;

    if (!encoded) {
      if (tries >= 80) {
        clearInterval(probe);
        log('mtc payload not found after 40s');
      }
      return;
    }

    const data = decode(encoded);
    if (!data || !data.linkr) return;

    clearInterval(probe);

    let target = String(data.linkr);
    try {
      const inner = new URL(target).searchParams.get('safelink_redirect');
      const decodedInner = inner && decode(inner);
      if (decodedInner && /^https?:\/\//i.test(decodedInner.safelink || '')) {
        target = decodedInner.safelink;
      }
    } catch (_) {}

    let seconds = Number.parseInt(data.delay, 10);
    if (!Number.isFinite(seconds)) seconds = 25;
    seconds = Math.max(0, seconds) + 2;

    log('mtc wait=' + seconds + 's target=' + target);
    showOverlay(seconds);

    const timer = setInterval(() => {
      seconds--;
      showOverlay(seconds);
      if (seconds < 0) {
        clearInterval(timer);
        log('mtc wait complete -> ' + target);
        location.href = target;
      }
    }, 1000);
  }, 500);
})();
""".trimIndent()

private fun shortLinksGoHookScript(): String = """
(() => {
  if (window.__abdmShortLinksHookInstalled) return;
  window.__abdmShortLinksHookInstalled = true;

  const log = (m) => {
    try { window.ABDMShortLink && window.ABDMShortLink.log(String(m)); } catch (_) {}
  };

  const maybeFollow = (url, text) => {
    if (!String(url || '').includes('/links/go')) return;
    try {
      const data = JSON.parse(text);
      if (data && /^https?:\/\//i.test(data.url || '')) {
        log('/links/go -> ' + data.url);
        location.href = data.url;
      }
    } catch (_) {}
  };

  try {
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url) {
      this.__abdmRequestUrl = String(url || '');
      return originalOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function() {
      if (String(this.__abdmRequestUrl || '').includes('/links/go')) {
        this.addEventListener('load', () => {
          maybeFollow(this.__abdmRequestUrl, this.responseText || '');
        }, { once: true });
      }
      return originalSend.apply(this, arguments);
    };
  } catch (_) {}

  try {
    const originalFetch = window.fetch;
    window.fetch = async function() {
      const response = await originalFetch.apply(this, arguments);
      try {
        const requestUrl = typeof arguments[0] === 'string'
          ? arguments[0]
          : (arguments[0] && arguments[0].url) || '';
        if (String(requestUrl).includes('/links/go')) {
          const clone = response.clone();
          maybeFollow(requestUrl, await clone.text());
        }
      } catch (_) {}
      return response;
    };
  } catch (_) {}

  const isVisible = (el) => {
    if (!el) return false;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  };

  const hasHumanVerification = () => {
    try {
      const bodyText = String(document.body?.innerText || '').toLowerCase();
      if (/\bverified\b|\bverificado\b/.test(bodyText)) return false;

      const selectors = [
        '.g-recaptcha',
        'iframe[src*="recaptcha"]',
        'iframe[src*="hcaptcha"]',
        'iframe[src*="turnstile"]',
        '[data-sitekey]'
      ];
      return selectors.some((selector) =>
        Array.from(document.querySelectorAll(selector)).some(isVisible)
      );
    } catch (_) {
      return false;
    }
  };

  const isVisibleButton = (el) => {
    if (!el || el.disabled) return false;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  };

  const getLabel = (el) => String(
    el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || ''
  ).replace(/\s+/g, ' ').trim();

  let clickChecks = 0;
  const clickTimer = setInterval(() => {
    clickChecks++;

    if (hasHumanVerification()) {
      clearInterval(clickTimer);
      log('human verification detected; waiting for user');
      return;
    }

    const candidates = Array.from(document.querySelectorAll(
      'a.get-link, #btn-get-link, #get-link-btn, button#go-submit, button, a.btn, input[type="submit"]'
    ));

    const button = candidates.find((el) => {
      if (!isVisibleButton(el)) return false;
      const label = getLabel(el);
      return /^(obtener\s+v[ií]nculo|get\s*link|continuar|continue)$/i.test(label);
    });

    if (button) {
      clearInterval(clickTimer);
      log('auto-click ShortXLinks button: ' + getLabel(button));
      button.click();
      return;
    }

    if (clickChecks >= 120) {
      clearInterval(clickTimer);
      log('ShortXLinks button not found after 90s');
    }
  }, 750);

  log('ShortXLinks /links/go hook ready');
})();
""".trimIndent()

private fun devUploadsAutomationScript(): String = """
(() => {
  if (window.__abdmDevUploadsAutomationInstalled) return;
  window.__abdmDevUploadsAutomationInstalled = true;

  const log = (m) => {
    try { window.ABDMShortLink && window.ABDMShortLink.log(String(m)); } catch (_) {}
  };

  const isVisible = (el) => {
    if (!el || el.disabled) return false;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  };

  const hasHumanVerification = () => {
    try {
      const bodyText = String(document.body?.innerText || '').toLowerCase();

      // DevUploads keeps challenge-related markup in the DOM even after the
      // user/session has already been verified. Do not treat stale/hidden
      // captcha nodes as an active challenge.
      if (/\bverified\b|\bverificado\b/.test(bodyText)) return false;

      const selectors = [
        '.g-recaptcha',
        'iframe[src*="recaptcha"]',
        'iframe[src*="hcaptcha"]',
        'iframe[src*="turnstile"]',
        '[data-sitekey]'
      ];
      return selectors.some((selector) =>
        Array.from(document.querySelectorAll(selector)).some(isVisible)
      );
    } catch (_) {
      return false;
    }
  };

  const labelOf = (el) => String(
    el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || ''
  ).replace(/\s+/g, ' ').trim();

  const overlay = document.createElement('div');
  overlay.id = 'abdm-devuploads-progress';
  overlay.style.cssText = [
    'position:fixed','inset:0','z-index:2147483647',
    'background:#111','color:#fff','display:flex',
    'align-items:center','justify-content:center',
    'font-family:sans-serif','text-align:center','padding:24px'
  ].join(';');
  overlay.innerHTML = '<div style="max-width:420px;font-size:18px;line-height:1.45"><b>ABDM está preparando la descarga</b><div style="margin-top:10px;font-size:13px;opacity:.7">Esperando el botón gratuito de DevUploads…</div></div>';

  if (document.body && !hasHumanVerification()) {
    document.body.appendChild(overlay);
  }

  let checks = 0;
  let verifiedLogged = false;
  const timer = setInterval(() => {
    checks++;

    const bodyText = String(document.body?.innerText || '').toLowerCase();
    if (!verifiedLogged && /\bverified\b|\bverificado\b/.test(bodyText)) {
      verifiedLogged = true;
      log('DevUploads verified state observed; continuing automation');
    }

    if (hasHumanVerification()) {
      clearInterval(timer);
      overlay.remove();
      log('DevUploads human verification detected; waiting for user');
      return;
    }

    const candidates = Array.from(document.querySelectorAll(
      'button, a, input[type="submit"], input[type="button"], [role="button"], .btn, [onclick]'
    ));

    const freeButton = candidates.find((el) => {
      if (!isVisible(el)) return false;
      const label = labelOf(el);
      if (/premium|prima|sponsor|advert|anuncio|ads?/i.test(label)) return false;
      return /(free\s*download|liberta\s+descarga|descarga\s+gratis|download\s+free|continue|continuar)/i.test(label);
    });

    if (freeButton) {
      const label = labelOf(freeButton);
      const disabledByAttribute =
        freeButton.disabled === true ||
        freeButton.getAttribute('aria-disabled') === 'true' ||
        freeButton.classList.contains('disabled');

      if (disabledByAttribute) {
        if (checks % 10 === 0) {
          log('DevUploads free button visible but still disabled: ' + label);
        }
      } else {
        clearInterval(timer);
        log('auto-click DevUploads button: ' + label);
        overlay.remove();

        const clickable =
          freeButton.closest('button, a, [role="button"], .btn, [onclick]') ||
          freeButton;
        clickable.click();
        return;
      }
    } else if (checks % 20 === 0) {
      const labels = candidates
        .filter(isVisible)
        .map(labelOf)
        .filter(Boolean)
        .filter((label) => label.length <= 80)
        .slice(0, 12);
      log('DevUploads visible controls: ' + labels.join(' | '));
    }

    if (checks >= 240) {
      clearInterval(timer);
      overlay.remove();
      log('DevUploads free/continue button not actionable after 120s');
    }
  }, 500);

  log('DevUploads automation ready');
})();
""".trimIndent()

private fun isKnownShortLinkAdHost(host: String): Boolean {
    if (host.isBlank()) return false
    return host == "ndcertainlywhen.com" ||
        host.endsWith(".ndcertainlywhen.com") ||
        host == "smartfeecalculator.com" ||
        host.endsWith(".smartfeecalculator.com") ||
        host == "control.kochava.com" ||
        host.endsWith(".kochava.com") ||
        host.endsWith(".x9m.workers.dev")
}

private fun logCookiePresence(url: String?) {
    if (url == null) return
    try {
        val cookie = CookieManager.getInstance().getCookie(url)
        val present = !cookie.isNullOrBlank()
        val count = if (present) cookie!!.split(";").size else 0
        Log.d(TAG, "cookies for $url present=$present count=$count")
        ShortLinkTrace.record("cookies for $url present=$present count=$count")
    } catch (e: Exception) {
        Log.d(TAG, "cookies for $url error=${e.message}")
        ShortLinkTrace.record("cookies for $url error=${e.message}")
    }
}
