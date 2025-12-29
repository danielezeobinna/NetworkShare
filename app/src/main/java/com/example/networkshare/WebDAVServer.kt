package com.example.networkshare

import android.os.Environment
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class WebDAVServer(
    val port: Int = 8080
) : NanoHTTPD(port) {

    private val tag = "WebDAVServer"

    private val virtualRoot: List<File> = buildVirtualRoot()

    init {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(tag, "Server started on http://${getLocalIpAddress()}:$port/")
        } catch (e: IOException) {
            Log.e(tag, "Could not start server", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/')
        val method = session.method

        var targetFile: File? = null
        
        if (uri.isEmpty()) {
            targetFile = virtualRoot.firstOrNull()
        } else {
            for (root in virtualRoot) {
                val potential = File(root, uri)
                if (potential.exists()) {
                    targetFile = potential
                    break
                }
            }
        }

        if (targetFile == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        return when (method) {
            Method.GET -> serveFile(targetFile)
            Method.PROPFIND -> servePropfind(targetFile, session)
            Method.OPTIONS -> serveOptions()
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Not Supported")
        }
    }

    private fun serveOptions(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        res.addHeader("Allow", "GET, POST, OPTIONS, PROPFIND, HEAD")
        res.addHeader("DAV", "1")
        return res
    }

    private fun serveFile(target: File): Response {
        return try {
            if (target.isDirectory) {
                newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Directory GET not allowed")
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    getMimeType(target.path),
                    FileInputStream(target),
                    target.length()
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error serving file: ${target.name}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading file")
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
        val size = if (isDir) 0 else file.length()
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(file.lastModified()))

        return """
            <D:response>
                <D:href>$uri</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>${file.name}</D:displayname>
                        <D:getcontentlength>$size</D:getcontentlength>
                        <D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>
                        <D:getlastmodified>$date</D:getlastmodified>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        """.trimIndent()
    }

    private fun getMimeType(path: String) = when {
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".txt", true) -> "text/plain"
        path.endsWith(".mp4", true) -> "video/mp4"
        else -> MIME_PLAINTEXT
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(tag, "Error getting local IP", ex)
        }
        return null
    }

    fun stopServer() {
        stop()
        Log.d(tag, "Server stopped")
    }

    companion object {
        private fun buildVirtualRoot(): List<File> {
            val roots = mutableListOf<File>()
            roots.add(Environment.getExternalStorageDirectory())

            val extSd = File("/storage/").listFiles()
            extSd?.forEach { 
                if (it.exists() && it.canRead() && !roots.contains(it)) {
                    roots.add(it)
                }
            }
            return roots
        }
    }
}