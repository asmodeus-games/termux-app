package com.termux.ssh.data

import kotlinx.coroutines.flow.Flow

class ServerRepository(private val serverDao: ServerDao) {
    val allServers: Flow<List<ServerEntity>> = serverDao.getAllServers()

    suspend fun getServerById(id: Long): ServerEntity? = serverDao.getServerById(id)
    suspend fun insertServer(server: ServerEntity): Long = serverDao.insertServer(server)
    suspend fun updateServer(server: ServerEntity) = serverDao.updateServer(server)
    suspend fun deleteServer(server: ServerEntity) = serverDao.deleteServer(server)
}

class SSHKeyRepository(private val sshKeyDao: SSHKeyDao) {
    val allKeys: Flow<List<SSHKeyEntity>> = sshKeyDao.getAllKeys()

    suspend fun getKeyById(id: Long): SSHKeyEntity? = sshKeyDao.getKeyById(id)
    suspend fun insertKey(key: SSHKeyEntity): Long = sshKeyDao.insertKey(key)
    suspend fun deleteKey(key: SSHKeyEntity) = sshKeyDao.deleteKey(key)
}

class CommandHistoryRepository(private val commandHistoryDao: CommandHistoryDao) {
    fun getHistoryForServer(serverId: Long): Flow<List<CommandHistoryEntity>> =
        commandHistoryDao.getHistoryForServer(serverId)

    suspend fun addCommand(serverId: Long, commandStr: String) {
        val trimmed = commandStr.trim()
        if (trimmed.isEmpty()) return
        val existing = commandHistoryDao.getCommand(serverId, trimmed)
        if (existing != null) {
            commandHistoryDao.updateCommand(existing.copy(
                useCount = existing.useCount + 1,
                lastUsed = System.currentTimeMillis()
            ))
        } else {
            commandHistoryDao.insertCommand(CommandHistoryEntity(
                serverId = serverId,
                command = trimmed,
                useCount = 1,
                lastUsed = System.currentTimeMillis()
            ))
        }
    }

    suspend fun deleteHistoryItem(id: Long) = commandHistoryDao.deleteHistoryItem(id)
}