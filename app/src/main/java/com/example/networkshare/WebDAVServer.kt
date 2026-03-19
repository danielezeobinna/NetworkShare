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
    private const val NONCE_VALIDITY_MS = 300_000L  // 5 minutes
    private const val SESSION_IDLE_MS   = 10_000L   // 10 seconds idle timeout

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
        val tempFile   = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"

        return try {
            target.parentFile?.mkdirs()
            val inputStream    = session.inputStream
            val contentLength  = session.headers["content-length"]?.toLong() ?: 0L

            DigestAuthManager.pauseTimer(sessionKey) // ← freeze idle countdown

            tempFile.outputStream().use { output ->
                val buffer         = ByteArray(65536)
                var totalRead      = 0L
                var lastUpdateTime = 0L

                while (totalRead < contentLength) {
                    if (WebDAVService.isCancelled(target.name)) {
                        WebDAVService.clearCancel(target.name)
                        tempFile.delete()
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

            if (tempFile.length() == contentLength || contentLength == 0L) {
                if (target.exists()) target.delete()
                if (tempFile.renameTo(target)) {
                    newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "")
                } else {
                    throw IOException("Failed to move temp file to destination")
                }
            } else {
                tempFile.delete()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Transfer incomplete")
            }

        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        } finally {
            DigestAuthManager.resumeTimer(sessionKey) // ← resume idle countdown
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
            if (target.isDirectory)
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Directory GET forbidden")

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
            target.listFiles()?.forEach { child ->
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
