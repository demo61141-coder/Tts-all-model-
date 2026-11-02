package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AudioHistoryEntity
import com.example.data.AudioHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AudioHistoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AudioHistoryRepository(database.audioHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("বাংলা ভয়েস স্টুডিও", appName)
    }

    @Test
    fun `verify voice model specs exact byte sizes and folders`() {
        assertEquals(114160150L, VoiceModelSpec.ELEVENLABS.expectedModelBytes)
        assertEquals(567L, VoiceModelSpec.ELEVENLABS.expectedTokensBytes)
        assertEquals("model_eleven", VoiceModelSpec.ELEVENLABS.folderName)

        assertEquals(114333643L, VoiceModelSpec.KIKI_NEWS.expectedModelBytes)
        assertEquals(567L, VoiceModelSpec.KIKI_NEWS.expectedTokensBytes)
        assertEquals("model_kiki", VoiceModelSpec.KIKI_NEWS.folderName)
    }

    @Test
    fun `insert and retrieve generated audio history from Room database`() = runBlocking {
        val item1 = AudioHistoryEntity(
            timestamp = 1700000001000L,
            textSnippet = "স্বাগতম বাংলা ভয়েস স্টুডিওতে।",
            fullText = "স্বাগতম বাংলা ভয়েস স্টুডিওতে। আজকের আবহাওয়া চমৎকার!",
            voiceModel = "ElevenLabs",
            speed = 1.0f,
            durationSeconds = 3.5f,
            audioFilePath = "/data/user/0/com.banglatts.voice.aistudio/files/audio_history/BanglaTTS_1.wav"
        )
        val item2 = AudioHistoryEntity(
            timestamp = 1700000005000L,
            textSnippet = "আজকের প্রধান সংবাদ।",
            fullText = "আজকের প্রধান সংবাদ। রাজধানী ঢাকাসহ সারাদেশে আগামীকাল আবহাওয়া স্বাভাবিক থাকবে!",
            voiceModel = "সংবাদ (Kiki)",
            speed = 1.25f,
            durationSeconds = 4.2f,
            audioFilePath = "/data/user/0/com.banglatts.voice.aistudio/files/audio_history/BanglaTTS_2.wav"
        )

        repository.insertCreation(item1)
        repository.insertCreation(item2)

        val historyList = repository.allHistory.first()
        assertEquals(2, historyList.size)
        // Ordered by timestamp DESC (newest first)
        assertEquals("সংবাদ (Kiki)", historyList[0].voiceModel)
        assertEquals("আজকের প্রধান সংবাদ।", historyList[0].textSnippet)
        assertEquals("ElevenLabs", historyList[1].voiceModel)

        repository.deleteCreation(historyList[0])
        val afterDelete = repository.allHistory.first()
        assertEquals(1, afterDelete.size)
        assertTrue(afterDelete.first().voiceModel == "ElevenLabs")
    }
}
