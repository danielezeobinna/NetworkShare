package com.danieleze.networkshare

import android.content.*
import android.os.*
import android.os.storage.StorageManager
import androidx.compose.runtime.*
import io.github.webdanlib.webdav.server.DavResponse
import java.io.File

data class FolderItem(
    val file: File,
    val name: String,
    val hasSubFolders: Boolean
)

object FileManager {

    var selectedPaths = mutableStateListOf<String>()
    var scannedItems = mutableStateListOf<FolderItem>()
    var isScanning = mutableStateOf(false)
    var tempPriorityPath: String? = null
    var storageRoots = mutableMapOf<String, String>()

    private var appContext: Context? = null
    private val serverIniContent = """
    [ViewState]
    Mode=
    Vid=
    FolderType=Generic
    [.ShellClassInfo]
    InfoTip=Contains files and folders that are shared to your network
    IconResource=%SystemRoot%\System32\imageres.dll,42
""".trimIndent()

    private val storageLabelIniContent = """
    [ViewState]
    Mode=
    Vid=
    FolderType=Generic
    [.ShellClassInfo]
    IconResource=C:\Windows\System32\SHELL32.dll,8
""".trimIndent()
    private val knownSystemDirTypes = listOf(
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_PICTURES
    )
    private val desktopIniContent: Map<String, String> by lazy {
        val contents = listOf(
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Pictures
        [.ShellClassInfo]
        InfoTip=Contains photos and footage taken by the camera
        IconResource=C:\Windows\System32\SHELL32.dll,117
    """.trimIndent(),
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Documents
        [.ShellClassInfo]
        IconResource=C:\WINDOWS\System32\imageres.dll,85
    """.trimIndent(),
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Generic
        [.ShellClassInfo]
        InfoTip=Contains downloaded files and folders
        IconResource=%SystemRoot%\system32\imageres.dll,-184
    """.trimIndent(),
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Videos
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12690
        IconResource=C:\WINDOWS\System32\imageres.dll,18
    """.trimIndent(),
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Music
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12689
        IconResource=C:\WINDOWS\System32\imageres.dll,103
    """.trimIndent(),
            """
        [ViewState]
        Mode=
        Vid=
        FolderType=Pictures
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12688
        IconResource=C:\WINDOWS\System32\imageres.dll,108
    """.trimIndent()
        )

        knownSystemDirTypes.zip(contents)
            .associate { (type, content) -> Environment.getExternalStoragePublicDirectory(type).name to content }
    }
    private var mediaReceiver: BroadcastReceiver? = null

    fun registerMediaReceiver(context: Context) {
        appContext = context.applicationContext
        if (mediaReceiver != null) return

        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refreshStorageRoots(context.applicationContext)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }

        context.applicationContext.registerReceiver(mediaReceiver, filter)
    }

    fun unregisterMediaReceiver(context: Context) {
        mediaReceiver?.let {
            try {
                context.applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        mediaReceiver = null
    }

    private fun desktopIniFile(cacheKey: String, content: String): File? {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "virtual_desktop_ini")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val iniFile = File(cacheDir, "${cacheKey}__desktop.ini")
        if (!iniFile.exists()) {
            try {
                val encodedBytes = content.replace("\n", "\r\n").toByteArray(Charsets.UTF_16LE)
                val finalBody = ByteArray(encodedBytes.size + 2)
                finalBody[0] = 0xFF.toByte()
                finalBody[1] = 0xFE.toByte()
                System.arraycopy(encodedBytes, 0, finalBody, 2, encodedBytes.size)
                iniFile.writeBytes(finalBody)
            } catch (_: Exception) {
                return null
            }
        }
        return iniFile
    }

    private fun appIconFile(): File? {
        val context = appContext ?: return null
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "virtual_assets")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val iconFile = File(cacheDir, "ic_ns.png")
        if (!iconFile.exists()) {
            try {
                context.assets.open("ic_ns.png").use { input ->
                    iconFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return iconFile
    }

    fun browserInstructionsFile(): File? {
        val context = appContext ?: return null
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "virtual_assets")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val htmlFile = File(cacheDir, "browser_instructions.html")
        if (!htmlFile.exists()) {
            try {
                context.assets.open("browser_instructions.html").use { input ->
                    htmlFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return htmlFile
    }

    fun clearVirtualDesktopIniCache() {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "virtual_desktop_ini")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun getAvailableStorages(context: Context): List<File> {
        refreshStorageRoots(context)
        return storageRoots.values
            .map { File(it) }
            .distinct()
            .filter { it.exists() && it.canRead() }
    }

    private fun matchStorageRoot(path: String): Map.Entry<String, String>? {
        return storageRoots.entries.firstOrNull { (_, root) -> path == root || path.startsWith("$root/") }
    }

    fun getStorageLabel(path: String): String {
        return matchStorageRoot(path)?.key ?: File(path).name.ifBlank { "Storage" }
    }

    fun toggleSelection(path: String) {
        val parentPath = selectedPaths.firstOrNull { path.startsWith("$it/") && path != it }
        if (parentPath != null) return
        if (selectedPaths.contains(path)) selectedPaths.remove(path)
        else selectedPaths.add(path)
    }

    private val virtualRootDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "virtual_root").apply { mkdirs() }
    }

    private fun syncVirtualRoot() {
        val existingLabels = virtualRootDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val currentLabels = storageRoots.keys
        (existingLabels - currentLabels).forEach { File(virtualRootDir, it).deleteRecursively() }
        (currentLabels - existingLabels).forEach { File(virtualRootDir, it).mkdirs() }
    }

    fun getObjects(uri: String): Any? {
        val clean = uri.trimStart('/')

        if (clean.isEmpty()) {
            syncVirtualRoot()
            return virtualRootDir
        }

        if (clean.equals("desktop.ini", ignoreCase = true)) {
            return desktopIniFile("server_root", serverIniContent)
        }

        if (clean.equals("ic_ns.png", ignoreCase = true)) {
            return appIconFile()
        }

        if (clean.equals("browser_instructions.html", ignoreCase = true)) {
            return browserInstructionsFile()
        }

        val slashIndex = clean.indexOf('/')
        val label = if (slashIndex == -1) clean else clean.take(slashIndex)
        val rest = if (slashIndex == -1) "" else clean.substring(slashIndex + 1)

        val rootPath = storageRoots[label] ?: return null
        val file = if (rest.isEmpty()) File(rootPath) else File(rootPath, rest)
        val absPath = file.absolutePath

        if (file.name.equals("ic_ns.png", ignoreCase = true) && !file.exists()) {
            return appIconFile()
        }

        if (file.name.equals("desktop.ini", ignoreCase = true) && !file.exists()) {
            if (rest == "desktop.ini") {
                return desktopIniFile("label_$label", storageLabelIniContent)
            }
            val folderName = file.parentFile?.name
            val isStorageRoot =
                folderName != null && rootPath == file.parentFile?.parentFile?.absolutePath
            if (isStorageRoot) {
                val content = desktopIniContent[folderName] ?: return null
                return desktopIniFile("${label}_$folderName", content)
            }
        }

        val isAllowed = selectedPaths.any { allowed ->
            absPath == allowed || absPath.startsWith("$allowed/") || allowed.startsWith("$absPath/")
        }

        if (!isAllowed) return DavResponse(
            403,
            "Forbidden",
            "text/plain; charset=utf-8",
            "Access denied"
        )

        if (!file.exists()) {
            return file
        }

        return file
    }

    fun requestFolderScan(directory: File?) {
        if (directory == null) return
        isScanning.value = true
        Thread {
            try {
                val items = directory.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map {
                        FolderItem(
                            file = it,
                            name = it.name,
                            hasSubFolders = it.listFiles()?.any { sub -> sub.isDirectory } ?: false
                        )
                    } ?: emptyList()
                Handler(Looper.getMainLooper()).post {
                    scannedItems.clear()
                    scannedItems.addAll(items)
                    isScanning.value = false
                }
            } catch (_: Exception) {
                isScanning.value = false
            }
        }.start()
    }

    fun refreshStorageRoots(context: Context) {
        storageRoots.clear()
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val externalDirs = context.getExternalFilesDirs(null).filterNotNull()
        val usedLabels = mutableSetOf<String>()

        externalDirs.forEach { dir ->
            val volume = storageManager.getStorageVolume(dir) ?: return@forEach

            val rootPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath ?: dir.absolutePath.split("/Android/")[0]
            } else {
                dir.absolutePath.split("/Android/")[0]
            }

            val description = volume.getDescription(context) ?: "External Drive"

            var finalLabel = description
            var suffix = 2
            while (!usedLabels.add(finalLabel)) {
                finalLabel = "$description #$suffix"
                suffix++
            }

            storageRoots[finalLabel] = rootPath
        }
    }

    fun resolveRealPath(context: Context, uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            try {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:"))
                    return "${Environment.getExternalStorageDirectory()}/${docId.removePrefix("primary:")}"
                if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    return "/storage/${parts[0]}/${parts[1]}"
                }
            } catch (e: Exception) {
                android.util.Log.d("FileManager", "getDocumentId failed: ${e.message}")
            }

            // Samsung My Files FileProvider — path is embedded directly in the URI segments
            @Suppress("SpellCheckingInspection")
            if (uri.authority == "com.sec.android.app.myfiles.FileProvider") {
                val segments = uri.pathSegments
                val storageIndex = segments.indexOf("device_storage")
                if (storageIndex != -1 && storageIndex + 1 < segments.size) {
                    val relative = segments.drop(storageIndex + 2).joinToString("/")
                    if (relative.isNotBlank()) {
                        return "${Environment.getExternalStorageDirectory()}/$relative"
                    }
                }
            }

            try {
                context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex("_data")
                            if (index != -1) {
                                val path = cursor.getString(index)
                                if (!path.isNullOrBlank()) return path
                            }
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.d("FileManager", "_data query failed: ${e.message}")
            }
        }
        android.util.Log.d(
            "FileManager",
            "resolveRealPath failed. scheme=${uri.scheme}, authority=${uri.authority}, " +
                    "uri=$uri, isDocumentUri=${
                        android.provider.DocumentsContract.isDocumentUri(
                            context,
                            uri
                        )
                    }, " +
                    "pathSegments=${uri.pathSegments}"
        )
        if (uri.scheme == "file") return uri.path
        return null
    }

    fun findSharedLocation(realPath: String): Pair<String, String>? {
        val entry = matchStorageRoot(realPath) ?: return null
        val relativePath = realPath.removePrefix(entry.value).trimStart('/')
        return entry.key to relativePath
    }

    fun urlSafeSegment(segment: String): String {
        return segment
            .replace("%", "%25")
            .replace("#", "%23")
            .replace("?", "%3F")
            .replace("&", "%26")
    }

    fun applySelectedPaths(saved: Set<String>?): Boolean {
        selectedPaths.clear()
        return if (saved != null) {
            selectedPaths.addAll(saved)
            false
        } else {
            selectedPaths.addAll(defaultSharedPaths())
            true
        }
    }

    private fun defaultSharedPaths(): List<String> {
        return knownSystemDirTypes
            .map { Environment.getExternalStoragePublicDirectory(it) }
            .filter { it.exists() && it.isDirectory }
            .map { it.absolutePath }
    }
}
