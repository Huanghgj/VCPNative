package com.vcpnative.app.data.room

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

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String = "",
    val promptMode: String = "original",
    val originalSystemPrompt: String = "",
    val advancedSystemPromptJson: String = "",
    val presetSystemPrompt: String = "",
    val presetPromptPath: String = "",
    val selectedPreset: String = "",
    val model: String = "gemini-pro",
    val temperature: Double = 0.7,
    val contextTokenLimit: Int? = null,
    val maxOutputTokens: Int? = null,
    @ColumnInfo(name = "topP") val topP: Double? = null,
    @ColumnInfo(name = "topK") val topK: Int? = null,
    val streamOutput: Boolean = true,
    val avatarPath: String? = null,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "extra_json") val extraJson: String? = null,
)

@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("agentId")],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val sourceTopicId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(name = "extra_json") val extraJson: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("topicId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val topicId: String,
    val role: String,
    val content: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MessageAttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val attachmentOrder: Int,
    val name: String,
    val mimeType: String,
    val size: Long,
    val src: String,
    val internalFileName: String,
    val internalPath: String,
    val hash: String,
    val createdAt: Long,
    val extractedText: String? = null,
    val imageFramesJson: String? = null,
)

@Entity(
    tableName = "regex_rules",
    primaryKeys = ["agentId", "ruleOrder"],
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("agentId")],
)
data class RegexRuleEntity(
    val agentId: String,
    val ruleOrder: Int,
    val findPattern: String,
    val replaceWith: String,
    val applyToContext: Boolean = true,
    val applyToFrontend: Boolean = true,
    val applyToRolesJson: String = "[]",
    val minDepth: Int = 0,
    val maxDepth: Int = -1,
    val extraJson: String? = null,
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :agentId LIMIT 1")
    suspend fun findById(agentId: String): AgentEntity?

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int

    @Query("DELETE FROM agents")
    suspend fun deleteAll()

    @Query("DELETE FROM agents WHERE id = :agentId")
    suspend fun deleteById(agentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)
}

@Dao
interface TopicDao {
    @Query("SELECT * FROM topics WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun observeByAgent(agentId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE agentId = :agentId ORDER BY updatedAt DESC")
    suspend fun loadByAgent(agentId: String): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE id = :topicId LIMIT 1")
    suspend fun findById(topicId: String): TopicEntity?

    @Query("SELECT COUNT(*) FROM topics WHERE agentId = :agentId")
    suspend fun countByAgent(agentId: String): Int

    @Query("SELECT COUNT(*) FROM topics")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(topic: TopicEntity)

    @Query("UPDATE topics SET updatedAt = :updatedAt WHERE id = :topicId")
    suspend fun touch(topicId: String, updatedAt: Long)

    @Query("UPDATE topics SET title = :title, updatedAt = :updatedAt WHERE id = :topicId")
    suspend fun updateTitle(topicId: String, title: String, updatedAt: Long)

    @Query("DELETE FROM topics WHERE id = :topicId")
    suspend fun delete(topicId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE topicId = :topicId ORDER BY createdAt ASC")
    fun observeByTopic(topicId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE topicId = :topicId ORDER BY createdAt ASC")
    suspend fun loadByTopic(topicId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE topicId = :topicId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun loadRecent(topicId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun findById(messageId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query(
        """
        UPDATE messages
        SET content = :content, status = :status, updatedAt = :updatedAt
        WHERE id = :messageId
        """,
    )
    suspend fun updateContent(
        messageId: String,
        content: String,
        status: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE topicId = :topicId AND createdAt >= :createdAt")
    suspend fun deleteFrom(topicId: String, createdAt: Long)
}

@Dao
interface MessageAttachmentDao {
    @Query(
        """
        SELECT message_attachments.*
        FROM message_attachments
        INNER JOIN messages ON messages.id = message_attachments.messageId
        WHERE messages.topicId = :topicId
        ORDER BY messages.createdAt ASC, message_attachments.attachmentOrder ASC
        """,
    )
    fun observeByTopic(topicId: String): Flow<List<MessageAttachmentEntity>>

    @Query(
        """
        SELECT message_attachments.*
        FROM message_attachments
        INNER JOIN messages ON messages.id = message_attachments.messageId
        WHERE messages.topicId = :topicId
        ORDER BY messages.createdAt ASC, message_attachments.attachmentOrder ASC
        """,
    )
    suspend fun loadByTopic(topicId: String): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun findById(attachmentId: String): MessageAttachmentEntity?

    @Query("SELECT COUNT(*) FROM message_attachments")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: MessageAttachmentEntity)
}

@Dao
interface RegexRuleDao {
    @Query("SELECT * FROM regex_rules WHERE agentId = :agentId ORDER BY ruleOrder ASC")
    suspend fun loadByAgent(agentId: String): List<RegexRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RegexRuleEntity)
}

@Database(
    entities = [
        AgentEntity::class,
        TopicEntity::class,
        MessageEntity::class,
        MessageAttachmentEntity::class,
        RegexRuleEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun topicDao(): TopicDao
    abstract fun messageDao(): MessageDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun regexRuleDao(): RegexRuleDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agents ADD COLUMN model TEXT NOT NULL DEFAULT 'gemini-pro'")
                db.execSQL("ALTER TABLE agents ADD COLUMN temperature REAL NOT NULL DEFAULT 0.7")
                db.execSQL("ALTER TABLE agents ADD COLUMN contextTokenLimit INTEGER")
                db.execSQL("ALTER TABLE agents ADD COLUMN maxOutputTokens INTEGER")
                db.execSQL("ALTER TABLE agents ADD COLUMN topP REAL")
                db.execSQL("ALTER TABLE agents ADD COLUMN topK INTEGER")
                db.execSQL("ALTER TABLE agents ADD COLUMN streamOutput INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topics ADD COLUMN sourceTopicId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE topics SET sourceTopicId = id WHERE sourceTopicId = ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `regex_rules` (
                        `agentId` TEXT NOT NULL,
                        `ruleOrder` INTEGER NOT NULL,
                        `findPattern` TEXT NOT NULL,
                        `replaceWith` TEXT NOT NULL,
                        `applyToContext` INTEGER NOT NULL,
                        `applyToFrontend` INTEGER NOT NULL,
                        `applyToRolesJson` TEXT NOT NULL,
                        `minDepth` INTEGER NOT NULL,
                        `maxDepth` INTEGER NOT NULL,
                        `extraJson` TEXT,
                        PRIMARY KEY(`agentId`, `ruleOrder`),
                        FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_regex_rules_agentId` ON `regex_rules` (`agentId`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_attachments` (
                        `id` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `attachmentOrder` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `src` TEXT NOT NULL,
                        `internalFileName` TEXT NOT NULL,
                        `internalPath` TEXT NOT NULL,
                        `hash` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `extractedText` TEXT,
                        `imageFramesJson` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId`
                    ON `message_attachments` (`messageId`)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN promptMode TEXT NOT NULL DEFAULT 'original'")
                db.execSQL("ALTER TABLE agents ADD COLUMN originalSystemPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agents ADD COLUMN advancedSystemPromptJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agents ADD COLUMN presetSystemPrompt TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN presetPromptPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agents ADD COLUMN selectedPreset TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN extra_json TEXT")
                db.execSQL("ALTER TABLE topics ADD COLUMN extra_json TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "vcpnative.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
    }
}
