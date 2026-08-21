package com.danieleze.networkshare

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.danieleze.networkshare.ui.theme.AppTheme
import java.io.File

class AppControl(application: Application) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        var isUnlocked by mutableStateOf(false)
        private const val PREFS_NAME = "app_control_prefs"
    }

    // ── State exposed to the UI ───────────────────────────────────────────────
    var isValidNetwork by mutableStateOf(true)
    var serverAddresses by mutableStateOf("")
    var serverAddressesFallback by mutableStateOf("")
    var isDiscoveryOn by mutableStateOf(false)
    var isPending by mutableStateOf(false)
    var appTheme by mutableStateOf(AppTheme.SYSTEM)
    var showLocationOffDialog by mutableStateOf(false)
    var showNetworkDialog by mutableStateOf(false)
    var showUnknownNetworkDialog by mutableStateOf(false)
    var showNotificationDialog by mutableStateOf(false)
    var pendingNotificationCheck = false
    var pendingLocationCheck = false
    var pendingStorageCheck = false

    init {
        loadAddresses()
        val savedTheme = prefs().getString("app_theme", "SYSTEM")
        appTheme = AppTheme.valueOf(savedTheme ?: "SYSTEM")
    }

    // ── Preference helpers ────────────────────────────────────────────────────
    private fun prefs() =
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveAddresses(addresses: String, fallback: String) {
        prefs().edit {
            putString("last_addresses", addresses)
            putString("last_addresses_fallback", fallback)
        }
    }

    fun loadAddresses() {
        serverAddresses = prefs().getString("last_addresses", "loading...") ?: ""
        serverAddressesFallback = prefs().getString("last_addresses_fallback", "") ?: ""
    }

    fun saveTheme(theme: AppTheme) {
        appTheme = theme
        prefs().edit { putString("app_theme", theme.name) }
    }

    fun isServiceRunning() = WebDAVService.isRunning


    // ── WebDAVService helpers ─────────────────────────────────────────────────
    val networkState: NetworkState get() = WebDAVService.networkState.value
    val isAuthEnabled: Boolean get() = WebDAVService.isAuthEnabled.value
    val username: String get() = WebDAVService.username.value
    val password: String get() = WebDAVService.password.value
    val pendingTrustSsid: String? get() = WebDAVService.pendingTrustSsid.value
    var isWaitingForHotspot
        get() = WebDAVService.isWaitingForHotspot
        set(value) {
            WebDAVService.isWaitingForHotspot = value
        }

    fun setAuthEnabled(enabled: Boolean, context: Context) {
        WebDAVService.isAuthEnabled.value = enabled
        WebDAVService.savePaths(context)
    }

    fun setUsername(value: String) {
        WebDAVService.username.value = value
    }

    fun setPassword(value: String) {
        WebDAVService.password.value = value
    }

    fun saveCredentials(context: Context) {
        WebDAVService.savePaths(context)
    }

    fun clearPendingTrustSsid() {
        WebDAVService.pendingTrustSsid.value = null
    }

    // ── FileManager helpers ───────────────────────────────────────────────────
    val scannedItems get() = FileManager.scannedItems
    val isScanning get() = FileManager.isScanning.value
    val selectedPaths get() = FileManager.selectedPaths.toList()

    fun getAvailableStorages(context: Context) =
        FileManager.getAvailableStorages(context)

    fun togglePathSelection(path: String, context: Context) {
        FileManager.toggleSelection(path)
        WebDAVService.savePaths(context)
    }

    fun getStorageLabel(path: String) = FileManager.getStorageLabel(path)

    fun requestFolderScan(path: File?) {
        FileManager.requestFolderScan(path)
    }

    fun clearScannedItems() {
        FileManager.scannedItems.clear()
    }

    fun loadStorageRoots(context: Context) {
        WebDAVService.loadPaths(context)
    }

    // ── NetworkManager helpers ────────────────────────────────────────────────
    val allowedNetworks get() = NetworkManager.allowedNetworks
    val blockedNetworks get() = NetworkManager.blockedNetworks

    fun allowNetwork(context: Context, ssid: String) {
        NetworkManager.allow(context, ssid)
    }

    fun allowNetworkOnce(ssid: String) {
        NetworkManager.allowOnce(ssid)
    }

    fun blockNetwork(context: Context, ssid: String) {
        NetworkManager.block(context, ssid)
    }

    fun removeNetwork(context: Context, ssid: String) {
        NetworkManager.remove(context, ssid)
    }
}
