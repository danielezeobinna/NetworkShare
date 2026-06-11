package com.danieleze.networkshare

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

    fun toggleSelection(path: String) {
        val parentPath = selectedPaths.firstOrNull { path.startsWith("$it/") && path != it }
        if (parentPath != null) return
        if (selectedPaths.contains(path)) selectedPaths.remove(path)
        else selectedPaths.add(path)
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
}