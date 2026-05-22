package com.danieleze.networkshare

import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
            android.util.Log.w("FastHTTPD", "Socket tuning failed (non-fatal): ${e.message}")
        }

        // Wrap with large buffer before handing to NanoHTTPD
        val bufferedInput = BufferedInputStream(inputStream, FAST_BUFFER_SIZE)

        return super.createClientHandler(finalAccept, bufferedInput)
    }
}
