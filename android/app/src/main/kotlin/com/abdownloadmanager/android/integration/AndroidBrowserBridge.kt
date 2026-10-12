package com.abdownloadmanager.android.integration

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.abdownloadmanager.android.R
import com.abdownloadmanager.android.pages.add.multiple.AddMultiDownloadActivity
import com.abdownloadmanager.android.pages.add.single.AddSingleDownloadActivity
import com.abdownloadmanager.android.service.DownloadSystemService
import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.android.util.ABDMAppManager
import com.abdownloadmanager.android.util.AndroidConstants
import com.abdownloadmanager.shared.downloaderinui.BasicDownloadItem
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadConfig
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.util.FilenameFixer
import com.abdownloadmanager.shared.util.DownloadSystem
import com.abdownloadmanager.resources.Res
import ir.amirab.util.compose.asStringSource
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.utils.OnDuplicateStrategy
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

class AndroidBrowserBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettingsStorage,
    private val appManager: ABDMAppManager,
    private val downloadSystem: DownloadSystem,
    private val registry: DownloaderInUiRegistry,
) {
    val inbox = BrowserInbox(File(context.filesDir, "browser-inbox"))
    val status = MutableStateFlow(context.getString(R.string.browser_bridge_stopped))
    private val worker = Mutex()
    private var bootJob: Job? = null

    fun boot() {
        if (bootJob != null) return
        bootJob = scope.launch(Dispatchers.IO) {
            combine(settings.apiEnabled, settings.apiPort, settings.apiAuthKey,
                DownloadSystemService.isServiceRunningFlow) { enabled, port, key, running ->
                BridgeSettings(enabled && running, port, key)
            }.distinctUntilChanged().collectLatest { config ->
                if (!config.enabled) {
                    status.value = context.getString(R.string.browser_bridge_stopped)
                    return@collectLatest
                }
                if (config.port !in 1024..65535 || config.key.isBlank()) {
                    status.value = context.getString(R.string.browser_bridge_invalid)
                    return@collectLatest
                }
                // Existing installs may have the old desktop default. Android always requires a key.
                settings.apiAuthEnabled.value = true
                val server = embeddedServer(CIO, host = "127.0.0.1", port = config.port) {
                    browserBridgeRoutes(config.key) { body ->
                        val (id, fresh) = inbox.accept(body)
                        if (fresh) scope.launch(Dispatchers.IO) { deliver(id) }
                    }
                }
                try {
                    server.start(wait = false)
                    status.value = "127.0.0.1:${config.port}"
                    inbox.ids().forEach { id -> scope.launch(Dispatchers.IO) { deliver(id) } }
                    awaitCancellation()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status.value = context.getString(R.string.browser_bridge_failed)
                } finally {
                    server.stop(100, 500)
                }
            }
        }
    }

    private suspend fun deliver(id: String) = worker.withLock {
        val request = runCatching { inbox.read(id) }.getOrNull() ?: return@withLock
        try {
            if (request.options.silentAdd) {
                appManager.awaitDownloadEngineBoot()
                val props = request.items.distinctBy { it.link }.map { item ->
                    val source = item.toUiProps()
                    val downloader = registry.getDownloaderOf(source.credentials) ?: error("Unsupported download")
                    val name = FilenameFixer.fix(item.suggestedName?.takeIf { it.isNotBlank() }
                        ?: item.link.substringBefore('?').substringAfterLast('/').ifBlank { "download" })
                    NewDownloadItemProps(
                        downloadItem = downloader.createBareDownloadItem(source.credentials,
                            BasicDownloadItem(settings.defaultDownloadFolder.value, name)),
                        onDuplicateStrategy = OnDuplicateStrategy.default(),
                        extraConfig = null,
                        context = EmptyContext,
                    )
                }
                // Import as a batch with the engine's existing duplicate handling.
                val ids = appManager.addDownloads(props, null, null).await()
                if (request.options.silentStart) ids.forEach { downloadSystem.userManualResume(it) }
                inbox.remove(id)
            } else {
                showNotification(id, request.items.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retain the request and let the user import through the normal dialog.
            showNotification(id, request.items.size)
        }
    }

    private fun showNotification(id: String, count: Int) {
        // Returning an error here would make the extension fall back to its browser download.
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return // Retained on disk and offered in Settings through the inbox action.
        }
        val intent = Intent(context, BrowserImportActivity::class.java).putExtra("requestId", id)
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, AndroidConstants.NOTIFICATION_DOWNLOAD_CHANEL_ID)
            .setSmallIcon(R.drawable.ic_monochrome)
            .setContentTitle(Res.string.settings_browser_integration.asStringSource().getString())
            .setContentText("$count · " + Res.string.add_download.asStringSource().getString())
            .setContentIntent(pending).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(id, 2, notification)
    }

    fun openOldest() {
        inbox.ids().firstOrNull()?.let { id ->
            context.startActivity(Intent(context, BrowserImportActivity::class.java)
                .putExtra("requestId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun createImportIntent(id: String, serialization: Json): Intent {
        val request = inbox.read(id)
        val items = request.items.distinctBy { it.link }.map { it.toUiProps() }
        return if (items.size == 1) {
            AddSingleDownloadActivity.createIntent(context,
                AddDownloadConfig.SingleAddConfig(items.first(), ImportOptions(), id), serialization)
        } else {
            AddMultiDownloadActivity.createIntent(context,
                AddDownloadConfig.MultipleAddConfig(items, ImportOptions(), id), serialization)
        }
    }

    private data class BridgeSettings(val enabled: Boolean, val port: Int, val key: String)
}

private fun BrowserItem.toUiProps(): AddDownloadCredentialsInUiProps {
    val safeHeaders = headers?.filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding") }
    val credentials = if (type == "hls") HLSDownloadCredentials(link = link, headers = safeHeaders, downloadPage = downloadPage)
        else HttpDownloadCredentials(link = link, headers = safeHeaders, downloadPage = downloadPage)
    return AddDownloadCredentialsInUiProps(credentials,
        AddDownloadCredentialsInUiProps.Configs(suggestedName))
}
