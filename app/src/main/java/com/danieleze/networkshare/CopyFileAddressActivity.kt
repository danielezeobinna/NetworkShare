package com.danieleze.networkshare

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class CopyFileAddressActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebDAVService.loadPaths(this)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type == null) {
            toast("Unsupported share type"); return
        }

        val uri = androidx.core.content.IntentCompat.getParcelableExtra(
            intent, Intent.EXTRA_STREAM, android.net.Uri::class.java
        ) ?: run {
            // No actual file attached — this is a link/text share that slipped through
            // (e.g. an app sharing text/plain that isn't in our excluded list, or a
            // manifest edge case). Fail silently instead of showing a confusing error.
            return
        }

        if (!WebDAVService.isRunning) {
            toast("NetworkShare is not running. Turn it on first."); return
        }

        val realPath = FileManager.resolveRealPath(this, uri) ?: run {
            toast("Could not resolve file path. Try sharing from a different file manager."); return
        }

        val url = buildUrl(realPath) ?: run {
            toast("This file is not in a shared folder.\nAdd its folder in Choose Shared Paths first."); return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("File Address", url))
        toast("File address copied!")
    }

    private fun buildUrl(realPath: String): String? {
        val isShared = FileManager.selectedPaths.any { shared ->
            realPath == shared || realPath.startsWith("$shared/")
        }
        if (!isShared) return null

        val (label, relativePath) = FileManager.findSharedLocation(realPath) ?: return null

        val port = WebDAVService.activeServers.firstOrNull()?.port ?: return null
        val base = WebDAVService.baseAddressForPort(port)

        val safeLabel = FileManager.urlSafeSegment(label)
        val safeRelative = relativePath.split("/").joinToString("/") { FileManager.urlSafeSegment(it) }
        val suffix = if (safeRelative.isEmpty()) safeLabel else "$safeLabel/$safeRelative"

        return if (WebDAVService.isAuthEnabled.value) {
            val token = WebDAVService.generateToken()
            "$base/$suffix?token=$token"
        } else {
            "$base/$suffix"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
