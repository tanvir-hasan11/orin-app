package com.orin.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val isUser: Boolean,
    val text: String,
    val toolSummary: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "personal_tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val priority: String = "normal",
    val due: String? = null,
    val completed: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val tool: String,
    val action: String,
    val detail: String,
    val ok: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
)

@Dao
interface OrinDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("UPDATE conversations SET updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAtMs: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM personal_tasks ORDER BY completed ASC, createdAtMs DESC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("UPDATE personal_tasks SET completed = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: String, completed: Boolean)

    @Query("DELETE FROM personal_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT * FROM audit_logs ORDER BY timestampMs DESC LIMIT 50")
    fun observeAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(entry: AuditLogEntity)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, TaskEntity::class, AuditLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OrinDatabase : RoomDatabase() {
    abstract fun dao(): OrinDao

    companion object {
        @Volatile
        private var instance: OrinDatabase? = null

        fun get(context: Context): OrinDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OrinDatabase::class.java,
                    "orin_db",
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { instance = it }
            }
    }
}
