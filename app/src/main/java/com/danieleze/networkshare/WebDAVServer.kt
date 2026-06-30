package com.danieleze.networkshare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import androidx.core.content.edit
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private object PersistenceGuard {
    private const val PREFS_NAME = "webdav_safety_prefs"
    private const val KEY_PREFIX_VERIFIED = "verified_"
    private const val KEY_PREFIX_TIME = "time_"

    fun markStarted(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_PREFIX_VERIFIED + path, false)
            putLong(KEY_PREFIX_TIME + path, System.currentTimeMillis())
        }
    }

    fun isSafeToDelete(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isVerified = prefs.getBoolean(KEY_PREFIX_VERIFIED + path, false)
        val lastTime = prefs.getLong(KEY_PREFIX_TIME + path, 0L)

        if (lastTime == 0L) return true

        val isExpired = (System.currentTimeMillis() - lastTime) > 60000
        return isVerified || isExpired
    }

    fun clear(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_PREFIX_VERIFIED + path)
            remove(KEY_PREFIX_TIME + path)
        }
    }

    // Force clears the lock regardless of timer — only use for failed/0-byte uploads
    fun forceRelease(context: Context, path: String) {
        clear(context, path)
    }
}

private object PropertyStore {
    private const val PREFS_NAME = "webdav_properties_prefs"

    fun saveProperties(context: Context, path: String, properties: Map<String, String>) {
        val json = org.json.JSONObject(properties as Map<*, *>).toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(path, json) }
    }

    fun getProperties(context: Context, path: String): Map<String, String>? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(path, null) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (_: Exception) {
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Digest Auth Manager (embedded)
// ─────────────────────────────────────────────────────────────
private object DigestAuthManager {
    private const val REALM = "NetworkShare"
    private const val NONCE_VALIDITY_MS = 1_800_000L
    private const val SESSION_IDLE_MS = 600_000L

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

        val params = parseDigestParams(authHeader)
        val nonce = params["nonce"] ?: return false
        val uri = params["uri"] ?: return false
        val response = params["response"] ?: return false
        val user = params["username"] ?: return false
        val nc = params["nc"] ?: "00000001"
        val cnonce = params["cnonce"] ?: ""
        val qop = params["qop"]

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
        val result = mutableMapOf<String, String>()
        val content = header.removePrefix("Digest ")
        val regex = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
        regex.findAll(content).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[key] = value
        }
        return result
    }

    fun buildWwwAuthenticateHeader(stale: Boolean = false): String {
        val nonce = generateNonce()
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
                sessionKey = sessionKey,
                expiryMs = System.currentTimeMillis() + SESSION_IDLE_MS,
                isTransferring = false,
                frozenRemainingMs = SESSION_IDLE_MS
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
        state.isTransferring = true
    }

    /**
     * Call when a file transfer completes or is cancelled.
     * Resumes the countdown from wherever it was frozen.
     */
    fun resumeTimer(sessionKey: String) {
        val state = authenticatedSessions[sessionKey] ?: return
        state.isTransferring = false
        state.expiryMs = System.currentTimeMillis() + state.frozenRemainingMs
    }
}

abstract class FastHTTPD(
    boundIp: String,
    port: Int
) : NanoHTTPD(boundIp, port) {

    companion object {
        /**
         * 1MB socket read buffer.
         * NanoHTTPD's default is 8192 (8KB) — 128x smaller.
         * This is the #1 reason for slow transfers.
         */
        const val FAST_BUFFER_SIZE = 1024 * 1024  // 1 MB

        /**
         * Socket send buffer — tells the OS how much data to
         * keep in the kernel send queue. Larger = better for
         * file downloads.
         */
        const val SOCKET_SEND_BUFFER = 2 * 1024 * 1024  // 2 MB

        /**
         * Socket receive buffer — for uploads from client.
         */
        const val SOCKET_RECV_BUFFER = 2 * 1024 * 1024  // 2 MB
    }

    init {
        // ── Replace the default one-thread-per-connection runner ──
        // NanoHTTPD's DefaultAsyncRunner creates a brand new Thread
        // for every request. This causes GC pressure and context-switch
        // overhead under load.
        //
        // CachedThreadPool reuses idle threads, dramatically reducing
        // overhead for rapid consecutive requests (e.g. Windows Explorer
        // sending PROPFIND before every file transfer).
        setAsyncRunner(PooledAsyncRunner())
    }

    // ── Pooled runner ────────────────────────────────────────────

    /**
     * Thread pool based AsyncRunner.
     *
     * Uses a SynchronousQueue so tasks are handed off to a thread
     * immediately — no queuing delay. Idle threads are kept alive
     * for 60 seconds then released.
     */
    class PooledAsyncRunner : AsyncRunner {

        private val running = CopyOnWriteArrayList<ClientHandler>()

        // CachedThreadPool equivalent but with explicit parameters
        // so we can tune keep-alive time
        private val pool = ThreadPoolExecutor(
            0,                          // no minimum threads (saves memory when idle)
            Int.MAX_VALUE,              // no hard cap on concurrent connections
            60L, TimeUnit.SECONDS,      // idle threads die after 60s
            SynchronousQueue()          // no queue — hand off immediately
        )

        override fun exec(clientHandler: ClientHandler) {
            running.add(clientHandler)
            pool.execute(clientHandler)
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        override fun closeAll() {
            // Copy list first to avoid ConcurrentModificationException
            running.toList().forEach { it.close() }
            pool.shutdown()
        }
    }

    // ── Socket tuning ────────────────────────────────────────────

    /**
     * Called by NanoHTTPD for every accepted connection.
     * We intercept here to:
     *   1. Tune the socket buffers (SO_SNDBUF, SO_RCVBUF)
     *   2. Enable TCP_NODELAY (disables Nagle's algorithm — reduces
     *      latency on small packets like WebDAV PROPFIND responses)
     *   3. Wrap the InputStream with a 1MB BufferedInputStream
     *      BEFORE NanoHTTPD's HTTPSession wraps it again with its
     *      own 8KB buffer — our outer buffer is what actually gets
     *      read from the socket kernel buffer, so size matters here.
     */
    override fun createClientHandler(
        finalAccept: Socket,
        inputStream: InputStream
    ): ClientHandler {

        // Tune the socket
        try {
            finalAccept.sendBufferSize = SOCKET_SEND_BUFFER  // 2MB kernel send buffer
            finalAccept.receiveBufferSize = SOCKET_RECV_BUFFER  // 2MB kernel recv buffer
            finalAccept.tcpNoDelay = true                 // no Nagle delay
            finalAccept.keepAlive = true                 // detect dead connections
        } catch (e: Exception) {
            // Not fatal — just means OS didn't honour the hint
            Log.w("FastHTTPD", "Socket tuning failed (non-fatal): ${e.message}")
        }

        // Wrap with large buffer before handing to NanoHTTPD
        val bufferedInput = BufferedInputStream(inputStream, FAST_BUFFER_SIZE)

        return super.createClientHandler(finalAccept, bufferedInput)
    }
}

interface WebDAVServerConfig {
    fun getUsername(): String
    fun getPassword(): String
    fun isAuthEnabled(): Boolean
    fun isNetworkTrusted(): Boolean
    fun isCancelled(fileName: String): Boolean
    fun clearCancel(fileName: String)
    fun generateToken(): String
    fun showSafetyAlert(fileName: String)
    fun getObjects(uri: String): Any?
    fun getCustomResponse(uri: String, uncPath: String): NanoHTTPD.Response? = null
    fun getDirectoryHtml(uncPath: String): String? = null
}

// ─────────────────────────────────────────────────────────────
//  WebDAV Server
// ─────────────────────────────────────────────────────────────
class WebDAVServer(
    val port: Int = 8080,
    private val context: Context,
    private val listener: TransferListener,
    private val config: WebDAVServerConfig,
    val boundIp: String = "0.0.0.0",
) : FastHTTPD(boundIp, port) {

    private var isShuttingDown = false
    private val tag = "WebDAVServer:$port"

    init {
        DigestAuthManager.setCredentials(
            config.getUsername(),
            config.getPassword()
        )
        try {
            start(SOCKET_READ_TIMEOUT, false)
            //Log.d(tag, "Server started at ${rootDirectory.absolutePath}")
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
            newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Server busy, please retry."
            )
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
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Server is shutting down."
            )
        }

        val customResponse = config.getCustomResponse(session.uri, "")
        if (customResponse != null) return customResponse

        if (!config.isNetworkTrusted()) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Network not trusted."
            )
        }

        // OPTIONS must always pass unauthenticated – Windows probes this first
        // before it even has credentials to send.
        if (session.method != Method.OPTIONS && config.isAuthEnabled()) {
            val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"
            val authHeader = session.headers["authorization"] ?: ""

            // Token bypass — read-only operations only (streaming/download via URL)
            val tokenParam = session.parameters["token"]?.firstOrNull()
            val isValidToken = !tokenParam.isNullOrBlank() &&
                    tokenParam == config.generateToken()
            val isReadOnly = session.method == Method.GET ||
                    session.method == Method.HEAD

            val isAuthenticated = when {
                isValidToken && isReadOnly -> true
                DigestAuthManager.isSessionCached(sessionKey) -> true
                authHeader.isNotEmpty() && DigestAuthManager.validateAuthorization(
                    authHeader,
                    session.method.name
                ) -> {
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

        val uri = session.uri
        val method = session.method

        val result = config.getObjects(uri)

        if (result is Int) {
            return newFixedLengthResponse(
                Response.Status.lookup(result), MIME_PLAINTEXT, "Error $result"
            )
        }
        if (result == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
            )
        }

        val targetFile = result as? File

        return when (method) {
            Method.OPTIONS -> serveOptions()
            Method.PROPFIND -> when {
                targetFile != null && targetFile.exists() -> servePropfind(targetFile, session)
                else -> serveNotFound()
            }

            Method.GET -> when {
                targetFile != null -> serveFile(targetFile, session)
                else -> serveNotFound()
            }

            Method.DELETE -> if (targetFile != null) handleDelete(targetFile) else serveNotFound()
            Method.PUT -> if (targetFile != null) handlePut(
                session,
                targetFile
            ) else serveNotFound()

            Method.MKCOL -> if (targetFile != null) handleMkcol(targetFile) else serveNotFound()
            Method.PROPPATCH -> if (targetFile != null) handleProppatch(
                session,
                targetFile
            ) else serveNotFound()

            Method.MOVE -> if (targetFile != null) handleMove(
                session,
                targetFile
            ) else serveNotFound()

            Method.COPY -> if (targetFile != null) handleCopy(
                session,
                targetFile
            ) else serveNotFound()

            Method.LOCK -> serveLock(uri)
            Method.UNLOCK -> newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            Method.HEAD -> if (targetFile != null && targetFile.exists()) {
                val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
                res.addHeader("Content-Length", targetFile.length().toString())
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("ETag", "\"${targetFile.lastModified()}-${targetFile.length()}\"")
                res
            } else serveNotFound()

            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Not Supported"
            )
        }
    }

    // ── WebDAV method handlers ───────────────────────────────

    private fun handleMkcol(target: File): Response {
        return try {
            if (target.exists()) {
                newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    MIME_PLAINTEXT,
                    "Folder already exists"
                )
            } else if (target.mkdirs()) {
                newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Created")
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Failed to create folder"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handlePut(session: IHTTPSession, target: File): Response {
        val tempFile = File(target.parentFile, ".~${target.name}.${UUID.randomUUID()}.tmp")
        val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"
        val isReplace = target.exists() && target.length() > 0L

        if (isReplace && !PersistenceGuard.isSafeToDelete(context, target.absolutePath)) {
            Log.w(tag, "Safety Lock Active: Blocking PUT (replace) for ${target.name}")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Safety Lock Active"
            )
        }

        return try {
            target.parentFile?.mkdirs()
            val inputStream = session.inputStream
            val contentLength = session.headers["content-length"]?.toLong() ?: 0L

            DigestAuthManager.pauseTimer(sessionKey)

            // If we're replacing an existing file, lock it immediately so a
            // DELETE arriving during or after a failed upload is blocked.
            if (isReplace) {
                PersistenceGuard.markStarted(context, target.absolutePath)
            }

            tempFile.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var totalRead = 0L
                var lastUpdateTime = 0L

                while (totalRead < contentLength) {
                    if (config.isCancelled(target.name)) {
                        config.clearCancel(target.name)
                        tempFile.delete()
                        // Lock stays active — original file is protected.
                        return newFixedLengthResponse(
                            Response.Status.FORBIDDEN,
                            MIME_PLAINTEXT,
                            "Transfer cancelled by user."
                        )
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
                    if (isReplace) PersistenceGuard.clear(context, target.absolutePath)
                    newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "")
                } else {
                    tempFile.delete()
                    if (target.exists() && target.length() == 0L) {
                        PersistenceGuard.forceRelease(context, target.absolutePath)
                        target.delete()
                    }
                    throw IOException("Failed to move temp file to destination")
                }
            } else {
                tempFile.delete()
                // Incomplete upload — lock stays on the original if it's a replace.
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Transfer incomplete"
                )
            }

        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            if (target.exists() && target.length() == 0L) {
                PersistenceGuard.forceRelease(context, target.absolutePath)
                target.delete()
            }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        } finally {
            DigestAuthManager.resumeTimer(sessionKey)
            listener.onTransferComplete(target.name)
        }
    }

    private fun handleProppatch(session: IHTTPSession, target: File): Response {
        val contentLength = session.headers["content-length"]?.toLong() ?: 0L
        val bodyBytes = ByteArray(contentLength.toInt())
        var totalRead = 0
        while (totalRead < contentLength) {
            val read =
                session.inputStream.read(bodyBytes, totalRead, (contentLength - totalRead).toInt())
            if (read == -1) break
            totalRead += read
        }
        val body = String(bodyBytes, 0, totalRead, Charsets.UTF_8)

        Log.d(tag, "PROPPATCH body for ${target.name}: $body")

        // Capture the COMPLETE raw element <Prefix:Tag>...</Prefix:Tag> exactly as sent — no parsing of the value
        val elementRegex = Regex("""<(\w+:\w+)>[^<]*</\1>""")
        val rawElements: Map<String, String> = elementRegex.findAll(body).associate { match ->
            val tagName = match.groupValues[1]
            val fullElement =
                match.value  // the entire literal "<Tag>value</Tag>" string, untouched
            tagName to fullElement
        }

        if (rawElements.isNotEmpty()) {
            PropertyStore.saveProperties(context, target.absolutePath, rawElements)
            Log.d(tag, "Saved raw elements verbatim for ${target.name}: $rawElements")

            // We still need the OS-level mtime updated so file listings/sorting work —
            // this reads the saved raw element just to extract the date for setLastModified,
            // it does NOT change what gets stored or returned.
            rawElements["Z:Win32LastModifiedTime"]?.let { rawElement ->
                try {
                    val valueOnly = rawElement.substringAfter(">").substringBefore("<")
                    val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val parsedDate = sdf.parse(valueOnly)
                    if (parsedDate != null) {
                        val success = target.setLastModified(parsedDate.time)
                        Log.d(
                            tag,
                            "setLastModified(${parsedDate.time}) on ${target.name} -> $success"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to parse Win32LastModifiedTime for OS mtime: ${e.message}")
                }
            }
        }

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
        return newFixedLengthResponse(
            Response.Status.lookup(207),
            "application/xml; charset=utf-8",
            xml
        )
    }

    private fun handleMove(session: IHTTPSession, target: File): Response {
        val destinationHeader = session.headers["destination"] ?: return serveNotFound()
        return try {
            val decodedPath =
                java.net.URLDecoder.decode(java.net.URL(destinationHeader).path, "UTF-8")

            val destFile: File = when (val result = config.getObjects(decodedPath)) {
                is File -> result
                is Int -> return newFixedLengthResponse(
                    Response.Status.lookup(result), MIME_PLAINTEXT, "Error $result"
                )

                else -> return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
                )
            }

            destFile.parentFile?.mkdirs()
            if (target.renameTo(destFile)) {
                newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Moved")
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Move Failed"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleCopy(session: IHTTPSession, target: File): Response {
        val destinationHeader = session.headers["destination"] ?: return serveNotFound()
        return try {
            val decodedPath =
                java.net.URLDecoder.decode(java.net.URL(destinationHeader).path, "UTF-8")

            val destFile: File = when (val result = config.getObjects(decodedPath)) {
                is File -> result
                is Int -> return newFixedLengthResponse(
                    Response.Status.lookup(result),
                    MIME_PLAINTEXT,
                    "Error $result"
                )

                else -> {
                    File(target.parent, decodedPath.substringAfterLast("/"))
                }
            }

            target.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Copied")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }


    private fun handleDelete(target: File): Response {
        val path = target.absolutePath
        if (target.isFile) {
            if (!PersistenceGuard.isSafeToDelete(context, path)) {
                config.showSafetyAlert(target.name)
                Log.w(tag, "Safety Lock Active: Blocking DELETE for ${target.name}")
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "Safety Lock Active"
                )
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
        res.addHeader(
            "Allow",
            "GET, HEAD, POST, OPTIONS, PROPFIND, PUT, MKCOL, DELETE, COPY, MOVE, LOCK, UNLOCK"
        )
        res.addHeader("DAV", "1, 2")
        res.addHeader("MS-Author-Via", "DAV")
        return res
    }

    // ── File serving ─────────────────────────────────────────

    private fun serveFile(target: File, session: IHTTPSession): Response {
        val fileLength = target.length()
        val rangeHeader = session.headers["range"]
        val sessionKey = "${session.remoteIpAddress}|${session.headers["user-agent"]}"

        return try {
            if (target.isDirectory) {
                val host = session.headers["host"] ?: "$boundIp:$port"
                val parts = host.split(":")
                val ip = parts[0]
                val portStr = if (parts.size > 1) parts[1] else "80"
                val uriPath = session.uri.trimEnd('/')
                val uncBase = """\\$ip@$portStr\"""
                val uncSubPath = if (uriPath.isEmpty() || uriPath == "/") ""
                else uriPath.replace('/', '\\')
                val uncFull = "$uncBase$uncSubPath"

                val customHtml = config.getDirectoryHtml(uncFull)
                return if (customHtml != null) {
                    newFixedLengthResponse(
                        Response.Status.FORBIDDEN, "text/html; charset=utf-8", customHtml
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.FORBIDDEN, "text/plain; charset=utf-8",
                        "This is a WebDAV server. It cannot be opened in a browser.\n\n" +
                                "To access it, use a WebDAV client and connect to:\n$uncFull"
                    )
                }
            }

            var startOffset = 0L
            var endOffset = fileLength - 1
            var isPartial = false

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isPartial = true
                val range = rangeHeader.substring(6).split("-")
                startOffset = range[0].toLongOrNull() ?: 0L
                if (range.size > 1 && range[1].isNotEmpty()) {
                    endOffset = range[1].toLongOrNull() ?: (fileLength - 1)
                }
            }

            val dataToDeliver = endOffset - startOffset + 1
            val fis = FileInputStream(target)
            if (startOffset > 0) fis.skip(startOffset)
            val bufferedFis = BufferedInputStream(fis, FAST_BUFFER_SIZE)
            val path = target.absolutePath

            if (!isPartial) DigestAuthManager.pauseTimer(sessionKey) // ← freeze idle countdown

            val fileStream = object : FileInputStream(fis.fd) {
                var totalBytesRead = 0L
                var lastUpdateTime = 0L

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (config.isCancelled(target.name)) {
                        config.clearCancel(target.name)
                        throw IOException("Transfer cancelled by user")
                    }
                    val maxToRead = min(len.toLong(), dataToDeliver - totalBytesRead).toInt()
                    if (maxToRead <= 0) return -1

                    val bytesRead = bufferedFis.read(b, off, maxToRead)
                    if (bytesRead != -1) {
                        totalBytesRead += bytesRead
                        if (!isPartial) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500) {
                                listener.onTransferProgress(
                                    target.name,
                                    totalBytesRead,
                                    dataToDeliver,
                                    false
                                )
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                    return bytesRead
                }

                override fun close() {
                    bufferedFis.close()
                    if (!isPartial) {
                        DigestAuthManager.resumeTimer(sessionKey) // ← resume idle countdown
                        listener.onTransferComplete(target.name)
                    }
                    super.close()

                    if (!isPartial && totalBytesRead < dataToDeliver) {
                        Log.w(
                            tag,
                            "Transfer failed/interrupted at $totalBytesRead/$dataToDeliver bytes. Safety lock maintained."
                        )
                        PersistenceGuard.markStarted(context, path)
                    } else if (!isPartial && totalBytesRead == dataToDeliver) {
                        Log.d(
                            tag,
                            "Transfer completed successfully. File is now safe to be deleted."
                        )
                    }
                }
            }

            val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val res = newFixedLengthResponse(
                status,
                "application/octet-stream",
                fileStream,
                dataToDeliver
            )

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
        xml.append(getObjectPropertiesXml(target, session.uri))

        val depth = session.headers["depth"] ?: "1"

        if (target.isDirectory && depth != "0") {
            target.listFiles()?.filter { child ->
                !child.name.startsWith(".~") // hide in-progress temp files
            }?.forEach { child ->
                val childResult = config.getObjects("${session.uri.trimEnd('/')}/${child.name}")
                val isVisible = childResult is File
                if (isVisible) {
                    val baseUri = session.uri.removeSuffix("/")
                    val childUri = "$baseUri/${child.name}"
                    xml.append(getObjectPropertiesXml(child, childUri))
                }
            }
        }

        xml.append("</D:multistatus>")
        return newFixedLengthResponse(
            Response.Status.lookup(207),
            "application/xml; charset=utf-8",
            xml.toString()
        )
    }

    private fun getObjectPropertiesXml(file: File, uri: String): String {
        val isDir = file.isDirectory
        val saved = PropertyStore.getProperties(context, file.absolutePath) ?: emptyMap()

        val etag = "\"${file.lastModified()}-${file.length()}\""
        val safeDisplayName =
            file.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val safeUri = uri.split("/")
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        val desktopIniUri = uri.trimEnd('/') + "/desktop.ini"
        val hasDesktopIni = isDir && (config.getObjects(desktopIniUri) as? File)?.exists() == true

        // Computed fallbacks — ONLY used when nothing was ever saved for that exact tag
        val computedAttributes: String? = when {
            isDir && hasDesktopIni -> "0x00000001"
            isDir -> "0x00000010"
            else -> null
        }
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val computedLastModified =
            "<D:getlastmodified>${sdf.format(Date(file.lastModified()))}</D:getlastmodified>"
        val computedCreationDate = "<D:creationdate>${
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.format(Date(file.lastModified()))
        }</D:creationdate>"

        // Pull raw saved elements verbatim — exactly the string Windows originally sent
        val attributesXml = saved["Z:Win32FileAttributes"]
            ?: (computedAttributes?.let { "<Z:Win32FileAttributes>$it</Z:Win32FileAttributes>" }
                ?: "")
        val lastModifiedXml = saved["Z:Win32LastModifiedTime"]?.let {
            "<D:getlastmodified>${
                it.substringAfter(">").substringBefore("<")
            }</D:getlastmodified>"
        }
            ?: computedLastModified
        val creationDateXml = saved["Z:Win32CreationTime"]?.let {
            "<D:creationdate>${
                it.substringAfter(">").substringBefore("<")
            }</D:creationdate>"
        }
            ?: computedCreationDate

        // Any other saved tags we don't explicitly know about — paste raw, no questions asked
        val knownTags =
            setOf("Z:Win32FileAttributes", "Z:Win32LastModifiedTime", "Z:Win32CreationTime")
        val otherSavedXml = saved.entries
            .filter { it.key !in knownTags }
            .joinToString("\n") { it.value }  // raw element string, untouched

        return """
        <D:response xmlns:Z="urn:schemas-microsoft-com:">
            <D:href>$safeUri</D:href>
            <D:propstat>
                <D:prop>
                    <D:displayname>$safeDisplayName</D:displayname>
                    <D:getcontentlength>${if (isDir) 0 else file.length()}</D:getcontentlength>
                    <D:resourcetype>${if (isDir) "<D:collection/>" else ""}</D:resourcetype>
                    $attributesXml
                    $lastModifiedXml
                    $creationDateXml
                    <D:getetag>$etag</D:getetag>
                    $otherSavedXml
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
    fun onTransferProgress(
        fileName: String,
        currentBytes: Long,
        totalBytes: Long,
        isDownload: Boolean
    )

    fun onTransferComplete(fileName: String)
}