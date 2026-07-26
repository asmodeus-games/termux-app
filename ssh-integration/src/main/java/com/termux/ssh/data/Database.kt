package com.termux.ssh.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String, // "PASSWORD" or "KEY"
    val password: String = "",
    val sshKeyId: Long? = null, // Refers to SSHKeyEntity.id
    val autoReconnect: Boolean = true,
    val lastUsed: Long = 0,
    val proxyType: String = "NONE", // "NONE", "CLOUDFLARED", "HTTP", "SOCKS5", "CUSTOM"
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyCommand: String = "" // e.g. "cloudflared access ssh --hostname %h"
)

@Entity(tableName = "ssh_keys")
data class SSHKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val privateKey: String,
    val publicKey: String,
    val passphrase: String = ""
)

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val command: String,
    val useCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY lastUsed DESC")
    fun getAllServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerById(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Delete
    suspend fun deleteServer(server: ServerEntity)
}

@Dao
interface SSHKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name ASC")
    fun getAllKeys(): Flow<List<SSHKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getKeyById(id: Long): SSHKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKey(key: SSHKeyEntity): Long

    @Delete
    suspend fun deleteKey(key: SSHKeyEntity)
}

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history WHERE serverId = :serverId ORDER BY useCount DESC, lastUsed DESC")
    fun getHistoryForServer(serverId: Long): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history WHERE serverId = :serverId AND command = :command LIMIT 1")
    suspend fun getCommand(serverId: Long, command: String): CommandHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: CommandHistoryEntity)

    @Update
    suspend fun updateCommand(command: CommandHistoryEntity)

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun deleteHistoryItem(id: Long)
}

@Database(entities = [ServerEntity::class, SSHKeyEntity::class, CommandHistoryEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun sshKeyDao(): SSHKeyDao
    abstract fun commandHistoryDao(): CommandHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ssh_terminal_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}