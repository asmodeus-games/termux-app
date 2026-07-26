package com.termux.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import com.termux.ssh.data.AppDatabase
import com.termux.ssh.data.ServerEntity
import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream

class SSHService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "ssh_terminal_channel"
        const val NOTIFICATION_ID = 4125
        const val LOCAL_TERMINAL_ID = -100L
        
        // Globally accessible list of active sessions keyed by Server ID
        val activeSessions = mutableStateMapOf<Long, TerminalSession>()
        
        fun startService(context: Context) {
            val intent = Intent(context, SSHService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SSHService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        createNotificationChannel()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        
        intent?.let {
            val serverId = it.getLongExtra("SERVER_ID", -1L)
            if (serverId != -1L) {
                when (it.action) {
                    "CONNECT" -> connectToServer(serverId)
                    "DISCONNECT" -> disconnectServer(serverId)
                    "SEND_COMMAND" -> {
                        val command = it.getStringExtra("COMMAND") ?: ""
                        sendCommand(serverId, command)
                    }
                    "SEND_RAW" -> {
                        val bytes = it.getByteArrayExtra("BYTES")
                        if (bytes != null) {
                            sendRawBytes(serverId, bytes)
                        }
                    }
                }
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SSH Session Keep-Alive"
            val descriptionText = "Keeps active SSH terminal connections alive in background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val activeCount = activeSessions.values.count { it.isConnected }
        val notificationText = if (activeCount == 0) {
            "Ready to connect to remote servers"
        } else {
            "Keeping $activeCount SSH session(s) active"
        }

        val notificationIntent = Intent()
        notificationIntent.action = Intent.ACTION_MAIN
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        notificationIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android SSH Terminal")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeCount = activeSessions.values.count { it.isConnected }
        val notificationText = if (activeCount == 0) {
            "Ready to connect to remote servers"
        } else {
            "Keeping $activeCount SSH session(s) active"
        }

        val notificationIntent = Intent()
        notificationIntent.action = Intent.ACTION_MAIN
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        notificationIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android SSH Terminal")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // Connects to a server or local shell
    fun connectToServer(serverId: Long) {
        val existing = activeSessions[serverId]
        if (existing != null && (existing.isConnected || existing.isReconnecting)) {
            return
        }

        if (serverId <= LOCAL_TERMINAL_ID) {
            connectToLocalTerminal(serverId)
            return
        }

        serviceScope.launch {
            val server = database.serverDao().getServerById(serverId) ?: return@launch
            
            withContext(Dispatchers.Main) {
                val session = activeSessions[serverId] ?: TerminalSession(serverId, server.name) {
                    updateNotification()
                }
                session.clearConsole()
                session.addSystemLine("Connecting to ${server.username}@${server.host}:${server.port}...")
                session.isReconnecting = false
                activeSessions[serverId] = session
            }

            executeConnection(server)
        }
    }

    fun connectToLocalTerminal(serverId: Long, name: String = "Local Shell") {
        serviceScope.launch {
            val terminalSession = withContext(Dispatchers.Main) {
                val session = activeSessions[serverId] ?: TerminalSession(serverId, name) {
                    updateNotification()
                }
                session.clearConsole()
                session.isLocal = true
                session.addSystemLine("╔════════════════════════════════════════════════════════════╗")
                session.addSystemLine("║  📱 Local Android Shell (/system/bin/sh)                   ║")
                session.addSystemLine("╠════════════════════════════════════════════════════════════╣")
                session.addSystemLine("║  - Standard shell utilities active: ls, cd, ping, ip, top  ║")
                session.addSystemLine("║  - Android SELinux restricts non-root app exec permissions ║")
                session.addSystemLine("║                                                            ║")
                session.addSystemLine("║  💡 To use APT Package Manager & Linux (Ubuntu/Debian):    ║")
                session.addSystemLine("║  1. Install Termux & run: pkg install openssh && sshd      ║")
                session.addSystemLine("║  2. Add Server in app: Host '127.0.0.1', Port '8022'       ║")
                session.addSystemLine("║  3. Connect to use full APT (python, git, node, gcc)       ║")
                session.addSystemLine("╚═════════════════════════════════════════════════════════════╝")
                activeSessions[serverId] = session
                session
            }

            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("/system/bin/sh", "-i")
                        .redirectErrorStream(true)
                        .start()

                    val inputStream = process.inputStream
                    val outputStream = process.outputStream

                    withContext(Dispatchers.Main) {
                        terminalSession.localProcess = process
                        terminalSession.outputStream = outputStream
                        terminalSession.isConnected = true
                        terminalSession.addSystemLine("Local Terminal Ready.")
                        updateNotification()
                    }

                    val buffer = ByteArray(4096)
                    var read = 0
                    while (process.isAlive && inputStream.read(buffer).also { read = it } != -1) {
                        val chunk = String(buffer, 0, read, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            terminalSession.appendOutput(chunk)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        terminalSession.addSystemLine("Local Terminal Error: ${e.message}")
                        terminalSession.isConnected = false
                        updateNotification()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        terminalSession.isConnected = false
                        updateNotification()
                    }
                }
            }
        }
    }

    private suspend fun executeConnection(server: ServerEntity) {
        val terminalSession = withContext(Dispatchers.Main) {
            activeSessions[server.id] ?: return@withContext null
        } ?: return

        withContext(Dispatchers.IO) {
            try {
                val jsch = JSch()
                
                // Add key identity if requested
                if (server.authType == "KEY" && server.sshKeyId != null) {
                    val keyEntity = database.sshKeyDao().getKeyById(server.sshKeyId)
                    if (keyEntity != null) {
                        val privateKeyBytes = keyEntity.privateKey.toByteArray(Charsets.UTF_8)
                        val passphraseBytes = keyEntity.passphrase.toByteArray(Charsets.UTF_8)
                        jsch.addIdentity(keyEntity.name, privateKeyBytes, null, passphraseBytes)
                        withContext(Dispatchers.Main) {
                            terminalSession.addSystemLine("Using SSH Key: ${keyEntity.name}")
                        }
                    } else {
                        throw Exception("SSH Key not found in database!")
                    }
                }

                var jschSession = jsch.getSession(server.username, server.host, server.port)
                
                if (server.authType == "PASSWORD") {
                    jschSession.setPassword(server.password)
                }

                // Proxy & ProxyCommand Configuration
                when (server.proxyType) {
                    "CLOUDFLARED", "CUSTOM" -> {
                        val cmd = server.proxyCommand.trim()
                        val isCloudflare = server.proxyType == "CLOUDFLARED" || cmd.contains("cloudflared")
                        if (isCloudflare) {
                            var extractedHost = server.host
                            if (cmd.isNotBlank()) {
                                val match = Regex("--hostname\\s+(\\S+)").find(cmd)
                                if (match != null) {
                                    val matchedGroup = match.groupValues[1]
                                    if (matchedGroup != "%h") {
                                        extractedHost = matchedGroup
                                    }
                                }
                            }
                            val proxy = CloudflareWebSocketProxy(
                                targetHost = extractedHost,
                                logCallback = { line ->
                                    serviceScope.launch(Dispatchers.Main) {
                                        terminalSession.addSystemLine(line)
                                    }
                                }
                            )
                            jschSession.setProxy(proxy)
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("Using Native Cloudflare WebSocket Tunnel to $extractedHost")
                            }
                        } else if (cmd.isNotBlank()) {
                            val proxy = ProcessProxyCommand(
                                commandTemplate = cmd,
                                context = this@SSHService,
                                logCallback = { line ->
                                    serviceScope.launch(Dispatchers.Main) {
                                        terminalSession.addSystemLine(line)
                                    }
                                }
                            )
                            jschSession.setProxy(proxy)
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("Using ProxyCommand: $cmd")
                            }
                        }
                    }
                    "HTTP" -> {
                        if (server.proxyHost.isNotBlank()) {
                            val proxy = com.jcraft.jsch.ProxyHTTP(server.proxyHost, server.proxyPort)
                            jschSession.setProxy(proxy)
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("Using HTTP Proxy: ${server.proxyHost}:${server.proxyPort}")
                            }
                        }
                    }
                    "SOCKS5" -> {
                        if (server.proxyHost.isNotBlank()) {
                            val proxy = com.jcraft.jsch.ProxySOCKS5(server.proxyHost, server.proxyPort)
                            jschSession.setProxy(proxy)
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("Using SOCKS5 Proxy: ${server.proxyHost}:${server.proxyPort}")
                            }
                        }
                    }
                }

                // Skip host key checking for quick connection
                jschSession.setConfig("StrictHostKeyChecking", "no")
                jschSession.timeout = 15000 // 15s timeout
                
                var connectionEstablished = false
                try {
                    jschSession.connect()
                    connectionEstablished = true
                } catch (proxyErr: Exception) {
                    if (server.proxyType != "NONE" && server.proxyType.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            terminalSession.addSystemLine("⚠️ Proxy/Tunnel failed: ${proxyErr.message}")
                            terminalSession.addSystemLine("⚡ Note: Android SELinux blocks executing 'cloudflared' binaries directly (Permission denied).")
                            terminalSession.addSystemLine("🔄 Attempting direct SSH connection to ${server.host}:${server.port}...")
                        }
                        
                        // Try fallback direct connection without proxy
                        val fallbackJschSession = jsch.getSession(server.username, server.host, server.port)
                        if (server.authType == "PASSWORD") {
                            fallbackJschSession.setPassword(server.password)
                        }
                        fallbackJschSession.setConfig("StrictHostKeyChecking", "no")
                        fallbackJschSession.timeout = 15000
                        
                        try {
                            fallbackJschSession.connect()
                            jschSession = fallbackJschSession
                            connectionEstablished = true
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("✅ Direct SSH connection established successfully!")
                            }
                        } catch (directErr: Exception) {
                            withContext(Dispatchers.Main) {
                                terminalSession.addSystemLine("❌ Direct SSH connection failed: ${directErr.message}")
                                terminalSession.addSystemLine("💡 Tip: If your server is reachable directly, edit server settings and set Proxy to 'None'.")
                            }
                            throw directErr
                        }
                    } else {
                        throw proxyErr
                    }
                }

                if (!connectionEstablished) {
                    throw Exception("Failed to establish SSH session.")
                }

                val channel = jschSession.openChannel("shell") as com.jcraft.jsch.ChannelShell
                channel.setPtyType("xterm")
                
                val inputStream = channel.inputStream
                val outputStream = channel.outputStream

                channel.connect(10000)

                withContext(Dispatchers.Main) {
                    terminalSession.session = jschSession
                    terminalSession.channel = channel
                    terminalSession.outputStream = outputStream
                    terminalSession.isConnected = true
                    terminalSession.reconnectAttempts = 0
                    terminalSession.isReconnecting = false
                    terminalSession.addSystemLine("Connected successfully!")
                    updateNotification()
                }

                // Start reader loop
                val buffer = ByteArray(4096)
                var read = 0
                while (channel.isConnected && inputStream.read(buffer).also { read = it } != -1) {
                    terminalSession.recordRx(read)
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        terminalSession.appendOutput(chunk)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    terminalSession.addSystemLine("Connection error: ${e.message}")
                    terminalSession.isConnected = false
                    updateNotification()
                }
                
                // Handle auto reconnect
                if (server.autoReconnect) {
                    triggerAutoReconnect(server)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    terminalSession.isConnected = false
                    updateNotification()
                }
            }
        }
    }

    private fun triggerAutoReconnect(server: ServerEntity) {
        val session = activeSessions[server.id] ?: return
        if (session.isReconnecting) return

        session.isReconnecting = true
        session.reconnectAttempts++
        
        val delaySec = (session.reconnectAttempts * 5).coerceAtMost(60)
        session.addSystemLine("Reconnecting in $delaySec seconds (Attempt ${session.reconnectAttempts})...")

        serviceScope.launch {
            delay(delaySec * 1000L)
            
            val stillNeedsReconnection = withContext(Dispatchers.Main) {
                activeSessions[server.id]?.isReconnecting == true && !activeSessions[server.id]!!.isConnected
            }

            if (stillNeedsReconnection) {
                withContext(Dispatchers.Main) {
                    session.addSystemLine("Retrying connection...")
                }
                executeConnection(server)
            }
        }
    }

    fun disconnectServer(serverId: Long) {
        val session = activeSessions[serverId] ?: return
        
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                session.addSystemLine("Disconnecting...")
                session.isReconnecting = false
                session.isConnected = false
            }

            try {
                session.localProcess?.destroy()
                session.channel?.disconnect()
                session.session?.disconnect()
            } catch (e: Exception) {
                // ignore
            }

            withContext(Dispatchers.Main) {
                session.localProcess = null
                session.channel = null
                session.session = null
                session.outputStream = null
                session.addSystemLine("Disconnected.")
                updateNotification()
            }
        }
    }

    fun sendCommand(serverId: Long, command: String) {
        val session = activeSessions[serverId] ?: return
        if (!session.isConnected) return

        serviceScope.launch {
            // Save to database command history
            database.commandHistoryDao().insertCommand(
                com.termux.ssh.data.CommandHistoryEntity(
                    serverId = serverId,
                    command = command.trim(),
                    useCount = 1,
                    lastUsed = System.currentTimeMillis()
                )
            )

            try {
                session.outputStream?.let { os ->
                    withContext(Dispatchers.Main) {
                        session.addCommandLine(command)
                    }
                    val fullCmd = command + "\n"
                    val bytes = fullCmd.toByteArray(Charsets.UTF_8)
                    session.recordTx(bytes.size)
                    os.write(bytes)
                    os.flush()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    session.addSystemLine("Failed to send command: ${e.message}")
                }
            }
        }
    }

    fun sendRawBytes(serverId: Long, bytes: ByteArray) {
        val session = activeSessions[serverId] ?: return
        if (!session.isConnected) return

        serviceScope.launch {
            try {
                session.outputStream?.let { os ->
                    session.recordTx(bytes.size)
                    os.write(bytes)
                    os.flush()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect all sessions when service is destroyed
        serviceScope.launch {
            for ((_, session) in activeSessions) {
                try {
                    session.channel?.disconnect()
                    session.session?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
            activeSessions.clear()
        }
        serviceScope.cancel()
    }
}