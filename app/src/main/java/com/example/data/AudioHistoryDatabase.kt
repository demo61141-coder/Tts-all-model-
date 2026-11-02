package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Room Entity representing a generated Bengali TTS audio creation.
 * Stores the creation timestamp, source text snippet, full source text,
 * the voice model used, playback speed, duration, and local WAV file path.
 */
@Entity(tableName = "audio_history")
data class AudioHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val textSnippet: String,
    val fullText: String,
    val voiceModel: String,
    val speed: Float = 1.0f,
    val durationSeconds: Float = 0f,
    val audioFilePath: String
)

@Dao
interface AudioHistoryDao {
    @Query("SELECT * FROM audio_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<AudioHistoryEntity>>

    @Query("SELECT * FROM audio_history WHERE id = :id LIMIT 1")
    suspend fun getHistoryById(id: Long): AudioHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: AudioHistoryEntity): Long

    @Query("DELETE FROM audio_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Long)

    @Query("DELETE FROM audio_history")
    suspend fun clearAllHistory()
}

@Database(entities = [AudioHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioHistoryDao(): AudioHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bangla_tts_history.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Repository abstracting Room database access and managing the local WAV files in filesDir/audio_history/.
 */
class AudioHistoryRepository(private val dao: AudioHistoryDao) {

    val allHistory: Flow<List<AudioHistoryEntity>> = dao.getAllHistory()

    suspend fun insertCreation(item: AudioHistoryEntity): Long = withContext(Dispatchers.IO) {
        dao.insertHistory(item)
    }

    suspend fun getById(id: Long): AudioHistoryEntity? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)
    }

    suspend fun deleteCreation(item: AudioHistoryEntity) = withContext(Dispatchers.IO) {
        try {
            val file = File(item.audioFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
        dao.deleteHistoryById(item.id)
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        try {
            val historyDir = File(context.filesDir, "audio_history")
            if (historyDir.exists()) {
                historyDir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Throwable) {
        }
        dao.clearAllHistory()
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioHistoryRepository? = null

        fun getInstance(context: Context): AudioHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val repo = AudioHistoryRepository(db.audioHistoryDao())
                INSTANCE = repo
                repo
            }
        }
    }
}
