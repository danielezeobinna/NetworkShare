package com.danieleze.networkshare

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import java.io.File

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
        ) ?: run { toast("No file found in share"); return }

        if (!WebDAVService.isRunning) {
            toast("NetworkShare is not running. Turn it on first."); return
        }

        val realPath = resolveRealPath(uri) ?: run {
            toast("Could not resolve file path. Try sharing from a different file manager."); return
        }

        val url = buildUrl(realPath) ?: run {
            toast("This file is not in a shared folder.\nAdd its folder in Choose Shared Paths first."); return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("File Address", url))
        toast("File address copied!")
    }

    private fun resolveRealPath(uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            try {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:"))
                    return "${Environment.getExternalStorageDirectory()}/${docId.removePrefix("primary:")}"
                if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    return "/storage/${parts[0]}/${parts[1]}"
                }
            } catch (_: Exception) {
            }
            try {
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex("_data")
                        if (index != -1) {
                            val path = cursor.getString(index)
                            if (!path.isNullOrBlank()) return path
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (uri.scheme == "file") return uri.path
        return null
    }

    private fun buildUrl(realPath: String): String? {
        val isShared = FileManager.selectedPaths.any { shared ->
            realPath == shared || realPath.startsWith("$shared/")
        }
        if (!isShared) return null

        val root = getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            if (path.contains("/Android/")) path.split("/Android/")[0] else path
        }.map { File(it) }.firstOrNull { realPath.startsWith(it.absolutePath) } ?: return null

        val ip = getLocalIp() ?: return null
        val port = getPortForRoot(root.absolutePath) ?: return null

        val relative = realPath
            .removePrefix(root.absolutePath).trimStart('/')
            .split("/")
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        return if (WebDAVService.isAuthEnabled.value) {
            val token = WebDAVService.generateToken()
            "http://$ip:$port/$relative?token=$token"
        } else {
            "http://$ip:$port/$relative"
        }
    }

    private fun getLocalIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun getPortForRoot(rootPath: String): Int? {
        val roots = getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val path = dir.absolutePath
            if (path.contains("/Android/")) path.split("/Android/")[0] else path
        }.distinct()

        var port = 8080
        for (root in roots) {
            val hasShared = FileManager.selectedPaths.any { it.startsWith(root) }
            if (hasShared) {
                if (root == rootPath) return port
                port++
            }
        }
        return null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
