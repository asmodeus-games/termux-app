package com.termux.ssh

import android.content.Context
import android.net.Uri
import com.termux.ssh.data.AppDatabase
import com.termux.ssh.data.ServerEntity
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Vector

data class SFTPItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val lastModified: Long
)

object SFTPManager {

    private suspend fun getOrCreateSession(server: ServerEntity, database: AppDatabase): Pair<Session, Boolean> = withContext(Dispatchers.IO) {
        val activeSession = SSHService.activeSessions[server.id]?.session
        if (activeSession != null && activeSession.isConnected) {
            return@withContext Pair(activeSession, false)
        }

        val jsch = JSch()
        if (server.authType == "KEY" && server.sshKeyId != null) {
            val keyEntity = database.sshKeyDao().getKeyById(server.sshKeyId)
            if (keyEntity != null) {
                val privateKeyBytes = keyEntity.privateKey.toByteArray(Charsets.UTF_8)
                val passphraseBytes = keyEntity.passphrase.toByteArray(Charsets.UTF_8)
                jsch.addIdentity(keyEntity.name, privateKeyBytes, null, passphraseBytes)
            }
        }

        val jschSession = jsch.getSession(server.username, server.host, server.port)
        if (server.authType == "PASSWORD") {
            jschSession.setPassword(server.password)
        }
        jschSession.setConfig("StrictHostKeyChecking", "no")
        jschSession.timeout = 15000
        jschSession.connect()
        return@withContext Pair(jschSession, true)
    }

    suspend fun listFiles(
        server: ServerEntity,
        database: AppDatabase,
        remotePath: String
    ): Result<List<SFTPItem>> = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            val currentDir = if (remotePath.isBlank()) sftpChannel.pwd() else remotePath
            sftpChannel.cd(currentDir)

            @Suppress("UNCHECKED_CAST")
            val vectorList = sftpChannel.ls(".") as Vector<ChannelSftp.LsEntry>

            val items = mutableListOf<SFTPItem>()
            for (entry in vectorList) {
                val filename = entry.filename
                if (filename == "." || filename == "..") continue

                val isDir = entry.attrs.isDir
                val fullPath = if (currentDir.endsWith("/")) "$currentDir$filename" else "$currentDir/$filename"
                
                items.add(
                    SFTPItem(
                        name = filename,
                        path = fullPath,
                        isDirectory = isDir,
                        size = entry.attrs.size,
                        permissions = entry.attrs.permissionsString ?: "",
                        lastModified = entry.attrs.mTime * 1000L
                    )
                )
            }

            // Sort directories first, then files alphabetically
            val sorted = items.sortedWith(
                compareByDescending<SFTPItem> { it.isDirectory }.thenBy { it.name.lowercase() }
            )

            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun uploadFile(
        context: Context,
        server: ServerEntity,
        database: AppDatabase,
        localUri: Uri,
        remoteDir: String,
        targetFileName: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val inputStream = context.contentResolver.openInputStream(localUri)
                ?: return@withContext Result.failure(Exception("Cannot open local file stream"))

            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            val targetPath = if (remoteDir.endsWith("/")) "$remoteDir$targetFileName" else "$remoteDir/$targetFileName"

            inputStream.use { stream ->
                sftpChannel.put(stream, targetPath, object : SftpProgressMonitor {
                    private var maxBytes: Long = 0
                    private var transferredBytes: Long = 0

                    override fun init(op: Int, src: String?, dest: String?, max: Long) {
                        this.maxBytes = max
                        this.transferredBytes = 0
                    }

                    override fun count(count: Long): Boolean {
                        transferredBytes += count
                        if (maxBytes > 0) {
                            val progress = (transferredBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                        return true
                    }

                    override fun end() {
                        onProgress(1f)
                    }
                })
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun downloadFile(
        context: Context,
        server: ServerEntity,
        database: AppDatabase,
        remoteFilePath: String,
        targetUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val outputStream = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext Result.failure(Exception("Cannot open local target stream"))

            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            val attrs = sftpChannel.stat(remoteFilePath)
            val totalSize = attrs.size

            outputStream.use { stream ->
                sftpChannel.get(remoteFilePath, stream, object : SftpProgressMonitor {
                    private var maxBytes: Long = totalSize
                    private var transferredBytes: Long = 0

                    override fun init(op: Int, src: String?, dest: String?, max: Long) {
                        if (max > 0) this.maxBytes = max
                        this.transferredBytes = 0
                    }

                    override fun count(count: Long): Boolean {
                        transferredBytes += count
                        if (maxBytes > 0) {
                            val progress = (transferredBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                        return true
                    }

                    override fun end() {
                        onProgress(1f)
                    }
                })
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun createDirectory(
        server: ServerEntity,
        database: AppDatabase,
        remotePath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            sftpChannel.mkdir(remotePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun deleteItem(
        server: ServerEntity,
        database: AppDatabase,
        remotePath: String,
        isDirectory: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            if (isDirectory) {
                sftpChannel.rmdir(remotePath)
            } else {
                sftpChannel.rm(remotePath)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }

    suspend fun getWorkingDirectory(
        server: ServerEntity,
        database: AppDatabase
    ): String = withContext(Dispatchers.IO) {
        var transientSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val (session, isTransient) = getOrCreateSession(server, database)
            if (isTransient) transientSession = session

            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)
            val pwd = sftpChannel.pwd()
            pwd ?: "/"
        } catch (e: Exception) {
            "/"
        } finally {
            try {
                sftpChannel?.disconnect()
                transientSession?.disconnect()
            } catch (_: Exception) {}
        }
    }
}