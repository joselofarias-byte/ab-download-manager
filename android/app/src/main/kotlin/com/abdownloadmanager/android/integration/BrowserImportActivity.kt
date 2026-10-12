package com.abdownloadmanager.android.integration

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Notification trampoline into private download dialogs; never callable by external apps. */
class BrowserImportActivity : Activity(), KoinComponent {
    private val bridge: AndroidBrowserBridge by inject()
    private val json: Json by inject()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("requestId")
        if (id != null) runCatching {
            startActivity(bridge.createImportIntent(id, json))
            bridge.inbox.remove(id)
            getSystemService(NotificationManager::class.java).cancel(id, 2)
        }
        finish()
    }
}
