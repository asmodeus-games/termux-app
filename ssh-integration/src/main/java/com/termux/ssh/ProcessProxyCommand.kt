package com.termux.ssh

import android.content.Context
import android.os.Build
import com.jcraft.jsch.Proxy
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

class ProcessProxyCommand(
    private val commandTemplate: String,
    private val context: Context,
    private val logCallback: (String) -> Unit = {}
) : Proxy {
    private var process: Process? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private fun ensureCloudflaredBinary(): String? {
        val candidatePaths = listOf(
            "${context.filesDir.absolutePath}/cloudflared",
            "/data/data/com.termux/files/usr/bin/cloudflared",
            "/data/data/com.termux/files/home/go/bin/cloudflared",
            "/data/data/com.termux/files/home/bin/cloudflared",
            "/data/local/tmp/cloudflared"
        )
        val existing = candidatePaths.firstOrNull { File(it).exists() && File(it).canExecute() }
        if (existing != null) return existing

        val targetFile = File(context.filesDir, "cloudflared")
        if (targetFile.exists() && targetFile.length() > 100000) {
            targetFile.setExecutable(true, false)
            return targetFile.absolutePath
        }

        logCallback("⚡ 'cloudflared' binary not found on Android. Auto-downloading Cloudflare binary...")
        val abis = Build.SUPPORTED_ABIS
        val isArm64 = abis.any { it.contains("arm64") || it.contains("aarch64") }
        val downloadUrl = if (isArm64) {
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        } else {
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm"
        }

        return try {
            var currentUrl = downloadUrl
            var connection: HttpURLConnection? = null
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308) {
                    val loc = connection.getHeaderField("Location")
                    if (loc != null) {
                        currentUrl = loc
                        redirects++
                    } else break
                } else break
            }

            connection?.let { conn ->
                if (conn.responseCode == 200) {
                    val tempFile = File(context.filesDir, "cloudflared_tmp")
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 100000) {
                        tempFile.renameTo(targetFile)
                        targetFile.setExecutable(true, false)
                        logCallback("✅ 'cloudflared' downloaded successfully (${targetFile.length() / 1024} KB)")
                        return targetFile.absolutePath
                    }
                } else {
                    logCallback("❌ Could not download cloudflared: HTTP ${conn.responseCode}")
                }
            }
            null
        } catch (e: Exception) {
            logCallback("❌ Error downloading cloudflared: ${e.message}")
            null
        }
    }

    override fun connect(socketFactory: com.jcraft.jsch.SocketFactory?, host: String, port: Int, timeout: Int) {
        var command = commandTemplate.replace("%h", host).replace("%p", port.toString())

        if (command.trim().startsWith("cloudflared ") || command.trim() == "cloudflared") {
            val resolvedPath = ensureCloudflaredBinary()
            if (resolvedPath != null) {
                command = command.trim().replaceFirst("cloudflared", resolvedPath)
                logCallback("Using cloudflared binary at: $resolvedPath")
            } else {
                logCallback("ProxyErr: 'cloudflared' binary is not accessible on this device.")
                logCallback("ProxyErr: Tip: If connecting directly to server, clear ProxyCommand in Server Settings.")
            }
        }

        val termuxSh = File("/data/data/com.termux/files/usr/bin/sh")
        val shellPath = if (termuxSh.exists() && termuxSh.canExecute()) termuxSh.absolutePath else "/system/bin/sh"

        logCallback("Executing ProxyCommand: $command")

        val pb = ProcessBuilder(shellPath, "-c", command)
        val env = pb.environment()
        val termuxUsr = "/data/data/com.termux/files/usr"
        val termuxHome = "/data/data/com.termux/files/home"
        val currentPath = env["PATH"] ?: "/system/bin:/system/xbin"
        env["PATH"] = "${context.filesDir.absolutePath}:$termuxUsr/bin:$termuxHome/go/bin:$termuxHome/bin:$currentPath:/data/local/tmp"
        if (File(termuxUsr).exists()) {
            env["PREFIX"] = termuxUsr
            env["LD_LIBRARY_PATH"] = "$termuxUsr/lib"
            env["HOME"] = termuxHome
            env["TMPDIR"] = "$termuxUsr/tmp"
        }

        val proc = pb.start()
        process = proc
        inStream = proc.inputStream
        outStream = proc.outputStream

        Thread {
            try {
                proc.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { logCallback("ProxyErr: $it") }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun getInputStream(): InputStream {
        return inStream ?: throw java.io.IOException("Proxy stream is null")
    }

    override fun getOutputStream(): OutputStream {
        return outStream ?: throw java.io.IOException("Proxy stream is null")
    }

    override fun getSocket(): java.net.Socket? = null

    override fun close() {
        try {
            outStream?.close()
            inStream?.close()
            process?.destroy()
        } catch (_: Exception) {}
    }
}