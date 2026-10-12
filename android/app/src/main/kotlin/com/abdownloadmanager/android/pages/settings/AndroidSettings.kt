package com.abdownloadmanager.android.pages.settings

import com.abdownloadmanager.android.pages.onboarding.permissions.ABDMPermissions
import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.android.ui.configurable.android.item.PermissionConfigurable
import com.abdownloadmanager.android.util.pagemanager.PermissionsPageManager
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.ui.configurable.item.BooleanConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.NavigatableConfigurable
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.flow.MutableStateFlow


object AndroidSettings {
    fun browserBridgePort(settings: AppSettingsStorage) =
        com.abdownloadmanager.shared.ui.configurable.item.IntConfigurable(
            title = Res.string.settings_browser_integration_server_port.asStringSource(),
            description = Res.string.settings_browser_integration_server_port_description.asStringSource(),
            backedBy = settings.apiPort,
            describe = { it.toString().asStringSource() },
            range = 1024..65000,
        )

    fun copyBrowserBridgeKey(settings: AppSettingsStorage) = NavigatableConfigurable(
        title = Res.string.copy.asStringSource(),
        description = Res.string.settings_api_auth_key.asStringSource(),
        onRequestNavigate = {
            val context = com.abdownloadmanager.android.util.AppInfo.context
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AB DM", settings.apiAuthKey.value))
        },
    )

    fun browserInbox(bridge: com.abdownloadmanager.android.integration.AndroidBrowserBridge) = NavigatableConfigurable(
        title = com.abdownloadmanager.android.util.AppInfo.context.getString(com.abdownloadmanager.android.R.string.browser_bridge_inbox).asStringSource(),
        description = Res.string.settings_browser_integration.asStringSource(),
        onRequestNavigate = bridge::openOldest,
    )

    fun permissionSettings(
        permissionsPageManager: PermissionsPageManager
    ): NavigatableConfigurable {
        return NavigatableConfigurable(
            title = Res.string.permissions.asStringSource(),
            description = "".asStringSource(),
            onRequestNavigate = {
                permissionsPageManager.openPermissionsPage(false)
            },
        )
    }

    fun ignoreBatteryOptimizations(): PermissionConfigurable {
        val permission = ABDMPermissions.BatteryOptimizationPermission
        return PermissionConfigurable(
            title = permission.title,
            description = permission.description,
            backedBy = MutableStateFlow(permission),
        )
    }

    fun browserIconInLauncher(
        appSettingsStorage: AppSettingsStorage
    ): BooleanConfigurable {
        return BooleanConfigurable(
            title = Res.string.settings_browser_in_launcher.asStringSource(),
            description = Res.string.settings_browser_in_launcher_description.asStringSource(),
            backedBy = appSettingsStorage.browserIconInLauncher,
            describe = {
                if (it) {
                    Res.string.enabled
                } else {
                    Res.string.disabled
                }.asStringSource()
            }
        )
    }
}
