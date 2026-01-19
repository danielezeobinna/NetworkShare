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
import kotlin.math.pow

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

        if (uri == "/" || uri.isEmpty()) {
            return if (method == Method.PROPFIND) {
                servePropfind(targetFile, session)
            } else {
                serveOptions()
            }
        }

        val isAllowed = allowedPaths.any { sharedPath ->
            targetPath == sharedPath || targetPath.startsWith("$sharedPath/")
        }

        if (!isAllowed) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access Denied")
        }

        return when (method) {
            Method.OPTIONS -> serveOptions()
            Method.PROPFIND -> if (targetFile.exists()) servePropfind(targetFile, session) else serveNotFound()
            Method.GET -> if (targetFile.exists()) serveFile(targetFile) else serveNotFound()
            Method.DELETE -> handleDelete(targetFile)
            Method.PUT -> handlePut(session, targetFile)
            Method.MKCOL -> handleMkcol(targetFile)
            Method.PROPPATCH -> handleProppatch(session, targetFile)
            Method.MOVE -> handleMove(session, targetFile)
            Method.LOCK -> serveLock(uri)
            Method.UNLOCK -> newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            Method.HEAD -> if (targetFile.exists()) newFixedLengthResponse(Response.Status.OK, null, "") else serveNotFound()
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Not Supported")
        }
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
                while (totalRead < contentLength) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read

                    val percent = (totalRead * 100 / contentLength).toInt()
                    if (percent % 5 == 0) {
                        builder.setProgress(100, percent, false)
                        builder.setContentText("${formatSize(totalRead)} / ${formatSize(contentLength)}")
                        manager?.notify(target.name.hashCode(), builder.build())
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

    private fun serveFile(target: File): Response {
        return try {
            if (target.isDirectory) {
                newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Directory GET forbidden")
            } else {
                val path = target.absolutePath
                val notificationId = target.name.hashCode()
                PersistenceGuard.markStarted(context, path)

                val manager = context.getSystemService(NotificationManager::class.java)
                val builder = NotificationCompat.Builder(context, "WebDAV_Service_Channel")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("Uploading ${target.name}")
                    .setColor("#2BAED5".toColorInt())
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)

                val fileStream = object : FileInputStream(target) {
                    var totalBytesRead = 0L
                    var lastPercent = -1

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        return try {
                            val bytesRead = super.read(b, off, len)
                            if (bytesRead != -1) {
                                totalBytesRead += bytesRead

                                val totalSize = target.length()
                                if (totalSize > 0) {
                                    val percent = (totalBytesRead * 100 / totalSize).toInt()

                                    if (percent != lastPercent && percent % 5 == 0) {
                                        builder.setProgress(100, percent, false)
                                        builder.setContentText("${formatSize(totalBytesRead)} / ${formatSize(totalSize)}")
                                        manager?.notify(notificationId, builder.build())
                                        lastPercent = percent
                                    }
                                }
                            }

                            if (totalBytesRead >= target.length()) {
                                PersistenceGuard.markVerified(context, path)
                            }
                            bytesRead
                        } catch (e: IOException) {
                            close()
                            throw e
                        }
                    }

                    override fun close() {
                        super.close()
                        manager?.cancel(notificationId)

                        if (totalBytesRead < target.length()) {
                            Log.w(tag, "Transfer failed at $totalBytesRead bytes. Safety lock maintained.")
                            PersistenceGuard.markStarted(context, path)
                        }
                    }
                }

                val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fileStream, target.length())
                res.addHeader("Connection", "close")
                return res
            }
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
                    val childUri = "${session.uri.removeSuffix("/")}/${child.name}"
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
        return """
            <D:response>
                <D:href>$uri</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>${file.name}</D:displayname>
                        <D:getcontentlength>${if (isDir) 0 else file.length()}</D:getcontentlength>
                        <D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>
                        <D:getlastmodified>${sdf.format(Date(file.lastModified()))}</D:getlastmodified>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        """.trimIndent()
    }

    fun stopServer() = stop()
}