package com.example.networkshare

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class WebDAVServer(
    port: Int,
    private val rootDirectory: File
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

    private fun handlePut(session: IHTTPSession, target: File): Response {
        return try {
            target.parentFile?.mkdirs()
        
            val inputStream = session.inputStream
            val contentLength = session.headers["content-length"]?.toLong() ?: 0L
        
            target.outputStream().use { output ->
                val buffer = ByteArray(65536) // 64KB buffer
                var totalRead = 0L
                while (totalRead < contentLength) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read
                }
            }
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
        val destPath = java.net.URL(destinationHeader).path
        val destFile = File(rootDirectory, destPath.trimStart('/'))
    
        return if (target.renameTo(destFile)) {
            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Moved")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Move Failed")
        }
    }
    private fun handleDelete(target: File): Response {
        return if (target.deleteRecursively()) {
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
                newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(target), target.length())
            }
        } catch (_: Exception) {
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
                val childUri = "${session.uri.removeSuffix("/")}/${child.name}"
                xml.append(getFilePropertiesXml(child, childUri))
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