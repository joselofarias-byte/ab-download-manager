package com.abdownloadmanager.android.util.pagemanager

interface IBrowserPageManager {
    fun openBrowser(url: String?)
    fun openBrowser(urls: List<String>)
}
