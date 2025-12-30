package com.example.networkshare

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class WebDAVServer(
    val port: Int, 
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
        val uri = session.uri.trimStart('/')
        val method = session.method
        val targetFile = File(rootDirectory, uri)

        if (!targetFile.exists()) {
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