package com.abdownloadmanager.android.pages.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight on-device trace for ShortXLinks physical tests.
 *
 * The active session is written directly to:
 * Download/ABDownloadManager/Diagnostics/
 *
 * This deliberately records navigation metadata only. Cookie values are never
 * persisted here.
 */
object ShortLinkTrace {
    private const val DIRECTORY = "ABDownloadManager/Diagnostics"

    private var appContext: Context? = null
    private var outputUri: Uri? = null
    private var fileName: String? = null
    private val activeTabIds = linkedSetOf<String>()

    @Synchronized
    fun start(context: Context, urls: List<String>) {
        if (urls.none(::isShortLinkUrl)) return

        appContext = context.applicationContext
        activeTabIds.clear()
        fileName = "ABDM-ShortXLinks-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            ".txt"
        outputUri = createOutput(appContext!!, fileName!!)

        record("TRACE_START")
        record("device_api=${Build.VERSION.SDK_INT}")
        record("urls=" + urls.joinToString(" | "))
        record("destination=" + destinationDescription())
    }

    @Synchronized
    fun registerTab(tabId: String) {
        activeTabIds += tabId
        record("registerTab id=$tabId")
    }

    @Synchronized
    fun isTabActive(tabId: String?): Boolean {
        return tabId != null && tabId in activeTabIds
    }

    @Synchronized
    fun unregisterTab(tabId: String?) {
        if (tabId != null && activeTabIds.remove(tabId)) {
            record("unregisterTab id=$tabId")
        }
    }


    @Synchronized
    fun record(message: String) {
        val context = appContext ?: return
        val uri = outputUri ?: return
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$time] $message\n"

        runCatching {
            if (uri.scheme == "content") {
                context.contentResolver
                    .openOutputStream(uri, "wa")
                    ?.bufferedWriter()
                    ?.use { it.write(line) }
                    ?: error("No se pudo abrir el archivo de diagnóstico")
            } else {
                val path = requireNotNull(uri.path)
                File(path).appendText(line)
            }
        }
    }

    fun isShortLinkUrl(url: String): Boolean {
        return runCatching {
            val host = Uri.parse(url).host?.lowercase(Locale.US).orEmpty()
            host == "shortxlinks.in" ||
                host.endsWith(".shortxlinks.in") ||
                host == "shortxlinks.com" ||
                host.endsWith(".shortxlinks.com")
        }.getOrDefault(false)
    }

    fun destinationDescription(): String? {
        val name = fileName ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Download/$DIRECTORY/$name"
        } else {
            appContext
                ?.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?.resolve(name)
                ?.absolutePath
        }
    }

    private fun createOutput(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DIRECTORY,
                )
            }
            return context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            )
        }

        val dir = context
            .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: return null
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        if (!file.exists()) file.createNewFile()
        return Uri.fromFile(file)
    }
}
