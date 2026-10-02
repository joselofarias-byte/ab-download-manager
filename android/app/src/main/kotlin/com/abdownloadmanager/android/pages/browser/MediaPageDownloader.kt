package com.abdownloadmanager.android.pages.browser

import android.content.Context
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Experimental page/platform media engine backed by yt-dlp.
 *
 * It resolves the current page URL instead of relying on a direct media URL,
 * which enables support for many video/audio platforms handled by yt-dlp.
 *
 * DRM-protected streams are intentionally out of scope.
 */
class MediaPageDownloader(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var initialized = false

    @Volatile
    private var ytDlpUpdateChecked = false

    suspend fun downloadBestVideo(
        pageUrl: String,
    ): Result<MediaPageDownloadResult> = download(
        pageUrl = pageUrl,
        mode = MediaPageDownloadMode.BEST_VIDEO,
    )

    suspend fun downloadAudioOnly(
        pageUrl: String,
    ): Result<MediaPageDownloadResult> = download(
        pageUrl = pageUrl,
        mode = MediaPageDownloadMode.AUDIO_ONLY,
    )

    suspend fun downloadBestVideoWithSpanishCaptions(
        pageUrl: String,
    ): Result<MediaPageDownloadResult> = download(
        pageUrl = pageUrl,
        mode = MediaPageDownloadMode.BEST_VIDEO_SPANISH_CAPTIONS,
    )

    suspend fun downloadAllCaptions(
        pageUrl: String,
    ): Result<MediaPageDownloadResult> = download(
        pageUrl = pageUrl,
        mode = MediaPageDownloadMode.ALL_CAPTIONS,
    )

    private suspend fun download(
        pageUrl: String,
        mode: MediaPageDownloadMode,
    ): Result<MediaPageDownloadResult> = withContext(ioDispatcher) {
        runCatching {
            ensureInitialized()

            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ABDownloadManager/Media",
            ).apply {
                mkdirs()
            }

            val request = YoutubeDLRequest(pageUrl)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--no-mtime")
                .addOption("--restrict-filenames")
                .addOption(
                    "-o",
                    File(outputDir, "%(title).180B.%(ext)s").absolutePath,
                )

            addBrowserSessionHeaders(request, pageUrl)

            when (mode) {
                MediaPageDownloadMode.BEST_VIDEO -> {
                    request
                        .addOption(
                            "-f",
                            "bestvideo*+bestaudio/best",
                        )
                        .addOption("--merge-output-format", "mp4")
                }

                MediaPageDownloadMode.AUDIO_ONLY -> {
                    request
                        .addOption("-f", "bestaudio/best")
                        .addOption("-x")
                        .addOption("--audio-format", "mp3")
                        .addOption("--audio-quality", "0")
                }

                MediaPageDownloadMode.BEST_VIDEO_SPANISH_CAPTIONS -> {
                    request
                        .addOption(
                            "-f",
                            "bestvideo*+bestaudio/best",
                        )
                        .addOption("--merge-output-format", "mp4")
                        .addOption("--write-subs")
                        .addOption("--write-auto-subs")
                        .addOption("--sub-langs", "es.*,es")
                        .addOption("--convert-subs", "srt")
                        .addOption("--embed-subs")
                }

                MediaPageDownloadMode.ALL_CAPTIONS -> {
                    request
                        .addOption("--skip-download")
                        .addOption("--write-subs")
                        .addOption("--write-auto-subs")
                        .addOption("--sub-langs", "all,-live_chat")
                        .addOption("--convert-subs", "srt")
                }
            }

            val processId = "abdm-media-" + UUID.randomUUID().toString()
            val response = YoutubeDL.getInstance().execute(
                request,
                processId,
            ) { _, _, _ ->
                // Progress UI will be wired in the next wave. Execution itself
                // already runs off the main thread.
            }

            if (response.exitCode != 0) {
                error(
                    response.err.ifBlank {
                        "yt-dlp exited with code ${response.exitCode}"
                    }
                )
            }

            MediaPageDownloadResult(
                pageUrl = pageUrl,
                mode = mode,
                outputDirectory = outputDir.absolutePath,
            )
        }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        val appContext = context.applicationContext
        val youtubeDL = YoutubeDL.getInstance()

        youtubeDL.init(appContext)
        FFmpeg.getInstance().init(appContext)

        if (!ytDlpUpdateChecked) {
            ytDlpUpdateChecked = true
            val before = runCatching {
                youtubeDL.versionName(appContext)
            }.getOrNull()

            runCatching {
                val status = youtubeDL.updateYoutubeDL(
                    appContext,
                    YoutubeDL.UpdateChannel._STABLE,
                )
                val after = runCatching {
                    youtubeDL.versionName(appContext)
                }.getOrNull()

                Log.i(
                    TAG,
                    "yt-dlp stable update check status=$status before=$before after=$after",
                )
            }.onFailure { error ->
                // Keep the bundled yt-dlp as an offline fallback. A failed
                // update check must not make media downloading unavailable.
                Log.w(
                    TAG,
                    "yt-dlp stable update check failed; using bundled version=$before",
                    error,
                )
            }
        }

        initialized = true
    }

    private fun addBrowserSessionHeaders(
        request: YoutubeDLRequest,
        pageUrl: String,
    ) {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        if (userAgent.isNotBlank()) {
            request.addOption("--user-agent", userAgent)
        }

        request.addOption("--referer", pageUrl)

        CookieManager.getInstance()
            .getCookie(pageUrl)
            ?.takeIf { it.isNotBlank() }
            ?.let { cookie ->
                request.addOption("--add-headers", "Cookie:$cookie")
            }
    }
}

enum class MediaPageDownloadMode {
    BEST_VIDEO,
    AUDIO_ONLY,
    BEST_VIDEO_SPANISH_CAPTIONS,
    ALL_CAPTIONS,
}

data class MediaPageDownloadResult(
    val pageUrl: String,
    val mode: MediaPageDownloadMode,
    val outputDirectory: String,
)

private const val TAG = "ABDM_MEDIA_PAGE"
