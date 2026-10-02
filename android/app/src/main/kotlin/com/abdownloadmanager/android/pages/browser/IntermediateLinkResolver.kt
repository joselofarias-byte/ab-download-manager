package com.abdownloadmanager.android.pages.browser

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Resolves responses that Android WebView reports as downloads even though they
 * are actually HTML interstitial/redirect pages.
 *
 * The resolver intentionally does not try to defeat challenges or bypass access
 * controls. It only follows ordinary HTTP redirects/Refresh headers, preserves
 * the browser session, and gives HTML back to the WebView so JavaScript/meta
 * redirects can continue in the normal browser sandbox.
 */
class IntermediateLinkResolver(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRedirects: Int = 12,
) {
    suspend fun resolve(
        url: String,
        userAgent: String?,
        referer: String?,
    ): Result<IntermediateLinkResolution> = withContext(ioDispatcher) {
        runCatching {
            resolveBlocking(
                initialUrl = url,
                userAgent = userAgent,
                initialReferer = referer,
            )
        }
    }

    private fun resolveBlocking(
        initialUrl: String,
        userAgent: String?,
        initialReferer: String?,
    ): IntermediateLinkResolution {
        var currentUrl = initialUrl
        var currentReferer = initialReferer

        repeat(maxRedirects + 1) { hop ->
            if (!isHttpWebUrl(currentUrl)) {
                return IntermediateLinkResolution.Navigate(currentUrl)
            }

            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,video/*,audio/*,*/*;q=0.8",
                )
                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                if (!currentReferer.isNullOrBlank() && isHttpWebUrl(currentReferer)) {
                    setRequestProperty("Referer", currentReferer)
                }
                CookieManager.getInstance()
                    .getCookie(currentUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }

            try {
                val responseCode = connection.responseCode
                copyResponseCookiesToWebView(currentUrl, connection)

                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: error("Redirect HTTP $responseCode sin cabecera Location")

                    val target = resolveTarget(currentUrl, location)
                    if (!isHttpWebUrl(target)) {
                        return IntermediateLinkResolution.Navigate(target)
                    }

                    currentReferer = currentUrl
                    currentUrl = target
                    return@repeat
                }

                parseRefreshTarget(connection.getHeaderField("Refresh"))
                    ?.let { refreshTarget ->
                        val target = resolveTarget(currentUrl, refreshTarget)
                        if (!isHttpWebUrl(target)) {
                            return IntermediateLinkResolution.Navigate(target)
                        }
                        currentReferer = currentUrl
                        currentUrl = target
                        return@repeat
                    }

                val contentType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()

                val contentDisposition = connection.getHeaderField("Content-Disposition").orEmpty()
                val looksLikeHtml =
                    contentType == "text/html" ||
                        contentType == "application/xhtml+xml" ||
                        contentDisposition.contains(".html", ignoreCase = true) ||
                        contentDisposition.contains(".htm", ignoreCase = true)

                if (looksLikeHtml) {
                    val stream = if (responseCode >= 400) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    } ?: error("Respuesta HTML vacía (HTTP $responseCode)")

                    val charsetName = connection.contentType
                        ?.substringAfter("charset=", missingDelimiterValue = "")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.trim('"', '\'')
                        ?.takeIf { it.isNotBlank() }

                    val responseCharset = charsetName
                        ?.let {
                            runCatching { java.nio.charset.Charset.forName(it) }
                                .getOrNull()
                        }
                        ?: Charsets.UTF_8

                    val html = stream.bufferedReader(responseCharset).use { reader ->
                        val out = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) break
                            val remaining = MAX_HTML_CHARS - total
                            if (remaining <= 0) break
                            val count = minOf(read, remaining)
                            out.append(buffer, 0, count)
                            total += count
                            if (total >= MAX_HTML_CHARS) break
                        }
                        out.toString()
                    }

                    return IntermediateLinkResolution.HtmlPage(
                        url = currentUrl,
                        html = html,
                    )
                }

                return IntermediateLinkResolution.DirectDownload(
                    url = currentUrl,
                    contentType = contentType,
                )
            } finally {
                connection.disconnect()
            }

            if (hop == maxRedirects) {
                error("Demasiadas redirecciones ($maxRedirects)")
            }
        }

        error("No se pudo resolver el enlace")
    }

    private fun copyResponseCookiesToWebView(
        url: String,
        connection: HttpURLConnection,
    ) {
        connection.headerFields.forEach { (name, values) ->
            if (name?.equals("Set-Cookie", ignoreCase = true) == true) {
                values.orEmpty()
                    .filter { it.isNotBlank() }
                    .forEach { cookie ->
                        CookieManager.getInstance().setCookie(url, cookie)
                    }
            }
        }
        CookieManager.getInstance().flush()
    }

    private fun parseRefreshTarget(refresh: String?): String? {
        if (refresh.isNullOrBlank()) return null
        val match = REFRESH_URL_REGEX.find(refresh) ?: return null
        return match.groupValues[1]
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotEmpty() }
    }

    private fun resolveTarget(baseUrl: String, target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("intent://", ignoreCase = true) ||
            trimmed.startsWith("googlechrome://", ignoreCase = true) ||
            trimmed.startsWith("googlechromes://", ignoreCase = true)
        ) {
            return trimmed
        }
        return URL(URL(baseUrl), trimmed).toString()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_HTML_CHARS = 1_500_000
        private val REFRESH_URL_REGEX =
            Regex("""(?i)(?:^|;)\s*url\s*=\s*(.+)$""")
    }
}

sealed interface IntermediateLinkResolution {
    data class HtmlPage(
        val url: String,
        val html: String,
    ) : IntermediateLinkResolution

    data class DirectDownload(
        val url: String,
        val contentType: String?,
    ) : IntermediateLinkResolution

    data class Navigate(
        val url: String,
    ) : IntermediateLinkResolution
}

fun isHttpWebUrl(url: String?): Boolean {
    return url?.startsWith("http://", ignoreCase = true) == true ||
        url?.startsWith("https://", ignoreCase = true) == true
}

/**
 * Extracts the web destination carried by common browser/deep-link schemes.
 * This keeps shortener flows inside ABDM whenever the custom URI already
 * contains a safe HTTP(S) fallback.
 */
fun extractHttpWebFallback(rawUrl: String): String? {
    if (isHttpWebUrl(rawUrl)) return rawUrl

    if (rawUrl.startsWith("intent://", ignoreCase = true)) {
        runCatching {
            Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
        }.getOrNull()?.let { intent ->
            intent.getStringExtra("browser_fallback_url")
                ?.takeIf(::isHttpWebUrl)
                ?.let { return it }

            intent.dataString
                ?.takeIf(::isHttpWebUrl)
                ?.let { return it }
        }
    }

    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme.orEmpty()

    if (scheme.equals("googlechrome", ignoreCase = true) ||
        scheme.equals("googlechromes", ignoreCase = true)
    ) {
        uri.getQueryParameter("url")
            ?.takeIf(::isHttpWebUrl)
            ?.let { return it }

        // googlechrome://https://example.com — the real URL is the
        // scheme-specific part (everything after "googlechrome://").
        val ssp = uri.schemeSpecificPart?.removePrefix("//")
        if (ssp != null && isHttpWebUrl(ssp)) {
            return ssp
        }
    }

    for (key in WEB_TARGET_QUERY_KEYS) {
        uri.getQueryParameter(key)
            ?.takeIf(::isHttpWebUrl)
            ?.let { return it }
    }

    return null
}

/**
 * Fast-forwards ShortXLinks' own safelink wrapper when the target token is
 * already present in the URL. This is only called for explicitly tagged
 * ShortXLinks test tabs.
 *
 * Examples seen on-device:
 *   ?adlinkfly=TfmfX?<token>
 *   ?safelink_redirect=<base64 JSON containing "safelink">
 */
fun extractShortXLinksFastForward(rawUrl: String): String? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null

    // IMPORTANT: do not reconstruct ?adlinkfly=... directly. ShortXLinks
    // validates the server-side wait/session state and answers "Too Early"
    // when the token is consumed before the wrapper timer has matured.
    // We only unwrap safelink_redirect here because that value already
    // represents a completed wrapper step.
    val encoded = uri.getQueryParameter("safelink_redirect")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val decoded = runCatching {
        String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull() ?: return null

    val target = runCatching {
        JSONObject(decoded).optString("safelink")
    }.getOrNull()
        ?.trim()
        ?.takeIf(::isHttpWebUrl)
        ?: return null

    val host = runCatching { Uri.parse(target).host.orEmpty().lowercase() }
        .getOrDefault("")
    if (host != "shortxlinks.com" && !host.endsWith(".shortxlinks.com")) {
        return null
    }

    return target
}

private val WEB_TARGET_QUERY_KEYS = listOf(
    "url",
    "uri",
    "link",
    "target",
    "redirect",
    "redirect_url",
)
