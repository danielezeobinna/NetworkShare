package com.example.networkshare

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.text.Charsets

// ─────────────────────────────────────────────────────────────
//  Digest Auth Manager (embedded)
// ─────────────────────────────────────────────────────────────
private object DigestAuthManager {
    private const val REALM             = "NetworkShare"
    private const val NONCE_VALIDITY_MS = 1_800_000L
    private const val SESSION_IDLE_MS   = 600_000L

    // nonce -> creation timestamp
    private val validNonces = ConcurrentHashMap<String, Long>()

    data class SessionState(
        val sessionKey: String,
        var expiryMs: Long,          // wall-clock time this session expires
        var isTransferring: Boolean, // when true the idle timer is paused
        var frozenRemainingMs: Long  // how much time was left when transfer started
    )

    // sessionKey (ip|userAgent) -> SessionState
    private val authenticatedSessions = ConcurrentHashMap<String, SessionState>()

    private var username: String = "user"
    private var password: String = "pass"

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
    }

    // ── Nonce helpers ────────────────────────────────────────

    fun generateNonce(): String {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        validNonces[nonce] = System.currentTimeMillis()
        cleanExpiredNonces()
        return nonce
    }

    private fun cleanExpiredNonces() {
        val now = System.currentTimeMillis()
        validNonces.entries.removeIf { now - it.value > NONCE_VALIDITY_MS }
    }

    private fun isNonceValid(nonce: String): Boolean {
        val timestamp = validNonces[nonce] ?: return false
        return System.currentTimeMillis() - timestamp < NONCE_VALIDITY_MS
    }

    // ── Digest validation ────────────────────────────────────

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun validateAuthorization(authHeader: String, method: String): Boolean {
        if (!authHeader.startsWith("Digest ")) return false

        val params   = parseDigestParams(authHeader)
        val nonce    = params["nonce"]    ?: return false
        val uri      = params["uri"]      ?: return false
        val response = params["response"] ?: return false
        val user     = params["username"] ?: return false
        val nc       = params["nc"]       ?: "00000001"
        val cnonce   = params["cnonce"]   ?: ""
        val qop      = params["qop"]

        if (user != username) return false
        if (!isNonceValid(nonce)) return false

        val ha1 = md5("$username:$REALM:$password")
        val ha2 = md5("$method:$uri")

        val expected = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        return response == expected
    }

    private fun parseDigestParams(header: String): Map<String, String> {
        val result  = mutableMapOf<String, String>()
        val content = header.removePrefix("Digest ")
        val regex   = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
        regex.findAll(content).forEach { match ->
            val key   = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[key] = value
        }
        return result
    }

    fun buildWwwAuthenticateHeader(stale: Boolean = false): String {
        val nonce    = generateNonce()
        val staleStr = if (stale) ", stale=true" else ""
        return """Digest realm="$REALM", qop="auth", nonce="$nonce", algorithm=MD5$staleStr"""
    }

    // ── Session cache ────────────────────────────────────────

    /**
     * Create or refresh the 10-second sliding session window.
     * Called on every successful authenticated request so the
     * timer resets as long as the client keeps making requests.
     */
    fun cacheSession(sessionKey: String) {
        val existing = authenticatedSessions[sessionKey]
        if (existing != null && existing.isTransferring) {
            // Transfer in progress — just update the frozen remainder to a full window
            // so when the transfer ends there's still 10 s left
            existing.frozenRemainingMs = SESSION_IDLE_MS
        } else {
            authenticatedSessions[sessionKey] = SessionState(
                sessionKey         = sessionKey,
                expiryMs           = System.currentTimeMillis() + SESSION_IDLE_MS,
                isTransferring     = false,
                frozenRemainingMs  = SESSION_IDLE_MS
            )
        }
    }

    /**
     * Check whether a session is still valid.
     * If not transferring: uses the sliding wall-clock expiry.
     * If transferring: timer is paused — always valid until transfer ends.
     */
    fun isSessionCached(sessionKey: String): Boolean {
        val state = authenticatedSessions[sessionKey] ?: return false
        if (state.isTransferring) return true  // timer paused — always valid
        return if (System.currentTimeMillis() < state.expiryMs) {
            // Still valid — slide the window forward on every check
            state.expiryMs = System.currentTimeMillis() + SESSION_IDLE_MS
            true
        } else {
            authenticatedSessions.remove(sessionKey)
            false
        }
    }

    /**
     * Call when a file transfer starts.
     * Freezes the remaining idle time so the 10 s countdown pauses.
     */
    fun pauseTimer(sessionKey: String) {
        val state = authenticatedSessions[sessionKey] ?: return
        state.frozenRemainingMs = maxOf(0L, state.expiryMs - System.currentTimeMillis())
        state.isTransferring    = true
    }

    /**
     * Call when a file transfer completes or is cancelled.
     * Resumes the countdown from wherever it was frozen.
     */
    fun resumeTimer(sessionKey: String) {
        val state = authenticatedSessions[sessionKey] ?: return
        state.isTransferring = false
        state.expiryMs       = System.currentTimeMillis() + state.frozenRemainingMs
    }
}

// ─────────────────────────────────────────────────────────────
//  WebDAV Server
// ─────────────────────────────────────────────────────────────
class WebDAVServer(
    val port: Int,
    val rootDirectory: File,
    private val context: Context,
    private val allowedPaths: List<String>,
    private val listener: TransferListener,
    val boundIp: String = "0.0.0.0",
) : NanoHTTPD(boundIp, port) {

    private var isShuttingDown = false
    private val tag = "WebDAVServer:$port"

    init {
        DigestAuthManager.setCredentials(
            WebDAVService.username.value,
            WebDAVService.password.value
        )
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(tag, "Server started at ${rootDirectory.absolutePath}")
        } catch (e: IOException) {
            Log.e(tag, "Could not start server on port $port", e)
        }
    }

    // ── Entry point ──────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInternal(session)
        } catch (e: Exception) {
            // Any unhandled exception (e.g. overwhelmed under concurrent load) returns
            // 403 instead of 400. Windows treats 403 as retriable; 400 causes it to
            // give up and show the misleading "file too large" error.
            Log.e(tag, "Unhandled error in serve(): ${e.message}", e)
            newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Server busy, please retry.")
        }
    }

    private fun serveInternal(session: IHTTPSession): Response {
        if (boundIp == "0.0.0.0") {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "No valid network interface available."
            )
        }
        if (isShuttingDown) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Server is shutting down.")
        }

        if (!WebDAVService.isNetworkTrusted.value) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Network not trusted."
            )
        }

        // OPTIONS must always pass unauthenticated – Windows probes this first
        // before it even has credentials to send.
        if (session.method != Method.OPTIONS && WebDAVService.isAuthEnabled.value) {
            val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"
            val authHeader = session.headers["authorization"] ?: ""

            val isAuthenticated = when {
                // 1. Session is already cached (covers LOCK after prior auth)
                DigestAuthManager.isSessionCached(sessionKey) -> true
                // 2. Fresh valid digest credentials in this request
                authHeader.isNotEmpty() && DigestAuthManager.validateAuthorization(authHeader, session.method.name) -> {
                    DigestAuthManager.cacheSession(sessionKey)
                    true
                }
                else -> false
            }

            if (!isAuthenticated) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "Authentication Required"
                )
                res.addHeader("WWW-Authenticate", DigestAuthManager.buildWwwAuthenticateHeader())
                res.addHeader("DAV", "1, 2")
                return res
            }
        }

        val uri        = session.uri
        val method     = session.method
        val targetFile = File(rootDirectory, uri.trimStart('/'))
        val targetPath = targetFile.absolutePath
        val fileName   = targetFile.name
        val isDesktopIni = fileName.equals("desktop.ini", ignoreCase = true)
        val isRoot       = uri == "/" || uri.isEmpty()

        val isAllowed = isRoot || isDesktopIni || allowedPaths.any { sharedPath ->
            targetPath == sharedPath || targetPath.startsWith("$sharedPath/")
        }

        if (!isAllowed) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access Denied")
        }

        return when (method) {
            Method.OPTIONS  -> serveOptions()
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
            Method.DELETE   -> handleDelete(targetFile)
            Method.PUT      -> handlePut(session, targetFile)
            Method.MKCOL    -> handleMkcol(targetFile)
            Method.PROPPATCH -> handleProppatch(session, targetFile)
            Method.MOVE     -> handleMove(session, targetFile)
            Method.LOCK     -> serveLock(uri)
            Method.UNLOCK   -> newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            Method.HEAD     -> if (targetFile.exists()) {
                val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
                res.addHeader("Content-Length", targetFile.length().toString())
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("ETag", "\"${targetFile.lastModified()}-${targetFile.length()}\"")
                res
            } else serveNotFound()
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Not Supported")
        }
    }

    // ── Virtual desktop.ini ──────────────────────────────────

    private fun serveVirtualDesktopIni(uri: String): Response {
        val folderPath = uri.removeSuffix("/desktop.ini").removeSuffix("/")
        val cleanPath  = folderPath.ifEmpty { "/" }

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
        }.replace("\n", "\r\n")

        val encodedBytes = content.toByteArray(Charsets.UTF_16LE)
        val finalBody    = ByteArray(encodedBytes.size + 2)
        finalBody[0] = 0xFF.toByte()
        finalBody[1] = 0xFE.toByte()
        System.arraycopy(encodedBytes, 0, finalBody, 2, encodedBytes.size)

        val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", finalBody.inputStream(), finalBody.size.toLong())
        res.addHeader("Content-Type", "text/plain; charset=utf-16le")
        return res
    }

    private fun getVirtualDesktopIniXml(uri: String): String {
        val now = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
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
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xmlBody)
    }

    // ── WebDAV method handlers ───────────────────────────────

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
        val tempFile   = File(target.parentFile, ".~${target.name}.${UUID.randomUUID()}.tmp")
        val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"
        val isReplace  = target.exists()

        if (isReplace && !PersistenceGuard.isSafeToDelete(context, target.absolutePath)) {
            Log.w(tag, "Safety Lock Active: Blocking PUT (replace) for ${target.name}")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Safety Lock Active")
        }

        return try {
            target.parentFile?.mkdirs()
            val inputStream   = session.inputStream
            val contentLength = session.headers["content-length"]?.toLong() ?: 0L

            DigestAuthManager.pauseTimer(sessionKey)

            // If we're replacing an existing file, lock it immediately so a
            // DELETE arriving during or after a failed upload is blocked.
            if (isReplace) {
                PersistenceGuard.markStarted(context, target.absolutePath)
            }

            tempFile.outputStream().use { output ->
                val buffer         = ByteArray(65536)
                var totalRead      = 0L
                var lastUpdateTime = 0L

                while (totalRead < contentLength) {
                    if (WebDAVService.isCancelled(target.name)) {
                        WebDAVService.clearCancel(target.name)
                        tempFile.delete()
                        // Lock stays active — original file is protected.
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer cancelled by user.")
                    }

                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime >= 500) {
                        listener.onTransferProgress(target.name, totalRead, contentLength, true)
                        lastUpdateTime = currentTime
                    }
                }
            }

            if (tempFile.length() == contentLength || (contentLength == 0L && !isReplace)) {
                if (target.exists()) target.delete()
                if (tempFile.renameTo(target)) {
                    // Upload fully completed — safe to delete now (clear any lock).
                    if (isReplace) PersistenceGuard.clear(context, target.absolutePath)
                    newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "")
                } else {
                    throw IOException("Failed to move temp file to destination")
                }
            } else {
                tempFile.delete()
                // Incomplete upload — lock stays on the original if it's a replace.
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Transfer incomplete")
            }

        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            // Lock intentionally NOT cleared — protects original on unexpected failure.
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        } finally {
            DigestAuthManager.resumeTimer(sessionKey)
            listener.onTransferComplete(target.name)
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
            val destFile    = File(rootDirectory, decodedPath.trimStart('/'))
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

    // ── Utility responses ────────────────────────────────────

    private fun serveNotFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    private fun serveLock(uri: String): Response {
        val token = "uuid:${UUID.randomUUID()}"
        val xml   = """<?xml version="1.0" encoding="utf-8" ?>
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

    // ── File serving ─────────────────────────────────────────

    private fun serveFile(target: File, session: IHTTPSession): Response {
        val fileLength  = target.length()
        val rangeHeader = session.headers["range"]
        val sessionKey  = "${session.remoteIpAddress}|${session.headers["user-agent"]}"

        return try {
            if (target.isDirectory) {
                // Build the UNC equivalent of the HTTP URL so the user knows what to paste
                // in Windows Explorer's address bar instead.
                //
                // HTTP:  http://192.168.111.27:8080/Download
                // UNC:   \\192.168.111.27@8080\DavWWWRoot\Download
                val host    = session.headers["host"] ?: "$boundIp:$port"  // e.g. "192.168.111.27:8080"
                val parts   = host.split(":")
                val ip      = parts[0]
                val portStr = if (parts.size > 1) parts[1] else "80"

                // URI path after the host, e.g. "/Download" or "/"
                val uriPath = session.uri.trimEnd('/')

                // UNC base: \\ip@port\DavWWWRoot
                val uncBase = """\\$ip@$portStr\DavWWWRoot"""

                // Append sub-path if not root, converting forward-slashes to backslashes
                val uncSubPath = if (uriPath.isEmpty() || uriPath == "/") ""
                else uriPath.replace('/', '\\')
                val uncFull = "$uncBase$uncSubPath"

                val html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>WebDAV Server</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: #f0f2f5;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 24px;
    }
    .card {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.10);
      max-width: 560px;
      width: 100%;
      padding: 40px 36px 32px;
    }
    h1 {
      font-size: 22px;
      font-weight: 700;
      color: #1a1a2e;
      margin-bottom: 10px;
    }
    p {
      font-size: 15px;
      color: #555;
      line-height: 1.6;
      margin-bottom: 16px;
    }
    .unc-box {
      background: #f6f8fa;
      border: 1.5px solid #d0d7de;
      border-radius: 8px;
      padding: 14px 16px;
      margin: 20px 0;
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .unc-path {
      font-family: "Cascadia Code", "Consolas", "Courier New", monospace;
      font-size: 14px;
      color: #0969da;
      word-break: break-all;
      flex: 1;
      user-select: all;
    }
    .copy-btn {
      background: #0969da;
      color: #fff;
      border: none;
      border-radius: 6px;
      padding: 8px 14px;
      font-size: 13px;
      cursor: pointer;
      white-space: nowrap;
      transition: background 0.15s;
    }
    .copy-btn:hover { background: #0753b0; }
    .copy-btn.copied { background: #1a7f37; }
    .steps {
      background: #d5eff6;
      border-left: 4px solid #2baed5;
      border-radius: 0 8px 8px 0;
      padding: 14px 16px;
      margin-top: 8px;
    }
    .steps p { margin-bottom: 6px; color: #555; font-size: 14px; }
    .steps p:last-child { margin-bottom: 0; }
    .steps strong { color: #333; }
    .footer {
      margin-top: 28px;
      font-size: 12px;
      color: #aaa;
      text-align: center;
    }
  </style>
</head>
<body>
  <div class="card">
    <img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsIAAA7CARUoSoAAAN3lY2FCWAAA3eVqdW1iAAAAHmp1bWRjMnBhABEAEIAAAKoAOJtxA2MycGEAAAA29Gp1bWIAAABHanVtZGMybWEAEQAQgAAAqgA4m3EDdXJuOmMycGE6ZTk0YmI5OGQtMjVmNS00N2QwLTgwZGItOTZhOGU1OTRjZGU5AAAAAcFqdW1iAAAAKWp1bWRjMmFzABEAEIAAAKoAOJtxA2MycGEuYXNzZXJ0aW9ucwAAAADlanVtYgAAAClqdW1kY2JvcgARABCAAACqADibcQNjMnBhLmFjdGlvbnMudjIAAAAAtGNib3KhZ2FjdGlvbnOCo2ZhY3Rpb25sYzJwYS5jcmVhdGVkbXNvZnR3YXJlQWdlbnS/ZG5hbWVmR1BULTRv/3FkaWdpdGFsU291cmNlVHlwZXhGaHR0cDovL2N2LmlwdGMub3JnL25ld3Njb2Rlcy9kaWdpdGFsc291cmNldHlwZS90cmFpbmVkQWxnb3JpdGhtaWNNZWRpYaFmYWN0aW9ubmMycGEuY29udmVydGVkAAAAq2p1bWIAAAAoanVtZGNib3IAEQAQgAAAqgA4m3EDYzJwYS5oYXNoLmRhdGEAAAAAe2Nib3KlamV4Y2x1c2lvbnOBomVzdGFydBghZmxlbmd0aBk3JmRuYW1lbmp1bWJmIG1hbmlmZXN0Y2FsZ2ZzaGEyNTZkaGFzaFgg8TLDEdck6wrhYkGXJDhcslkdNmxdyrd24iQFNskTshdjcGFkSAAAAAAAAAAAAAAB7Wp1bWIAAAAnanVtZGMyY2wAEQAQgAAAqgA4m3EDYzJwYS5jbGFpbS52MgAAAAG+Y2JvcqZqaW5zdGFuY2VJRHgseG1wOmlpZDo1YjIyZTY5My01NDk0LTRmYmMtODk5NC1jMWI1YzZjOTZhYTZ0Y2xhaW1fZ2VuZXJhdG9yX2luZm+/ZG5hbWVnQ2hhdEdQVHdvcmcuY29udGVudGF1dGguYzJwYV9yc2UwLjAuMP9pc2lnbmF0dXJleE1zZWxmI2p1bWJmPS9jMnBhL3VybjpjMnBhOmU5NGJiOThkLTI1ZjUtNDdkMC04MGRiLTk2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AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA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aWSjZGNvZGV4GWFzc2VydGlvbi5oYXNoZWRVUkkubWF0Y2hjdXJseF5zZWxmI2p1bWJmPS9jMnBhL3VybjpjMnBhOmU5NGJiOThkLTI1ZjUtNDdkMC04MGRiLTk2YThlNTk0Y2RlOS9jMnBhLmFzc2VydGlvbnMvYzJwYS5hY3Rpb25zLnYya2V4cGxhbmF0aW9ueD5oYXNoZWQgdXJpIG1hdGNoZWQ6IHNlbGYjanVtYmY9YzJwYS5hc3NlcnRpb25zL2MycGEuYWN0aW9ucy52MqNkY29kZXgZYXNzZXJ0aW9uLmhhc2hlZFVSSS5tYXRjaGN1cmx4XXNlbGYjanVtYmY9L2MycGEvdXJuOmMycGE6ZTk0YmI5OGQtMjVmNS00N2QwLTgwZGItOTZhOGU1OTRjZGU5L2MycGEuYXNzZXJ0aW9ucy9jMnBhLmhhc2guZGF0YWtleHBsYW5hdGlvbng9aGFzaGVkIHVyaSBtYXRjaGVkOiBzZWxmI2p1bWJmPWMycGEuYXNzZXJ0aW9ucy9jMnBhLmhhc2guZGF0YaNkY29kZXgYYXNzZXJ0aW9uLmRhdGFIYXNoLm1hdGNoY3VybHhdc2VsZiNqdW1iZj0vYzJwYS91cm46YzJwYTplOTRiYjk4ZC0yNWY1LTQ3ZDAtODBkYi05NmE4ZTU5NGNkZTkvYzJwYS5hc3NlcnRpb25zL2MycGEuaGFzaC5kYXRha2V4cGxhbmF0aW9ub2RhdGEgaGFzaCB2YWxpZG1pbmZvcm1hdGlvbmFsgGdmYWlsdXJlgGppbnN0YW5jZUlEeCx4bXA6aWlkOmI0MGJiNzdjLWNiNzQtNGRlMC1iMmUzLTEwNDI1NDJiM2Q4Mm5hY3RpdmVNYW5pZmVzdKNjdXJseD5zZWxmI2p1bWJmPS9jMnBhL3VybjpjMnBhOmU5NGJiOThkLTI1ZjUtNDdkMC04MGRiLTk2YThlNTk0Y2RlOWNhbGdmc2hhMjU2ZGhhc2hYIA3lc10/ruhYAKGxSoYu2o1LwAcPEOJyZpEewft2mmNTbmNsYWltU2lnbmF0dXJlo2N1cmx4TXNlbGYjanVtYmY9L2MycGEvdXJuOmMycGE6ZTk0YmI5OGQtMjVmNS00N2QwLTgwZGItOTZhOGU1OTRjZGU5L2MycGEuc2lnbmF0dXJlY2FsZ2ZzaGEyNTZkaGFzaFggoZodMH+GKWH2OIdhurbM7Bp10oRWa8MYHb69ldiLNPhpdGh1bWJuYWlsomN1cmx4NHNlbGYjanVtYmY9YzJwYS5hc3NlcnRpb25zL2MycGEudGh1bWJuYWlsLmluZ3JlZGllbnRkaGFzaFggVis94iYd4jHt5NeRhVZFG5GqOhyydH2hN1zgABXADp0AAADManVtYgAAAClqdW1kY2JvcgARABCAAACqADibcQNjMnBhLmFjdGlvbnMudjIAAAAAm2Nib3KhZ2FjdGlvbnOBomZhY3Rpb25rYzJwYS5vcGVuZWRqcGFyYW1ldGVyc79raW5ncmVkaWVudHOBomN1cmx4LXNlbGYjanVtYmY9YzJwYS5hc3NlcnRpb25zL2MycGEuaW5ncmVkaWVudC52M2RoYXNoWCASzW4hk96aFg+DHkxJG/wXQIPtxV5VKsW2+ZG384ja+P8AAACtanVtYgAAAChqdW1kY2JvcgARABCAAACqADibcQNjMnBhLmhhc2guZGF0YQAAAAB9Y2JvcqVqZXhjbHVzaW9uc4GiZXN0YXJ0GCFmbGVuZ3RoGdWpZG5hbWVuanVtYmYgbWFuaWZlc3RjYWxnZnNoYTI1NmRoYXNoWCDxMsMR1yTrCuFiQZckOFyyWR02bF3Kt3biJAU2yROyF2NwYWRKAAAAAAAAAAAAAAAAAr9qdW1iAAAAJ2p1bWRjMmNsABEAEIAAAKoAOJtxA2MycGEuY2xhaW0udjIAAAACkGNib3Knamluc3RhbmNlSUR4LHhtcDppaWQ6ZmViODFjYjItMDY2Ny00NjY4LWE1YzctNTA0MDNkMjE5ZmVhdGNsYWltX2dlbmVyYXRvcl9pbmZvv2RuYW1lZ0NoYXRHUFR3b3JnLmNvbnRlbnRhdXRoLmMycGFfcnNlMC4wLjD/aXNpZ25hdHVyZXhNc2VsZiNqdW1iZj0vYzJwYS91cm46YzJwYTo0ODNiNGM5Mi0zZmY2LTQyZDgtYTMyNS1mYzhjNjk0N2QxN2EvYzJwYS5zaWduYXR1cmVyY3JlYXRlZF9hc3NlcnRpb25zhKJjdXJseDRzZWxmI2p1bWJmPWMycGEuYXNzZXJ0aW9ucy9jMnBhLnRodW1ibmFpbC5pbmdyZWRpZW50ZGhhc2hYIFYrPeImHeIx7eTXkYVWRRuRqjoc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AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPZYQPeL5kEQqDE0YWsiR2W4pC47eeBYvNWAqe2D1JA6I9MjwcMQkF7E2S97GVbSY4bmusQHROAHIhgsL1fX4QXpJsYAAAhIanVtYgAAAEdqdW1kYzJ1bQARABCAAACqADibcQN1cm46dXVpZDo0NmExYzNmZC0yZjdmLTQ4YTEtYTgxMC0wMThkODU1MjA1OTYAAAADMWp1bWIAAAApanVtZGMyYXMAEQAQgAAAqgA4m3EDYzJwYS5hc3NlcnRpb25zAAAAASFqdW1iAAAALGp1bWRjYm9yABEAEIAAAKoAOJtxA2MycGEuaW5ncmVkaWVudC52MgAAAADtY2JvcqRtYzJwYV9tYW5pZmVzdKNjYWxnZnNoYTI1NmRoYXNoeCxEZVZ6WFQrdTZGZ0FvYkZLaGk3YWpVdkFCdzhRNG5KbWtSN0IrM2FhWTFNPWN1cmx4PnNlbGYjanVtYmY9L2MycGEvdXJuOmMycGE6ZTk0YmI5OGQtMjVmNS00N2QwLTgwZGItOTZhOGU1OTRjZGU5aWRjOmZvcm1hdGlpbWFnZS9wbmdoZGM6dGl0bGV4G1JlcG9ydGVkIGFzIGdlbmVyYXRlZCBieSBBSWxyZWxhdGlvbnNoaXBrY29tcG9uZW50T2YAAADkanVtYgAAAClqdW1kY2JvcgARABCAAACqADibcQNjMnBhLmFjdGlvbnMudjIAAAAAs2Nib3KhZ2FjdGlvbnOBo2ZhY3Rpb25rYzJwYS5lZGl0ZWRrZGVzY3JpcHRpb254QEVkaXRlZCBvZmZsaW5lIHdpdGhvdXQgdHJ1c3RlZCBjZXJ0aWZpY2F0ZSBhbmQgc2VjdXJlIHNpZ25hdHVyZS5tc29mdHdhcmVBZ2VudKJkbmFtZXRQYWludCBhcHAgb24gV2luZG93c2d2ZXJzaW9ubTExLjI1MTIuMjExLjAAAAD7anVtYgAAACxqdW1kY2JvcgARABCAAACqADibcQNjMnBhLmluZ3JlZGllbnQudjIAAAAAx2Nib3KkaGRjOnRpdGxlb1BhcmVudCBtYW5pZmVzdGlkYzpmb3JtYXRgbHJlbGF0aW9uc2hpcGhwYXJlbnRPZm1jMnBhX21hbmlmZXN0o2NhbGdmc2hhMjU2Y3VybHg9c2VsZiNqdW1iZj1jMnBhL3VybjpjMnBhOjQ4M2I0YzkyLTNmZjYtNDJkOC1hMzI1LWZjOGM2OTQ3ZDE3YWRoYXNoWCDLhouUbWrTtnPlzOPJj9wNggzeo8z9z4Ixtj9HRctQ9wAAAwpqdW1iAAAAJGp1bWRjMmNsABEAEIAAAKoAOJtxA2MycGEuY2xhaW0AAAAC3mNib3KnY2FsZ2ZzaGEyNTZpZGM6Zm9ybWF0aWltYWdlL3BuZ2lzaWduYXR1cmV4THNlbGYjanVtYmY9YzJwYS91cm46dXVpZDo0NmExYzNmZC0yZjdmLTQ4YTEtYTgxMC0wMThkODU1MjA1OTYvYzJwYS5zaWduYXR1cmVqaW5zdGFuY2VJRHgtdXJuOnV1aWQ6MjQ0NGY3NmUtMzQxYy00MzA4LWJhOWEtMTNhY2Q1NzU1YmU3b2NsYWltX2dlbmVyYXRvcnFMb2NhbGx5IGdlbmVyYXRlZHRjbGFpbV9nZW5lcmF0b3JfaW5mb4GhZG5hbWVxTG9jYWxseSBnZW5lcmF0ZWRqYXNzZXJ0aW9uc4OjY2FsZ2ZzaGEyNTZjdXJseGBzZWxmI2p1bWJmPWMycGEvdXJuOnV1aWQ6NDZhMWMzZmQtMmY3Zi00OGExLWE4MTAtMDE4ZDg1NTIwNTk2L2MycGEuYXNzZXJ0aW9ucy9jMnBhLmluZ3JlZGllbnQudjJkaGFzaFggETfwB2PD7nAbRF8/OGaIBFpieTeQ2iMA+p0Odupj5hCjY2FsZ2ZzaGEyNTZjdXJseF1zZWxmI2p1bWJmPWMycGEvdXJuOnV1aWQ6NDZhMWMzZmQtMmY3Zi00OGExLWE4MTAtMDE4ZDg1NTIwNTk2L2MycGEuYXNzZXJ0aW9ucy9jMnBhLmFjdGlvbnMudjJkaGFzaFggVZ84hwPtQ9qaYfxnCtUKwgcQi1e0VzA25GtOjal0tPujY2FsZ2ZzaGEyNTZjdXJseGBzZWxmI2p1bWJmPWMycGEvdXJuOnV1aWQ6NDZhMWMzZmQtMmY3Zi00OGExLWE4MTAtMDE4ZDg1NTIwNTk2L2MycGEuYXNzZXJ0aW9ucy9jMnBhLmluZ3JlZGllbnQudjJkaGFzaFgg2Nen3MsNB7IMa1yCxl/8iVUZ90ZbALhIE++6bKgKhwQAAAG+anVtYgAAAChqdW1kYzJjcwARABCAAACqADibcQNjMnBhLnNpZ25hdHVyZQAAAAGOY2JvctKEQ6EBJqFneDVjaGFpboFZATAwggEsMIHUoAMCAQICAQEwCgYIKoZIzj0EAwIwHjEcMBoGA1UEAwwTTWljcm9zb2Z0IFBhaW50IGFwcDAeFw0yNjAzMTQxMzMyMjZaFw0yNzAzMTQxMzMyMjZaMCMxITAfBgNVBAMMGE1pY3Jvc29mdCBQYWludCBhcHAgVXNlcjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABK/yy/OpZg4huhREsiH3TLXeIcH1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" />
    <h1>NetworkShare WebDAV File Server</h1>
    <p>
      This address is a <strong>NetworkShare WebDAV server</strong> running on your local network.
      It cannot be browsed directly in a web browser &mdash; you need to open it
      using <strong>Windows Explorer</strong> or another WebDAV-compatible client.
    </p>
    <p>Copy the network path below and paste it into the address bar of Windows Explorer:</p>
    <div class="unc-box">
      <span class="unc-path" id="uncPath">$uncFull</span>
      <button class="copy-btn" id="copyBtn" onclick="copyPath()">Copy</button>
    </div>
    <div class="steps">
      <p><strong>How to open in Windows Explorer:</strong></p>
      <p>1. Press <strong>Win + E</strong> to open File Explorer.</p>
      <p>2. Click the address bar at the top.</p>
      <p>3. Paste the path above and press <strong>Enter</strong>.</p>
      <p>4. Enter your username and password when prompted.</p>
    </div>
    <div class="footer">WebDAV Server &bull; Access via WebDAV client only</div>
  </div>
  <script>
    function copyPath() {
      const text = document.getElementById('uncPath').textContent;
      const btn  = document.getElementById('copyBtn');

      function onSuccess() {
        btn.textContent = 'Copied!';
        btn.classList.add('copied');
        setTimeout(function() {
          btn.textContent = 'Copy';
          btn.classList.remove('copied');
        }, 2000);
      }

      // Modern API works on HTTPS; falls back to execCommand on plain HTTP
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(onSuccess).catch(fallback);
      } else {
        fallback();
      }

      function fallback() {
        // Create a temporary textarea, select its content, copy, then remove it
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none;';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        try {
          document.execCommand('copy');
          onSuccess();
        } catch(e) {
          // Last resort: select the visible text so the user can Ctrl+C manually
          const range = document.createRange();
          range.selectNode(document.getElementById('uncPath'));
          window.getSelection().removeAllRanges();
          window.getSelection().addRange(range);
          btn.textContent = 'Press Ctrl+C';
          setTimeout(function() { btn.textContent = 'Copy'; }, 3000);
        }
        document.body.removeChild(ta);
      }
    }
  </script>
</body>
</html>"""
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html; charset=utf-8", html)
            }

            var startOffset = 0L
            var endOffset   = fileLength - 1
            var isPartial   = false

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isPartial = true
                val range = rangeHeader.substring(6).split("-")
                startOffset = range[0].toLongOrNull() ?: 0L
                if (range.size > 1 && range[1].isNotEmpty()) {
                    endOffset = range[1].toLongOrNull() ?: (fileLength - 1)
                }
            }

            val dataToDeliver = endOffset - startOffset + 1
            val fis           = FileInputStream(target)
            if (startOffset > 0) fis.skip(startOffset)
            val path = target.absolutePath

            if (!isPartial) DigestAuthManager.pauseTimer(sessionKey) // ← freeze idle countdown

            val fileStream = object : FileInputStream(fis.fd) {
                var totalBytesRead = 0L
                var lastUpdateTime = 0L

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (WebDAVService.isCancelled(target.name)) {
                        WebDAVService.clearCancel(target.name)
                        throw IOException("Transfer cancelled by user")
                    }
                    val maxToRead = min(len.toLong(), dataToDeliver - totalBytesRead).toInt()
                    if (maxToRead <= 0) return -1

                    val bytesRead = fis.read(b, off, maxToRead)
                    if (bytesRead != -1) {
                        totalBytesRead += bytesRead
                        if (!isPartial) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                listener.onTransferProgress(target.name, totalBytesRead, dataToDeliver, false)
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                    return bytesRead
                }

                override fun close() {
                    fis.close()
                    if (!isPartial) {
                        DigestAuthManager.resumeTimer(sessionKey) // ← resume idle countdown
                        listener.onTransferComplete(target.name)
                    }
                    super.close()

                    if (!isPartial && totalBytesRead < dataToDeliver) {
                        Log.w(tag, "Transfer failed/interrupted at $totalBytesRead/$dataToDeliver bytes. Safety lock maintained.")
                        PersistenceGuard.markStarted(context, path)
                    } else if (!isPartial && totalBytesRead == dataToDeliver) {
                        Log.d(tag, "Transfer completed successfully. File is now safe to be deleted.")
                    }
                }
            }

            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val res    = newFixedLengthResponse(status, "application/octet-stream", fileStream, dataToDeliver)

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

    // ── PROPFIND ─────────────────────────────────────────────

    private fun servePropfind(target: File, session: IHTTPSession): Response {
        val xml = StringBuilder("""<?xml version="1.0" encoding="utf-8" ?>""")
        xml.append("""<D:multistatus xmlns:D="DAV:">""")
        xml.append(getFilePropertiesXml(target, session.uri))

        val depth = session.headers["depth"] ?: "1"

        if (target.isDirectory && depth != "0") {
            target.listFiles()?.filter { child ->
                !child.name.startsWith(".~") // hide in-progress temp files
            }?.forEach { child ->
                val childPath = child.absolutePath
                val isVisible = allowedPaths.any { allowed ->
                    childPath == allowed ||
                            allowed.startsWith("$childPath/") ||
                            childPath.startsWith("$allowed/")
                }
                if (isVisible) {
                    val baseUri  = session.uri.removeSuffix("/")
                    val childUri = "$baseUri/${child.name}"
                    xml.append(getFilePropertiesXml(child, childUri))
                }
            }
        }

        xml.append("</D:multistatus>")
        return newFixedLengthResponse(Response.Status.lookup(207), "application/xml; charset=utf-8", xml.toString())
    }

    private fun getFilePropertiesXml(file: File, uri: String): String {
        val isDir        = file.isDirectory
        val sdf          = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val creationDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(file.lastModified()))
        val etag         = "\"${file.lastModified()}-${file.length()}\""

        val safeDisplayName = file.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val safeUri         = uri.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        val androidDir  = File(rootDirectory, "Android").absolutePath
        val currentPath = file.absolutePath
        val isWithinAndroid = currentPath == androidDir || currentPath.startsWith("$androidDir/")
        val isRootChild     = file.parentFile?.absolutePath == rootDirectory.absolutePath
        val iconFolders     = listOf("DCIM", "Documents", "Download", "Movies", "Music", "NetworkShare", "Pictures")

        val attributes = when {
            isWithinAndroid -> "0x00000004"
            isDir && isRootChild && iconFolders.any { it.equals(file.name, ignoreCase = true) } -> "0x00000001"
            isDir -> "0x00000010"
            else  -> "0x00000020"
        }

        return """
            <D:response xmlns:Z="urn:schemas-microsoft-com:">
                <D:href>$safeUri</D:href>
                <D:propstat>
                    <D:prop>
                        <D:displayname>$safeDisplayName</D:displayname>
                        <D:getcontentlength>${if (isDir) 0 else file.length()}</D:getcontentlength>
                        <D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>
                        <Z:Win32FileAttributes>$attributes</Z:Win32FileAttributes>
                        <D:getlastmodified>${sdf.format(Date(file.lastModified()))}</D:getlastmodified>
                        <D:creationdate>$creationDate</D:creationdate>
                        <D:getetag>$etag</D:getetag>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
            </D:response>
        """.trimIndent()
    }

    // ── Lifecycle ────────────────────────────────────────────

    fun stopServer() {
        isShuttingDown = true
        Thread.sleep(500)
        stop()
    }
}

// ─────────────────────────────────────────────────────────────
//  Transfer listener interface
// ─────────────────────────────────────────────────────────────
interface TransferListener {
    fun onTransferProgress(fileName: String, currentBytes: Long, totalBytes: Long, isDownload: Boolean)
    fun onTransferComplete(fileName: String)
}
