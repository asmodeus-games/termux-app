package com.termux.ssh

import android.content.Context
import com.termux.ssh.data.AppDatabase
import com.termux.ssh.data.SSHKeyEntity
import com.termux.ssh.data.ServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BackupManager {

    private val candidateBackupPaths = listOf(
        "/sdcard/Download/SSH_App_Config_Backup.json",
        "/sdcard/.ssh/ssh_app_config_backup.json",
        "/storage/emulated/0/Download/SSH_App_Config_Backup.json",
        "/data/data/com.termux/files/home/.ssh/ssh_app_config_backup.json"
    )

    suspend fun exportAndSaveBackup(context: Context, database: AppDatabase): String = withContext(Dispatchers.IO) {
        val servers = database.serverDao().getAllServers().first()
        val keys = database.sshKeyDao().getAllKeys().first()

        val keysMap = keys.associateBy { it.id }

        val backupObj = JSONObject()
        backupObj.put("version", 1)
        backupObj.put("timestamp", System.currentTimeMillis())

        val keysArr = JSONArray()
        for (key in keys) {
            val kObj = JSONObject()
            kObj.put("name", key.name)
            kObj.put("privateKey", key.privateKey)
            kObj.put("publicKey", key.publicKey)
            kObj.put("passphrase", key.passphrase)
            keysArr.put(kObj)
        }
        backupObj.put("keys", keysArr)

        val serversArr = JSONArray()
        for (server in servers) {
            val sObj = JSONObject()
            sObj.put("name", server.name)
            sObj.put("host", server.host)
            sObj.put("port", server.port)
            sObj.put("username", server.username)
            sObj.put("authType", server.authType)
            sObj.put("password", server.password)
            sObj.put("sshKeyName", keysMap[server.sshKeyId]?.name ?: "")
            sObj.put("proxyType", server.proxyType)
            sObj.put("proxyHost", server.proxyHost)
            sObj.put("proxyPort", server.proxyPort)
            sObj.put("proxyCommand", server.proxyCommand)
            serversArr.put(sObj)
        }
        backupObj.put("servers", serversArr)

        val jsonString = backupObj.toString(2)

        // Save internal backup
        try {
            val internalFile = File(context.filesDir, "ssh_app_config_backup.json")
            internalFile.writeText(jsonString)
        } catch (_: Exception) {}

        // Save to external shared candidate locations
        for (path in candidateBackupPaths) {
            try {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.writeText(jsonString)
            } catch (_: Exception) {}
        }

        jsonString
    }

    suspend fun importBackupJson(jsonString: String, database: AppDatabase): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var importedServers = 0
        var importedKeys = 0

        try {
            val root = JSONObject(jsonString)

            // 1. Import Keys
            val existingKeys = database.sshKeyDao().getAllKeys().first()
            val existingKeyMap = existingKeys.associateBy { it.name.trim().lowercase() }.toMutableMap()

            if (root.has("keys")) {
                val keysArr = root.getJSONArray("keys")
                for (i in 0 until keysArr.length()) {
                    val kObj = keysArr.getJSONObject(i)
                    val name = kObj.optString("name", "Key_$i").trim()
                    val privateKey = kObj.optString("privateKey", "")
                    val publicKey = kObj.optString("publicKey", "")
                    val passphrase = kObj.optString("passphrase", "")

                    if (privateKey.isNotBlank()) {
                        val keyNameLower = name.lowercase()
                        if (!existingKeyMap.containsKey(keyNameLower)) {
                            val newKey = SSHKeyEntity(
                                name = name,
                                privateKey = privateKey,
                                publicKey = publicKey,
                                passphrase = passphrase
                            )
                            val newId = database.sshKeyDao().insertKey(newKey)
                            existingKeyMap[keyNameLower] = newKey.copy(id = newId)
                            importedKeys++
                        }
                    }
                }
            }

            // 2. Import Servers
            val existingServers = database.serverDao().getAllServers().first()
            val existingServerNames = existingServers.map { "${it.name}_${it.host}_${it.username}".lowercase() }.toSet()

            if (root.has("servers")) {
                val serversArr = root.getJSONArray("servers")
                for (i in 0 until serversArr.length()) {
                    val sObj = serversArr.getJSONObject(i)
                    val name = sObj.optString("name", "Server $i")
                    val host = sObj.optString("host", "")
                    val port = sObj.optInt("port", 22)
                    val username = sObj.optString("username", "")
                    val authType = sObj.optString("authType", "PASSWORD")
                    val password = sObj.optString("password", "")
                    val sshKeyName = sObj.optString("sshKeyName", "").trim().lowercase()
                    val proxyType = sObj.optString("proxyType", "NONE")
                    val proxyHost = sObj.optString("proxyHost", "")
                    val proxyPort = sObj.optInt("proxyPort", 1080)
                    val proxyCommand = sObj.optString("proxyCommand", "")

                    val serverUniqKey = "${name}_${host}_${username}".lowercase()

                    if (host.isNotBlank() && !existingServerNames.contains(serverUniqKey)) {
                        val matchedKeyId = existingKeyMap[sshKeyName]?.id

                        val newServer = ServerEntity(
                            name = name,
                            host = host,
                            port = port,
                            username = username,
                            authType = authType,
                            password = password,
                            sshKeyId = matchedKeyId,
                            proxyType = proxyType,
                            proxyHost = proxyHost,
                            proxyPort = proxyPort,
                            proxyCommand = proxyCommand
                        )
                        database.serverDao().insertServer(newServer)
                        importedServers++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Pair(importedServers, importedKeys)
    }

    suspend fun autoRestoreIfEmpty(context: Context, database: AppDatabase) {
        withContext(Dispatchers.IO) {
            try {
                val hasServers = database.serverDao().getAllServers().first().isNotEmpty()
                val hasKeys = database.sshKeyDao().getAllKeys().first().isNotEmpty()

                if (!hasServers && !hasKeys) {
                    val allCandidateFiles = mutableListOf(File(context.filesDir, "ssh_app_config_backup.json"))
                    allCandidateFiles.addAll(candidateBackupPaths.map { File(it) })

                    for (file in allCandidateFiles) {
                        if (file.exists() && file.canRead()) {
                            val content = file.readText()
                            if (content.contains("servers") || content.contains("keys")) {
                                importBackupJson(content, database)
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }
}