package com.danieleze.networkshare

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File

data class FolderItem(
    val file: File,
    val name: String,
    val hasSubFolders: Boolean
)

object FileManager {

    var selectedPaths = mutableStateListOf<String>()
    var scannedItems  = mutableStateListOf<FolderItem>()
    var isScanning    = mutableStateOf(false)
    var tempPriorityPath: String? = null
    var storageRoots  = mutableMapOf<String, String>()

    private val storageLabelIniContent = """
    [ViewState]
    Mode=
    Vid=
    FolderType=Generic
    [.ShellClassInfo]
    IconResource=C:\Windows\System32\SHELL32.dll,8
""".trimIndent()
    private val desktopIniContent = mapOf(
        "Android" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Generic
        [.ShellClassInfo]
        InfoTip=Contains system files and folders
        IconResource=C:\Windows\System32\SHELL32.dll,314
    """.trimIndent(),
        "DCIM" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Pictures
        [.ShellClassInfo]
        InfoTip=Contains photos and footage taken by the camera
        IconResource=C:\Windows\System32\SHELL32.dll,117
    """.trimIndent(),
        "Documents" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Documents
        [.ShellClassInfo]
        IconResource=C:\Windows\System32\SHELL32.dll,126
    """.trimIndent(),
        "Download" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Generic
        [.ShellClassInfo]
        InfoTip=Contains downloaded files and folders
        IconResource=%SystemRoot%\system32\imageres.dll,-184
    """.trimIndent(),
        "Movies" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Videos
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12690
        IconResource=C:\Windows\System32\SHELL32.dll,129
    """.trimIndent(),
        "Music" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Music
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12689
        IconResource=C:\Windows\System32\SHELL32.dll,128
    """.trimIndent(),
        "Pictures" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Pictures
        [.ShellClassInfo]
        InfoTip=@%SystemRoot%\system32\shell32.dll,-12688
        IconResource=C:\Windows\System32\SHELL32.dll,127
    """.trimIndent(),
        "NetworkShare" to """
        [ViewState]
        Mode=
        Vid=
        FolderType=Generic
        [.ShellClassInfo]
        InfoTip=Contains files and folders that are shared to your network
        IconResource=%SystemRoot%\System32\imageres.dll,42
    """.trimIndent()
    )
    private var mediaReceiver: android.content.BroadcastReceiver? = null

    fun registerMediaReceiver(context: Context) {
        if (mediaReceiver != null) return

        mediaReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refreshStorageRoots(context.applicationContext)
            }
        }

        val filter = android.content.IntentFilter().apply {
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
            } catch (_: Exception) { }
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

    fun clearVirtualDesktopIniCache() {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "virtual_desktop_ini")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun getAvailableStorages(context: Context): List<File> {
        val storages = mutableListOf<File>()
        context.getExternalFilesDirs(null).forEach { dir ->
            if (dir != null) {
                val path = dir.absolutePath
                val rootPath = if (path.contains("/Android/")) path.split("/Android/")[0] else path
                val rootFile = File(rootPath)
                if (rootFile.exists() && rootFile.canRead() && !storages.contains(rootFile)) {
                    storages.add(rootFile)
                }
            }
        }
        return storages
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

        val slashIndex = clean.indexOf('/')
        val label = if (slashIndex == -1) clean else clean.take(slashIndex)
        val rest = if (slashIndex == -1) "" else clean.substring(slashIndex + 1)

        val rootPath = storageRoots[label] ?: return null
        val file = if (rest.isEmpty()) File(rootPath) else File(rootPath, rest)
        val absPath = file.absolutePath

        if (file.name.equals("desktop.ini", ignoreCase = true) && !file.exists()) {
            if (rest == "desktop.ini") {
                // This is the storage label's own desktop.ini (e.g. "/Internal Storage/desktop.ini")
                return desktopIniFile("label_$label", storageLabelIniContent) ?: 404
            }
            val folderName = file.parentFile?.name
            val isStorageRoot = folderName != null && rootPath == file.parentFile?.parentFile?.absolutePath
            if (isStorageRoot) {
                val content = desktopIniContent[folderName] ?: return null
                return desktopIniFile("${label}_$folderName", content) ?: 404
            }
        }

        val isAllowed = selectedPaths.any { allowed ->
            absPath == allowed || absPath.startsWith("$allowed/") || allowed.startsWith("$absPath/")
        }

        if (!isAllowed) return 403

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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
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
}