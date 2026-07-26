package com.termux.ssh

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.ssh.data.*
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val serverRepository = ServerRepository(database.serverDao())
    private val sshKeyRepository = SSHKeyRepository(database.sshKeyDao())
    private val historyRepository = CommandHistoryRepository(database.commandHistoryDao())

    val servers: StateFlow<List<ServerEntity>> = serverRepository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SSHKeyEntity>> = sshKeyRepository.allKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Tracks currently active terminal server ID (for active screen)
    var activeServerId by mutableLongStateOf(-1L)

    // SSH Service controller shortcuts
    val activeSessions = SSHService.activeSessions

    private val app: Application get() = getApplication()

    init {
        viewModelScope.launch {
            BackupManager.autoRestoreIfEmpty(app, database)
            autoDetectSshKeys {}
        }
    }

    fun exportAppBackup(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val json = BackupManager.exportAndSaveBackup(app, database)
            withContext(Dispatchers.Main) {
                onDone(json)
            }
        }
    }

    fun importAppBackup(jsonString: String, onDone: (Int, Int) -> Unit) {
        viewModelScope.launch {
            val res = BackupManager.importBackupJson(jsonString, database)
            BackupManager.exportAndSaveBackup(app, database)
            withContext(Dispatchers.Main) {
                onDone(res.first, res.second)
            }
        }
    }

    fun autoDetectSshKeys(onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val userHome = System.getProperty("user.home") ?: ""
            val candidatesDirs = listOfNotNull(
                if (userHome.isNotBlank()) java.io.File(userHome, ".ssh") else null,
                java.io.File(app.filesDir, ".ssh"),
                java.io.File("/sdcard/.ssh"),
                java.io.File("/storage/emulated/0/.ssh"),
                java.io.File("/data/data/com.termux/files/home/.ssh"),
                java.io.File("/data/data/${app.packageName}/files/.ssh")
            )

            val existingKeys = sshKeyRepository.allKeys.first()
            val existingPrivateKeys = existingKeys.map { it.privateKey.trim() }.toSet()
            val existingNames = existingKeys.map { it.name.lowercase() }.toSet()

            var newlyAddedCount = 0

            for (dir in candidatesDirs) {
                if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
                val files = dir.listFiles() ?: continue

                for (file in files) {
                    if (file.isDirectory || file.name.endsWith(".pub") || file.name == "known_hosts" || file.name == "authorized_keys" || file.name == "config") {
                        continue
                    }
                    try {
                        val content = file.readText().trim()
                        if (content.contains("-----BEGIN") || content.contains("PRIVATE KEY")) {
                            if (!existingPrivateKeys.contains(content)) {
                                val pubFile = java.io.File(file.parent, "${file.name}.pub")
                                val pubContent = if (pubFile.exists() && pubFile.canRead()) pubFile.readText().trim() else ""

                                val keyName = "${file.name} [auto-detected]"
                                if (!existingNames.contains(keyName.lowercase())) {
                                    sshKeyRepository.insertKey(
                                        SSHKeyEntity(
                                            name = keyName,
                                            privateKey = content,
                                            publicKey = pubContent,
                                            passphrase = ""
                                        )
                                    )
                                    newlyAddedCount++
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            withContext(Dispatchers.Main) {
                onComplete(newlyAddedCount)
            }
        }
    }

    // Get command history for a specific server
    fun getHistoryForServer(serverId: Long): Flow<List<CommandHistoryEntity>> {
        return historyRepository.getHistoryForServer(serverId)
    }

    // Connect to server
    fun connectToServer(serverId: Long) {
        viewModelScope.launch {
            // Update last used timestamp in DB
            val server = serverRepository.getServerById(serverId)
            if (server != null) {
                serverRepository.updateServer(server.copy(lastUsed = System.currentTimeMillis()))
            }
            // Ask SSHService to initiate
            SSHService.startService(app)
            // Brief delay to ensure service is initialized
            kotlinx.coroutines.delay(100)

            // Trigger connection through static companion
            val connIntent = Intent(app, SSHService::class.java).apply {
                action = "CONNECT"
                putExtra("SERVER_ID", serverId)
            }
            app.startService(connIntent)
        }
    }

    // Creates a new local Android shell session
    fun createLocalTerminalSession(): Long {
        var nextLocalId = -100L
        while (SSHService.activeSessions.containsKey(nextLocalId)) {
            nextLocalId--
        }
        SSHService.startService(app)
        val connIntent = Intent(app, SSHService::class.java).apply {
            action = "CONNECT"
            putExtra("SERVER_ID", nextLocalId)
        }
        app.startService(connIntent)
        return nextLocalId
    }

    fun disconnectServer(serverId: Long) {
        val intent = Intent(app, SSHService::class.java).apply {
            action = "DISCONNECT"
            putExtra("SERVER_ID", serverId)
        }
        app.startService(intent)
    }

    fun sendCommand(serverId: Long, command: String) {
        val intent = Intent(app, SSHService::class.java).apply {
            action = "SEND_COMMAND"
            putExtra("SERVER_ID", serverId)
            putExtra("COMMAND", command)
        }
        app.startService(intent)
    }

    fun sendRawBytes(serverId: Long, bytes: ByteArray) {
        val intent = Intent(app, SSHService::class.java).apply {
            action = "SEND_RAW"
            putExtra("SERVER_ID", serverId)
            putExtra("BYTES", bytes)
        }
        app.startService(intent)
    }

    // Server Database Actions
    fun saveServer(server: ServerEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            if (server.id == 0L) {
                serverRepository.insertServer(server)
            } else {
                serverRepository.updateServer(server)
            }
            BackupManager.exportAndSaveBackup(app, database)
            onDone()
        }
    }

    fun deleteServer(server: ServerEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            // Also disconnect if connected
            disconnectServer(server.id)
            serverRepository.deleteServer(server)
            BackupManager.exportAndSaveBackup(app, database)
            onDone()
        }
    }

    // Key Database Actions
    fun saveSshKey(name: String, privateKey: String, publicKey: String, passphraseStr: String, onDone: () -> Unit) {
        viewModelScope.launch {
            sshKeyRepository.insertKey(
                SSHKeyEntity(
                    name = name,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    passphrase = passphraseStr
                )
            )
            BackupManager.exportAndSaveBackup(app, database)
            onDone()
        }
    }

    fun deleteSshKey(key: SSHKeyEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            sshKeyRepository.deleteKey(key)
            BackupManager.exportAndSaveBackup(app, database)
            onDone()
        }
    }

    // Generates an RSA 2048 keypair natively in background using JSch KeyPair.genKeyPair
    fun generateRsaKeypair(name: String, passphraseStr: String, onDone: (SSHKeyEntity) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val jsch = JSch()
                // KeyPair.genKeyPair takes JSch and type (RSA=1, DSS=2, ECDSA=3, ED25519=4)
                val keypair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048)

                val privateStream = java.io.ByteArrayOutputStream()
                val publicStream = java.io.ByteArrayOutputStream()

                keypair.writePrivateKey(privateStream, passphraseStr.toByteArray())
                keypair.writePublicKey(publicStream, "Android-SSH-Terminal Key")

                val privateKeyText = String(privateStream.toByteArray(), Charsets.UTF_8)
                val publicKeyText = String(publicStream.toByteArray(), Charsets.UTF_8)

                val keyEntity = SSHKeyEntity(
                    name = name,
                    privateKey = privateKeyText,
                    publicKey = publicKeyText,
                    passphrase = passphraseStr
                )

                val keyId = sshKeyRepository.insertKey(keyEntity)
                BackupManager.exportAndSaveBackup(app, database)

                withContext(Dispatchers.Main) {
                    onDone(keyEntity.copy(id = keyId))
                }
            } catch (e: Exception) {
                // handle error
            }
        }
    }

    // Clipboard Helpers
    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    fun readFromClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        return item?.text?.toString() ?: ""
    }
}