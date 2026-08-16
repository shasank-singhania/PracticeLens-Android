package app.practicelens.android.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val feedbackMode: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val prompt: String,
    val optionsJson: String,
    val firstSelectedOptionId: String?,
    val finalSelectedOptionId: String?,
    val answerChangeCount: Int,
    val timeToFirstAnswerMs: Long?,
    val timeToFinalLockMs: Long?,
    val correctOptionId: String?,
    val explanation: String?,
    val modelId: String?,
    val requestLatencyMs: Long?,
    val retryCount: Int,
    val questionableFeedback: Boolean,
    val driveSyncState: String,
    val remoteDriveFileId: String?,
    val contentChecksum: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface PracticeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestion(entity: QuestionEntity)

    @Query("SELECT * FROM questions ORDER BY updatedAtEpochMs DESC")
    suspend fun questions(): List<QuestionEntity>
}

@Database(entities = [SessionEntity::class, QuestionEntity::class], version = 1, exportSchema = true)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun practiceDao(): PracticeDao
}

interface PracticeRepository {
    suspend fun saveQuestion(entity: QuestionEntity)
    suspend fun history(): List<QuestionEntity>
}
