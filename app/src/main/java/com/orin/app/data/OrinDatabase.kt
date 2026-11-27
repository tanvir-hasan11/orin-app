package com.orin.app.data

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val attachmentsJson: String? = null,
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

@Entity(
    tableName = "memories",
    indices = [
        Index("key"),
        Index(value = ["category"], name = "idx_memories_category"),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val key: String,
    val value: String,
    val category: String = "general",
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

@Entity(
    tableName = "automations",
    indices = [Index(value = ["enabled", "next_run_at"], name = "idx_automations_next_run")],
)
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    @ColumnInfo(name = "schedule_type") val scheduleType: String,
    @ColumnInfo(name = "schedule_param") val scheduleParam: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "is_daemon") val isDaemon: Boolean = false,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long? = null,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long,
)

@Entity(
    tableName = "meta_ad_snapshots",
    indices = [Index(value = ["account_id", "fetched_at"], name = "idx_meta_snapshots_account")],
)
data class MetaAdSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "date_preset") val datePreset: String,
    @ColumnInfo(name = "spend_cents") val spendCents: Long = 0L,
    val currency: String = "USD",
    val impressions: Long = 0L,
    val clicks: Long = 0L,
    val ctr: Double = 0.0,
    val cpc: Double = 0.0,
    val reach: Long = 0L,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "projects",
    indices = [Index(value = ["last_opened_at"], name = "idx_projects_last_opened")],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "display_path") val displayPath: String,
    @ColumnInfo(name = "is_saf") val isSaf: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long = System.currentTimeMillis(),
)

@Dao
interface OrinDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    suspend fun getAllConversations(): List<ConversationEntity>

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

    @Query("SELECT * FROM messages ORDER BY createdAtMs ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM personal_tasks ORDER BY completed ASC, createdAtMs DESC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM personal_tasks ORDER BY completed ASC, createdAtMs DESC")
    suspend fun getAllTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("UPDATE personal_tasks SET completed = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: String, completed: Boolean)

    @Query("DELETE FROM personal_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT * FROM memories ORDER BY createdAtMs DESC LIMIT 200")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAtMs DESC LIMIT 200")
    suspend fun getMemories(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE lower(key) = lower(:key) ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun findMemory(key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE lower(key) LIKE '%' || lower(:query) || '%' OR lower(value) LIKE '%' || lower(:query) || '%' OR lower(category) = lower(:query) ORDER BY createdAtMs DESC LIMIT 20")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("SELECT * FROM audit_logs ORDER BY timestampMs DESC LIMIT 50")
    fun observeAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(entry: AuditLogEntity)

    @Query("SELECT * FROM automations ORDER BY enabled DESC, next_run_at ASC")
    fun observeAutomations(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations ORDER BY enabled DESC, next_run_at ASC")
    suspend fun getAutomations(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id LIMIT 1")
    suspend fun getAutomation(id: String): AutomationEntity?

    @Query("SELECT * FROM automations WHERE enabled = 1 AND next_run_at <= :nowMs ORDER BY next_run_at ASC")
    suspend fun getDueAutomations(nowMs: Long): List<AutomationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutomation(automation: AutomationEntity)

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setAutomationEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteAutomation(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetaAdSnapshot(snapshot: MetaAdSnapshotEntity)

    @Query("SELECT * FROM meta_ad_snapshots WHERE date_preset = :datePreset ORDER BY fetched_at DESC LIMIT 1")
    suspend fun latestMetaAdSnapshot(datePreset: String): MetaAdSnapshotEntity?

    @Query("SELECT * FROM meta_ad_snapshots ORDER BY fetched_at DESC LIMIT 20")
    fun observeMetaAdSnapshots(): Flow<List<MetaAdSnapshotEntity>>

    @Query("SELECT * FROM projects ORDER BY last_opened_at DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY last_opened_at DESC")
    suspend fun getProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProject(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        TaskEntity::class,
        MemoryEntity::class,
        AuditLogEntity::class,
        AutomationEntity::class,
        MetaAdSnapshotEntity::class,
        ProjectEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class OrinDatabase : RoomDatabase() {
    abstract fun dao(): OrinDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memories (
                        id TEXT NOT NULL PRIMARY KEY,
                        `key` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_key ON memories (`key`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS automations (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        schedule_type TEXT NOT NULL,
                        schedule_param TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        is_daemon INTEGER NOT NULL DEFAULT 0,
                        last_run_at INTEGER,
                        next_run_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_automations_next_run ON automations (enabled, next_run_at)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meta_ad_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        account_id TEXT NOT NULL,
                        date_preset TEXT NOT NULL,
                        spend_cents INTEGER NOT NULL DEFAULT 0,
                        currency TEXT NOT NULL DEFAULT 'USD',
                        impressions INTEGER NOT NULL DEFAULT 0,
                        clicks INTEGER NOT NULL DEFAULT 0,
                        ctr REAL NOT NULL DEFAULT 0.0,
                        cpc REAL NOT NULL DEFAULT 0.0,
                        reach INTEGER NOT NULL DEFAULT 0,
                        fetched_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_meta_snapshots_account ON meta_ad_snapshots (account_id, fetched_at)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        display_path TEXT NOT NULL,
                        is_saf INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        last_opened_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_projects_last_opened ON projects (last_opened_at)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_category ON memories (category)")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
            }
        }

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
