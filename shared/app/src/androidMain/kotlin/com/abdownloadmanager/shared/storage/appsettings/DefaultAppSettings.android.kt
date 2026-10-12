package com.abdownloadmanager.shared.storage.appsettings

actual object PlatformDefaultSettings : DefaultAppSettings() {
    override val apiEnabled: Boolean get() = false
    override val apiAuthEnabled: Boolean get() = true
    override val useSparseFileAllocation: Boolean get() = false

    val browserIconInLauncher: Boolean get() = false
}
