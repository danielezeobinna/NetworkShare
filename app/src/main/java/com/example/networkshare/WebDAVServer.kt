package com.example.networkshare

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.text.Charsets
import java.util.UUID

class WebDAVServer(
    val port: Int,
    val rootDirectory: File,
    private val context: Context,
    private val allowedPaths: List<String>
) : NanoHTTPD(port) {

    private val tag = "WebDAVServer:$port"

    init {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(tag, "Server started at ${rootDirectory.absolutePath}")
        } catch (e: IOException) {
            Log.e(tag, "Could not start server on port $port", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        val targetFile = File(rootDirectory, uri.trimStart('/'))
        val targetPath = targetFile.absolutePath
        val fileName = targetFile.name
        val isDesktopIni = fileName.equals("desktop.ini", ignoreCase = true)
        val isRoot = uri == "/" || uri.isEmpty()

        val isAllowed = isRoot || isDesktopIni || allowedPaths.any { sharedPath ->
            targetPath == sharedPath || targetPath.startsWith("$sharedPath/")
        }

        if (!isAllowed) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access Denied")
        }

        return when (method) {
            Method.OPTIONS -> serveOptions()
            Method.PROPFIND -> {
                if (isDesktopIni && !targetFile.exists()) {
                    serveVirtualDesktopIniPropfind(uri)
                } else if (targetFile.exists()) {
                    servePropfind(targetFile, session)
                } else {
                    serveNotFound()
                }
            }
            Method.GET -> {
                if (isDesktopIni) {
                    if (targetFile.exists()) serveFile(targetFile, session)
                    else serveVirtualDesktopIni(uri)
                } else {
                    if (targetFile.exists()) serveFile(targetFile, session) else serveNotFound()
                }
            }
            Method.DELETE -> handleDelete(targetFile)
            Method.PUT -> handlePut(session, targetFile)
            Method.MKCOL -> handleMkcol(targetFile)
            Method.PROPPATCH -> handleProppatch(session, targetFile)
            Method.MOVE -> handleMove(session, targetFile)
            Method.LOCK -> serveLock(uri)
            Method.UNLOCK -> newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            Method.HEAD -> if (targetFile.exists()) {
                val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
                res.addHeader("Content-Length", targetFile.length().toString())
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("ETag", "\"${targetFile.lastModified()}-${targetFile.length()}\"")
                res
            } else serveNotFound()
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Not Supported")
        }
    }

    private fun serveVirtualDesktopIni(uri: String): Response {
        // Determine the folder path by removing /desktop.ini from the end
        val folderPath = uri.removeSuffix("/desktop.ini").removeSuffix("/")
        val cleanPath = folderPath.ifEmpty { "/" }

        val content = when {
            cleanPath == "/" -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Generic
            [.ShellClassInfo]
            IconResource=C:\Windows\System32\SHELL32.dll,9
        """.trimIndent()

            cleanPath.equals("/Android", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Generic
            [.ShellClassInfo]
            InfoTip=Contains system files and folders
            IconResource=C:\Windows\System32\SHELL32.dll,314
        """.trimIndent()

            cleanPath.equals("/DCIM", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Pictures
            [.ShellClassInfo]
            InfoTip=Contains photos and footage taken by the camera
            IconResource=C:\Windows\System32\SHELL32.dll,117
        """.trimIndent()

            cleanPath.equals("/Documents", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Documents
            [.ShellClassInfo] 
            IconResource=C:\Windows\System32\SHELL32.dll,126
        """.trimIndent()

            cleanPath.equals("/Download", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Generic
            [.ShellClassInfo]
            InfoTip=Contains downloaded files and folders 
            IconResource=%SystemRoot%\system32\imageres.dll,-184
        """.trimIndent()

            cleanPath.equals("/Movies", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Videos
            [.ShellClassInfo]
            InfoTip=@%SystemRoot%\system32\shell32.dll,-12690
            IconResource=C:\Windows\System32\SHELL32.dll,129
        """.trimIndent()

            cleanPath.equals("/Music", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Music
            [.ShellClassInfo]
            InfoTip=@%SystemRoot%\system32\shell32.dll,-12689
            IconResource=C:\Windows\System32\SHELL32.dll,128
        """.trimIndent()

            cleanPath.equals("/Pictures", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Pictures
            [.ShellClassInfo]
            InfoTip=@%SystemRoot%\system32\shell32.dll,-12688
            IconResource=C:\Windows\System32\SHELL32.dll,127
        """.trimIndent()

            cleanPath.equals("/NetworkShare", ignoreCase = true) -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Generic
            [.ShellClassInfo]
            InfoTip=Contains files and folders that are shared to your network 
            IconResource=%SystemRoot%\System32\imageres.dll,42
        """.trimIndent()

            else -> """
            [ViewState]
            Mode=
            Vid=
            FolderType=Generic
            [.ShellClassInfo]
            IconResource=C:\Windows\System32\SHELL32.dll,4
        """.trimIndent()
        }.replace("\n", "\r\n") // Ensure Windows-style line endings

        // Convert to UTF-16LE with BOM
        val encodedBytes = content.toByteArray(Charsets.UTF_16LE)
        val finalBody = ByteArray(encodedBytes.size + 2)
        finalBody[0] = 0xFF.toByte()
        finalBody[1] = 0xFE.toByte()
        System.arraycopy(encodedBytes, 0, finalBody, 2, encodedBytes.size)

        val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", finalBody.inputStream(), finalBody.size.toLong())
        res.addHeader("Content-Type", "text/plain; charset=utf-16le")
        return res
    }

    private fun getVirtualDesktopIniXml(uri: String): String {
        val now = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
        return """
    <D:response xmlns:Z="urn:schemas-microsoft-com:">
        <D:href>$uri</D:href>
        <D:propstat>
            <D:prop>
                <D:displayname>desktop.ini</D:displayname>
                <D:getcontentlength>200</D:getcontentlength>
                <D:resourcetype/>
                <D:getlastmodified>$now</D:getlastmodified>
                <Z:Win32FileAttributes>0x00000006</Z:Win32FileAttributes>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
        </D:propstat>
    </D:response>
    """.trimIndent()
    }

    private fun serveVirtualDesktopIniPropfind(uri: String): Response {
        val xmlBody = getVirtualDesktopIniXml(uri)
        // 207 Multi-Status is the required response for WebDAV PROPFIND
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xmlBody)
    }

    private fun handleMkcol(target: File): Response {
        return try {
            if (target.exists()) {
                newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Folder already exists")
            } else if (target.mkdirs()) {
                newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Created")
            } else {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to create folder")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    @SuppressLint("DefaultLocale")
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun handlePut(session: IHTTPSession, target: File): Response {
        return try {
            target.parentFile?.mkdirs()
            val inputStream = session.inputStream
            val contentLength = session.headers["content-length"]?.toLong() ?: 0L

            val manager = context.getSystemService(NotificationManager::class.java)
            val builder = NotificationCompat.Builder(context, "WebDAV_Service_Channel")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading ${target.name}")
                .setColor("#2BAED5".toColorInt())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var totalRead = 0L
                var lastUpdateTime = 0L // Track the time

                while (totalRead < contentLength) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read

                    val currentTime = System.currentTimeMillis()
                    // Check if 1 second (1000ms) has passed since the last update
                    if (currentTime - lastUpdateTime >= 500) {
                        val percent = (totalRead * 100 / contentLength).toInt()
                        builder.setProgress(100, percent, false)
                        builder.setContentText("${formatSize(totalRead)} / ${formatSize(contentLength)}")
                        manager?.notify(target.name.hashCode(), builder.build())

                        lastUpdateTime = currentTime // Reset the timer
                    }
                }
            }
            manager?.cancel(target.name.hashCode())
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleProppatch(session: IHTTPSession, target: File): Response {
        Log.d(tag, "Windows is updating properties for: ${target.absolutePath}")
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>${session.uri}</D:href>
                    <D:propstat>
                        <D:prop><D:getlastmodified/></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>""".trimIndent()
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xml)
    }

    private fun handleMove(session: IHTTPSession, target: File): Response {
        val destinationHeader = session.headers["destination"] ?: return serveNotFound()
        return try {
            val decodedPath = java.net.URLDecoder.decode(java.net.URL(destinationHeader).path, "UTF-8")
            val destFile = File(rootDirectory, decodedPath.trimStart('/'))
            destFile.parentFile?.mkdirs()
            if (target.renameTo(destFile)) {
                newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Moved")
            } else {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Move Failed")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleDelete(target: File): Response {
        val path = target.absolutePath
        if (target.isFile) {
            if (!PersistenceGuard.isSafeToDelete(context, path)) {

                if (context is WebDAVService) {
                    context.showSafetyAlert(target.name)
                }

                Log.w(tag, "Safety Lock Active: Blocking DELETE for ${target.name}")
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Safety Lock Active")
            }
        }

        return if (target.deleteRecursively()) {
            PersistenceGuard.clear(context, path)
            newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Delete failed")
        }
    }

    private fun serveNotFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    private fun serveLock(uri: String): Response {
        val token = "uuid:${UUID.randomUUID()}"
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
            <D:prop xmlns:D="DAV:">
                <D:lockdiscovery>
                    <D:activelock>
                        <D:locktype><D:write/></D:locktype>
                        <D:lockscope><D:exclusive/></D:lockscope>
                        <D:depth>0</D:depth>
                        <D:owner><D:href>AndroidServer</D:href></D:owner>
                        <D:timeout>Second-3600</D:timeout>
                        <D:locktoken><D:href>$token</D:href></D:locktoken>
                        <D:lockroot><D:href>$uri</D:href></D:lockroot>
                    </D:activelock>
                </D:lockdiscovery>
            </D:prop>""".trimIndent()
        val res = newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", xml)
        res.addHeader("Lock-Token", "<$token>")
        return res
    }

    private fun serveOptions(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        res.addHeader("Allow", "GET, POST, OPTIONS, PROPFIND, PUT, MKCOL, DELETE, COPY, MOVE, LOCK, UNLOCK")
        res.addHeader("DAV", "1, 2")
        res.addHeader("MS-Author-Via", "DAV")
        return res
    }

    private fun serveFile(target: File, session: IHTTPSession): Response {
        val fileLength = target.length()
        val rangeHeader = session.headers["range"]

        return try {
            if (target.isDirectory) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Directory GET forbidden")

            var startOffset = 0L
            var endOffset = fileLength - 1
            var isPartial = false

            // 1. Detect if this is a Stream (Partial) or a full Copy (Upload)
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isPartial = true
                val range = rangeHeader.substring(6).split("-")
                startOffset = range[0].toLongOrNull() ?: 0L
                if (range.size > 1 && range[1].isNotEmpty()) {
                    endOffset = range[1].toLongOrNull() ?: (fileLength - 1)
                }
            }

            val dataToDeliver = endOffset - startOffset + 1
            val notificationId = target.name.hashCode()
            val manager = context.getSystemService(NotificationManager::class.java)

            // 2. Setup Notification ONLY if it is NOT a partial/stream request
            val builder = if (!isPartial) {
                NotificationCompat.Builder(context, "WebDAV_Service_Channel")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("Uploading ${target.name}")
                    .setColor("#2BAED5".toColorInt())
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
            } else null

            val fis = FileInputStream(target)
            if (startOffset > 0) fis.skip(startOffset)
            val path = target.absolutePath

            val fileStream = object : FileInputStream(fis.fd) {
                var totalBytesRead = 0L
                var lastUpdateTime = 0L // Track the time

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val maxToRead = min(len.toLong(), dataToDeliver - totalBytesRead).toInt()
                    if (maxToRead <= 0) return -1

                    val bytesRead = fis.read(b, off, maxToRead)
                    if (bytesRead != -1) {
                        totalBytesRead += bytesRead

                        builder?.let {
                            val currentTime = System.currentTimeMillis()
                            // static/member variable to track time in the anonymous object
                            if (currentTime - lastUpdateTime >= 500) {
                                val percent = (totalBytesRead * 100 / dataToDeliver).toInt()
                                it.setProgress(100, percent, false)
                                it.setContentText("${formatSize(totalBytesRead)} / ${formatSize(dataToDeliver)}")
                                manager?.notify(notificationId, it.build())

                                lastUpdateTime = currentTime
                            }
                        }
                    }
                    return bytesRead
                }

                override fun close() {
                    fis.close()
                    if (!isPartial) manager?.cancel(notificationId)
                    super.close()

                    if (!isPartial && totalBytesRead < dataToDeliver) {
                        Log.w(tag, "Transfer failed/interrupted at $totalBytesRead/$dataToDeliver bytes. Safety lock maintained.")
                        PersistenceGuard.markStarted(context, path)
                    } else if (!isPartial && totalBytesRead == dataToDeliver) {
                        Log.d(tag, "Transfer completed successfully. File is now safe to be moved/deleted.")
                    }
                }
            }

            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val res = newFixedLengthResponse(status, "application/octet-stream", fileStream, dataToDeliver)

            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("ETag", "\"${target.lastModified()}-${target.length()}\"")
            if (isPartial) {
                res.addHeader("Content-Range", "bytes $startOffset-$endOffset/$fileLength")
            }
            return res

        } catch (e: Exception) {
            Log.e(tag, "Read Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Read Error")
        }
    }

    private fun servePropfind(target: File, session: IHTTPSession): Response {
        val xml = StringBuilder("""<?xml version="1.0" encoding="utf-8" ?>""")
        xml.append("""<D:multistatus xmlns:D="DAV:">""")

        xml.append(getFilePropertiesXml(target, session.uri))

        val depth = session.headers["depth"] ?: "1"

        if (target.isDirectory && depth != "0") {
            target.listFiles()?.forEach { child ->
                val childPath = child.absolutePath

                val isVisible = allowedPaths.any { allowed ->
                    childPath == allowed ||
                            allowed.startsWith("$childPath/") ||
                            childPath.startsWith("$allowed/")
                }

                if (isVisible) {
                    val baseUri = session.uri.removeSuffix("/")
                    val childUri = "$baseUri/${child.name}"
                    xml.append(getFilePropertiesXml(child, childUri))
                }
            }
        }

        xml.append("</D:multistatus>")
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xml.toString())
    }

    private fun getFilePropertiesXml(file: File, uri: String): String {
        val isDir = file.isDirectory
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        // Windows loves this specific format for creation dates
        val creationDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(file.lastModified()))
        val etag = "\"${file.lastModified()}-${file.length()}\""

        val safeDisplayName = file.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val safeUri = uri.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        return """
    <D:response xmlns:Z="urn:schemas-microsoft-com:">
        <D:href>$safeUri</D:href>
        <D:propstat>
            <D:prop>
                <D:displayname>$safeDisplayName</D:displayname>
                <D:getcontentlength>${if (isDir) 0 else file.length()}</D:getcontentlength>
                <D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>
                <Z:Win32FileAttributes>0x00000004</Z:Win32FileAttributes>
                <D:getlastmodified>${sdf.format(Date(file.lastModified()))}</D:getlastmodified>
                <D:creationdate>$creationDate</D:creationdate>
                <D:getetag>$etag</D:getetag>
            </D:prop>
            <D:status>HTTP/1.1 200 OK</D:status>
        </D:propstat>
    </D:response>
""".trimIndent()
    }

    fun stopServer() = stop()
}