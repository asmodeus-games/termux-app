package com.termux.ssh

import com.jcraft.jsch.Proxy
import java.io.InputStream
import java.io.OutputStream
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Request
import okhttp3.OkHttpClient
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareWebSocketProxy(
    private val targetHost: String,
    private val logCallback: (String) -> Unit = {}
) : Proxy {

    private var socket: java.net.Socket? = null
    private val inPipe = java.io.PipedInputStream(65536)
    private val inOutPipe = java.io.PipedOutputStream(inPipe)
    private val outPipe = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val slice = b.copyOfRange(off, off + len)
            val byteString = slice.toByteString()
            val ws = webSocket
            if (ws != null) {
                ws.send(byteString)
            } else {
                throw java.io.IOException("WebSocket connection is not active")
            }
        }
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun connect(socketFactory: com.jcraft.jsch.SocketFactory?, host: String, port: Int, timeout: Int) {
        val hostToConnect = if (targetHost.isNotBlank() && targetHost != "%h") targetHost else host
        val cleanHost = hostToConnect.removePrefix("http://").removePrefix("https://").removePrefix("wss://").trim()

        val candidateUrls = listOf(
            "wss://$cleanHost/cdn-cgi/access/ssh",
            "wss://$cleanHost/ws",
            "wss://$cleanHost/cdn-cgi/access/tok",
            "wss://$cleanHost/"
        )

        var lastError: String? = null
        var success = false

        for (wsUrl in candidateUrls) {
            if (success) break
            logCallback("Connecting via WebSocket Tunnel ($wsUrl)...")

            val request = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", "cloudflared/2024.1.0")
                .header("Sec-WebSocket-Protocol", "cloudflared")
                .build()

            val latch = CountDownLatch(1)
            var currentError: String? = null

            val wsListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    logCallback("✅ Cloudflare WebSocket Tunnel connected via $wsUrl! Switched to SSH protocol.")
                    success = true
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        inOutPipe.write(bytes.toByteArray())
                        inOutPipe.flush()
                    } catch (_: Exception) {}
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        inOutPipe.write(text.toByteArray(Charsets.UTF_8))
                        inOutPipe.flush()
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    val httpStatus = response?.code
                    val err = "Endpoint $wsUrl failed: ${t.message ?: "Handshake rejected"} ${if (httpStatus != null) "(HTTP $httpStatus)" else ""}"
                    currentError = err
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    logCallback("Cloudflare WebSocket closed ($code: $reason)")
                }
            }

            val activeWs = client.newWebSocket(request, wsListener)
            val connectedInTime = latch.await(8000L, TimeUnit.MILLISECONDS)

            if (success && connectedInTime) {
                this.webSocket = activeWs
                break
            } else {
                try { activeWs.close(1000, "Trying next endpoint") } catch (_: Exception) {}
                lastError = currentError ?: "Connection timeout on $wsUrl"
                logCallback("⚠️ $lastError")
            }
        }

        if (!success) {
            close()
            throw java.io.IOException("Cloudflare Tunnel connection failed. $lastError")
        }
    }

    override fun getInputStream(): InputStream = inPipe
    override fun getOutputStream(): OutputStream = outPipe
    override fun getSocket(): java.net.Socket? = socket

    override fun close() {
        try { webSocket?.close(1000, "Closed") } catch (_: Exception) {}
        try { inOutPipe.close() } catch (_: Exception) {}
        try { inPipe.close() } catch (_: Exception) {}
    }
}