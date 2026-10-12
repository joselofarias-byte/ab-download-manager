package com.abdownloadmanager.android.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

@Serializable
data class BrowserRequest(
    val items: List<BrowserItem>,
    val options: BrowserOptions = BrowserOptions(),
) {
    companion object {
        const val MAX_BYTES = 128 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(body: String): BrowserRequest {
            require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            return json.decodeFromString<BrowserRequest>(body).also { request ->
                require(request.items.size in 1..100)
                request.items.forEach { item ->
                    require(item.type == "http" || item.type == "hls")
                    require(isHttpUrl(item.link))
                    require(item.downloadPage == null || isHttpUrl(item.downloadPage))
                    require((item.suggestedName?.length ?: 0) <= 512)
                    require(item.suggestedName == null || ('\u0000' !in item.suggestedName &&
                        item.suggestedName.trim() !in setOf(".", "..")))
                    require((item.headers?.size ?: 0) <= 64)
                    item.headers?.forEach { (key, value) ->
                        require(key.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]{1,128}")))
                        require(value.length <= 8192 && '\r' !in value && '\n' !in value && '\u0000' !in value)
                    }
                }
            }
        }

        private fun isHttpUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 16384 && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

        fun authorized(expected: String, received: String?): Boolean =
            expected.isNotBlank() && received != null && MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8), received.toByteArray(Charsets.UTF_8)
            )
    }
}

@Serializable
data class BrowserItem(
    val link: String,
    val type: String = "http",
    val headers: Map<String, String>? = null,
    val downloadPage: String? = null,
    val suggestedName: String? = null,
)

@Serializable
data class BrowserOptions(val silentAdd: Boolean = false, val silentStart: Boolean = false)
