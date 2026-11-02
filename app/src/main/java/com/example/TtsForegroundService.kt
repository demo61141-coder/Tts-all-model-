package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import com.example.data.AudioHistoryEntity
import com.example.data.AudioHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class TtsSessionState(
    val isRunning: Boolean = false,
    val isPlayingCompletedAudio: Boolean = false,
    val currentPiece: Int = 0,
    val totalPieces: Int = 0,
    val progressPercent: Int = 0,
    val activePieceText: String = "",
    val statusBn: String = "প্রস্তুত — টেক্সট লিখে Generate চাপুন",
    val errorBn: String? = null,
    val hasCompletedWav: Boolean = false,
    val sampleRate: Int = 22050,
    val durationSeconds: Float = 0f,
    val activeVoiceName: String = "ElevenLabs",
    val playingHistoryId: Long? = null
)

class TtsForegroundService : Service() {

    companion object {
        const val ACTION_START_GENERATE = "com.banglatts.voice.aistudio.ACTION_START_GENERATE"
        const val ACTION_PLAY_SAVED_BUFFER = "com.banglatts.voice.aistudio.ACTION_PLAY_SAVED_BUFFER"
        const val ACTION_PLAY_HISTORY_FILE = "com.banglatts.voice.aistudio.ACTION_PLAY_HISTORY_FILE"
        const val ACTION_STOP = "com.banglatts.voice.aistudio.ACTION_STOP"

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_VOICE_KEY = "extra_voice_key"
        const val EXTRA_VOICE_NAME = "extra_voice_name"
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_TOKENS_PATH = "extra_tokens_path"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_NOISE_SCALE = "extra_noise_scale"
        const val EXTRA_NOISE_SCALE_W = "extra_noise_scale_w"
        const val EXTRA_LENGTH_SCALE = "extra_length_scale"
        const val EXTRA_VOLUME_GAIN = "extra_volume_gain"

        const val EXTRA_HISTORY_ID = "extra_history_id"
        const val EXTRA_HISTORY_FILE_PATH = "extra_history_file_path"
        const val EXTRA_HISTORY_TITLE = "extra_history_title"

        private const val CHANNEL_ID = "bangla_tts_media_channel"
        private const val NOTIFICATION_ID = 1001

        private val _sessionState = MutableStateFlow(TtsSessionState())
        val sessionState: StateFlow<TtsSessionState> = _sessionState.asStateFlow()

        private val pcmLock = Any()
        private var lastGeneratedPcm: ShortArray? = null
        private var lastSampleRate: Int = 22050

        fun getLastWavBytes(): ByteArray? {
            synchronized(pcmLock) {
                val pcm = lastGeneratedPcm ?: return null
                if (pcm.isEmpty()) return null
                return BengaliTextChunker.encodeWavBytes(pcm, lastSampleRate)
            }
        }

        fun updateStatusExternal(statusBn: String, errorBn: String? = null) {
            _sessionState.value = _sessionState.value.copy(
                statusBn = statusBn,
                errorBn = errorBn
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopActiveWorkAndService(stoppedByUser = true)
            }
            ACTION_PLAY_SAVED_BUFFER -> {
                startForegroundWithNotification("অডিও চালানো হচ্ছে…", 0, 0)
                playExistingBuffer()
            }
            ACTION_PLAY_HISTORY_FILE -> {
                val historyId = intent.getLongExtra(EXTRA_HISTORY_ID, -1L)
                val filePath = intent.getStringExtra(EXTRA_HISTORY_FILE_PATH).orEmpty()
                val title = intent.getStringExtra(EXTRA_HISTORY_TITLE).orEmpty()
                startForegroundWithNotification("ইতিহাস থেকে অডিও বাজছে…", 0, 0)
                playHistoryWavFile(historyId, filePath, title)
            }
            ACTION_START_GENERATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                val voiceKey = intent.getStringExtra(EXTRA_VOICE_KEY) ?: "elevenlabs"
                val voiceName = intent.getStringExtra(EXTRA_VOICE_NAME) ?: "ElevenLabs"
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH).orEmpty()
                val tokensPath = intent.getStringExtra(EXTRA_TOKENS_PATH).orEmpty()
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                val noiseScale = intent.getFloatExtra(EXTRA_NOISE_SCALE, 0.667f)
                val noiseScaleW = intent.getFloatExtra(EXTRA_NOISE_SCALE_W, 0.8f)
                val lengthScale = intent.getFloatExtra(EXTRA_LENGTH_SCALE, 1.0f)
                val volumeGain = intent.getFloatExtra(EXTRA_VOLUME_GAIN, 1.0f)

                startForegroundWithNotification("অডিও তৈরি হচ্ছে…", 0, 0)
                startSynthesisAndStreaming(
                    text = text,
                    voiceKey = voiceKey,
                    voiceName = voiceName,
                    modelPath = modelPath,
                    tokensPath = tokensPath,
                    speed = speed,
                    noiseScale = noiseScale,
                    noiseScaleW = noiseScaleW,
                    lengthScale = lengthScale,
                    volumeGain = volumeGain
                )
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSynthesisAndStreaming(
        text: String,
        voiceKey: String,
        voiceName: String,
        modelPath: String,
        tokensPath: String,
        speed: Float,
        noiseScale: Float,
        noiseScaleW: Float,
        lengthScale: Float,
        volumeGain: Float
    ) {
        activeJob?.cancel()
        releaseAudioTrack()

        if (text.isBlank()) {
            _sessionState.value = _sessionState.value.copy(
                isRunning = false,
                isPlayingCompletedAudio = false,
                playingHistoryId = null,
                errorBn = "অনুগ্রহ করে প্রথমে বাংলা টেক্সট লিখুন বা পেস্ট করুন।",
                statusBn = "টেক্সট খালি রয়েছে"
            )
            stopForegroundAndSelf()
            return
        }

        val chunks = BengaliTextChunker.chunkText(text)
        if (chunks.isEmpty()) {
            _sessionState.value = _sessionState.value.copy(
                isRunning = false,
                isPlayingCompletedAudio = false,
                playingHistoryId = null,
                errorBn = "প্রক্রিয়াকরণের জন্য কোনো বৈধ বাক্য পাওয়া যায়নি।",
                statusBn = "টেক্সট খালি রয়েছে"
            )
            stopForegroundAndSelf()
            return
        }

        val totalPieces = chunks.size
        val totalBn = BengaliTextChunker.toBengaliDigits(totalPieces)

        _sessionState.value = _sessionState.value.copy(
            isRunning = true,
            isPlayingCompletedAudio = false,
            playingHistoryId = null,
            currentPiece = 0,
            totalPieces = totalPieces,
            progressPercent = 0,
            activePieceText = chunks.first().text,
            statusBn = "$voiceName মডেল লোড হচ্ছে… (মোট খণ্ড: $totalBn)",
            errorBn = null,
            activeVoiceName = voiceName
        )

        activeJob = serviceScope.launch {
            try {
                // 1. Lazily load or reuse model in memory
                val engineResult = TtsEngineHolder.getOrLoadEngine(
                    voiceKey = voiceKey,
                    modelPath = modelPath,
                    tokensPath = tokensPath,
                    noiseScale = noiseScale,
                    noiseScaleW = noiseScaleW,
                    lengthScale = lengthScale
                )

                if (engineResult.isFailure) {
                    val errMsg = engineResult.exceptionOrNull()?.message
                        ?: "ভয়েস মডেল লোড করতে ব্যর্থ হয়েছে।"
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        statusBn = "মডেল লোড ব্যর্থ হয়েছে",
                        errorBn = errMsg
                    )
                    stopForegroundAndSelf()
                    return@launch
                }

                val tts = engineResult.getOrThrow()
                val sampleRate = 22050 // Mandatory 22050 Hz mono 16-bit PCM

                val audioTrack = createStreamingAudioTrack(sampleRate)
                activeAudioTrack = audioTrack
                var playbackStarted = false

                // Accumulate all pieces + pause silence into a growable PCM buffer for WAV saving
                var pcmCapacity = sampleRate * 10
                var fullPcmBuffer = ShortArray(pcmCapacity)
                var fullPcmSize = 0

                fun appendToWavBuffer(samples: ShortArray) {
                    if (samples.isEmpty()) return
                    val needed = fullPcmSize + samples.size
                    if (needed > fullPcmBuffer.size) {
                        var newCap = fullPcmBuffer.size * 2
                        while (newCap < needed) newCap *= 2
                        fullPcmBuffer = fullPcmBuffer.copyOf(newCap)
                    }
                    System.arraycopy(samples, 0, fullPcmBuffer, fullPcmSize, samples.size)
                    fullPcmSize += samples.size
                }

                for (index in chunks.indices) {
                    if (!isActive) break

                    val pieceNum = index + 1
                    val chunk = chunks[index]
                    val pieceBn = BengaliTextChunker.toBengaliDigits(pieceNum)
                    val pctBefore = ((index * 100) / totalPieces).coerceIn(0, 99)
                    val pctBeforeBn = BengaliTextChunker.toBengaliDigits(pctBefore)

                    val progressText = "অডিও তৈরি হচ্ছে… খণ্ড $pieceBn / $totalBn ($pctBeforeBn%)"
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = true,
                        currentPiece = pieceNum,
                        totalPieces = totalPieces,
                        progressPercent = pctBefore,
                        activePieceText = chunk.text,
                        statusBn = progressText,
                        errorBn = null
                    )
                    updateNotification(progressText, pieceNum, totalPieces)

                    // Generate audio for this piece with selected speed
                    val generated = tts.generate(text = chunk.text, sid = 0, speed = speed)
                    if (!isActive) break

                    val rawFloats = generated.samples
                    val scaledFloats = if (volumeGain != 1.0f) {
                        FloatArray(rawFloats.size) { i -> rawFloats[i] * volumeGain }
                    } else {
                        rawFloats
                    }

                    val piecePcm = BengaliTextChunker.floatToPcm16(scaledFloats)
                    val silencePcm = BengaliTextChunker.createSilencePcm16(
                        seconds = chunk.silenceAfterSeconds,
                        sampleRate = sampleRate
                    )

                    // Append to complete WAV output buffer immediately
                    appendToWavBuffer(piecePcm)
                    if (silencePcm.isNotEmpty()) {
                        appendToWavBuffer(silencePcm)
                    }

                    // Update progress as soon as piece is synthesized
                    val pctAfter = ((pieceNum * 100) / totalPieces).coerceIn(1, 100)
                    val pctAfterBn = BengaliTextChunker.toBengaliDigits(pctAfter)
                    val playingStatus = "চলছে… খণ্ড $pieceBn / $totalBn ($pctAfterBn%)"
                    _sessionState.value = _sessionState.value.copy(
                        currentPiece = pieceNum,
                        totalPieces = totalPieces,
                        progressPercent = pctAfter,
                        statusBn = playingStatus
                    )
                    updateNotification(playingStatus, pieceNum, totalPieces)

                    // Start playing IMMEDIATELY as soon as the FIRST piece is ready
                    if (!playbackStarted && isActive) {
                        audioTrack.play()
                        playbackStarted = true
                    }

                    // Stream piece PCM to AudioTrack
                    writeAllPcmBlocking(audioTrack, piecePcm)
                    if (!isActive) break

                    // Stream silence pause between pieces
                    if (silencePcm.isNotEmpty()) {
                        writeAllPcmBlocking(audioTrack, silencePcm)
                    }
                }

                if (isActive && fullPcmSize > 0) {
                    val completedPcm = fullPcmBuffer.copyOf(fullPcmSize)
                    synchronized(pcmLock) {
                        lastGeneratedPcm = completedPcm
                        lastSampleRate = sampleRate
                    }
                    val totalDurationSec = completedPcm.size.toFloat() / sampleRate.toFloat()
                    val durBn = BengaliTextChunker.toBengaliDigits(
                        String.format("%.1f", totalDurationSec)
                    )

                    // Save generated WAV to local history folder and record in Room database
                    saveCompletedCreationToRoomHistory(
                        fullText = text,
                        voiceName = voiceName,
                        speed = speed,
                        durationSeconds = totalDurationSec,
                        pcmSamples = completedPcm,
                        sampleRate = sampleRate
                    )

                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        currentPiece = totalPieces,
                        totalPieces = totalPieces,
                        progressPercent = 100,
                        activePieceText = "",
                        statusBn = "সম্পন্ন ও ইতিহাসে সংরক্ষিত (১০০%) — মোট সময় $durBn সেকেন্ড",
                        errorBn = null,
                        hasCompletedWav = true,
                        sampleRate = sampleRate,
                        durationSeconds = totalDurationSec
                    )
                }
            } catch (t: Throwable) {
                if (isActive) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        statusBn = "অডিও তৈরিতে ত্রুটি ঘটেছে",
                        errorBn = "অডিও তৈরি ব্যর্থ হয়েছে: ${t.localizedMessage ?: "অজানা ত্রুটি"}"
                    )
                }
            } finally {
                releaseAudioTrack()
                stopForegroundAndSelf()
            }
        }
    }

    private suspend fun saveCompletedCreationToRoomHistory(
        fullText: String,
        voiceName: String,
        speed: Float,
        durationSeconds: Float,
        pcmSamples: ShortArray,
        sampleRate: Int
    ) {
        try {
            val now = System.currentTimeMillis()
            val historyDir = File(applicationContext.filesDir, "audio_history")
            if (!historyDir.exists()) {
                historyDir.mkdirs()
            }
            val wavFile = File(historyDir, "BanglaTTS_$now.wav")
            val wavBytes = BengaliTextChunker.encodeWavBytes(pcmSamples, sampleRate)
            FileOutputStream(wavFile).use { out ->
                out.write(wavBytes)
                out.flush()
            }

            val snippet = BengaliTextChunker.createSnippet(fullText, 90)
            val entity = AudioHistoryEntity(
                timestamp = now,
                textSnippet = snippet,
                fullText = fullText,
                voiceModel = voiceName,
                speed = speed,
                durationSeconds = durationSeconds,
                audioFilePath = wavFile.absolutePath
            )
            AudioHistoryRepository.getInstance(applicationContext).insertCreation(entity)
        } catch (_: Throwable) {
            // Non-fatal if disk is full
        }
    }

    private fun playHistoryWavFile(historyId: Long, filePath: String, titleSnippet: String) {
        activeJob?.cancel()
        releaseAudioTrack()

        val file = File(filePath)
        if (!file.exists() || file.length() <= 44L) {
            _sessionState.value = _sessionState.value.copy(
                isRunning = false,
                isPlayingCompletedAudio = false,
                playingHistoryId = null,
                errorBn = "সংরক্ষিত অডিও ফাইলটি খুঁজে পাওয়া যায়নি।"
            )
            stopForegroundAndSelf()
            return
        }

        activeJob = serviceScope.launch {
            try {
                val wavBytes = file.readBytes()
                val decoded = BengaliTextChunker.decodeWavFileToPcm16(wavBytes)
                if (decoded == null || decoded.first.isEmpty()) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        errorBn = "অডিও ফাইলটি পড়তে সমস্যা হয়েছে।"
                    )
                    stopForegroundAndSelf()
                    return@launch
                }

                val (pcmData, sampleRate) = decoded
                synchronized(pcmLock) {
                    lastGeneratedPcm = pcmData
                    lastSampleRate = sampleRate
                }

                val track = createStreamingAudioTrack(sampleRate)
                activeAudioTrack = track
                track.play()

                val totalSamples = pcmData.size
                val step = sampleRate / 4
                var offset = 0

                while (isActive && offset < totalSamples) {
                    val count = minOf(step, totalSamples - offset)
                    val pct = ((offset.toLong() * 100L) / totalSamples).toInt().coerceIn(0, 100)
                    val pctBn = BengaliTextChunker.toBengaliDigits(pct)
                    val statusText = "ইতিহাস থেকে বাজছে ($pctBn%): $titleSnippet"
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = true,
                        isPlayingCompletedAudio = true,
                        playingHistoryId = historyId,
                        progressPercent = pct,
                        activePieceText = titleSnippet,
                        statusBn = statusText,
                        hasCompletedWav = true,
                        errorBn = null
                    )
                    updateNotification(statusText, pct, 100)
                    val written = track.write(pcmData, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }

                if (isActive) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        activePieceText = "",
                        progressPercent = 100,
                        hasCompletedWav = true,
                        statusBn = "ইতিহাস থেকে অডিও প্লেব্যাক সম্পন্ন হয়েছে (১০০%)"
                    )
                }
            } catch (t: Throwable) {
                if (isActive) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        errorBn = "প্লেব্যাক ত্রুটি: ${t.localizedMessage ?: "অজানা ত্রুটি"}"
                    )
                }
            } finally {
                releaseAudioTrack()
                stopForegroundAndSelf()
            }
        }
    }

    private fun playExistingBuffer() {
        activeJob?.cancel()
        releaseAudioTrack()

        val pcmData = synchronized(pcmLock) { lastGeneratedPcm }
        val sampleRate = synchronized(pcmLock) { lastSampleRate }

        if (pcmData == null || pcmData.isEmpty()) {
            _sessionState.value = _sessionState.value.copy(
                isRunning = false,
                isPlayingCompletedAudio = false,
                playingHistoryId = null,
                errorBn = "চালানোর মতো কোনো অডিও নেই। প্রথমে অডিও তৈরি করুন (Generate)।"
            )
            stopForegroundAndSelf()
            return
        }

        activeJob = serviceScope.launch {
            try {
                val track = createStreamingAudioTrack(sampleRate)
                activeAudioTrack = track
                track.play()

                val totalSamples = pcmData.size
                val step = sampleRate / 4 // 250ms chunks
                var offset = 0

                while (isActive && offset < totalSamples) {
                    val count = minOf(step, totalSamples - offset)
                    val pct = ((offset.toLong() * 100L) / totalSamples).toInt().coerceIn(0, 100)
                    val pctBn = BengaliTextChunker.toBengaliDigits(pct)
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = true,
                        isPlayingCompletedAudio = true,
                        playingHistoryId = null,
                        progressPercent = pct,
                        statusBn = "সংরক্ষিত অডিও বাজছে… ($pctBn%)",
                        errorBn = null
                    )
                    val written = track.write(pcmData, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }

                if (isActive) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        progressPercent = 100,
                        statusBn = "অডিও প্লেব্যাক সম্পন্ন হয়েছে (১০০%)"
                    )
                }
            } catch (t: Throwable) {
                if (isActive) {
                    _sessionState.value = _sessionState.value.copy(
                        isRunning = false,
                        isPlayingCompletedAudio = false,
                        playingHistoryId = null,
                        errorBn = "প্লেব্যাক ত্রুটি: ${t.localizedMessage ?: "অজানা ত্রুটি"}"
                    )
                }
            } finally {
                releaseAudioTrack()
                stopForegroundAndSelf()
            }
        }
    }

    private fun writeAllPcmBlocking(audioTrack: AudioTrack, samples: ShortArray) {
        if (samples.isEmpty()) return
        val chunkSize = 2048
        var offset = 0
        while (offset < samples.size) {
            val toWrite = minOf(chunkSize, samples.size - offset)
            val written = audioTrack.write(samples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private fun createStreamingAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()
    }

    private fun stopActiveWorkAndService(stoppedByUser: Boolean) {
        activeJob?.cancel()
        activeJob = null
        releaseAudioTrack()
        if (stoppedByUser) {
            val hasWav = synchronized(pcmLock) { lastGeneratedPcm != null && lastGeneratedPcm!!.isNotEmpty() }
            _sessionState.value = _sessionState.value.copy(
                isRunning = false,
                isPlayingCompletedAudio = false,
                playingHistoryId = null,
                activePieceText = "",
                statusBn = "অডিও প্রসেসিং ও প্লেব্যাক থামানো হয়েছে।",
                hasCompletedWav = hasWav
            )
        }
        stopForegroundAndSelf()
    }

    private fun releaseAudioTrack() {
        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            try {
                track.pause()
                track.flush()
            } catch (_: Throwable) {
            }
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "বাংলা টিটিএস ব্যাকগ্রাউন্ড সার্ভিস",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "অফলাইন বাংলা টেক্সট-টু-স্পিচ অডিও জেনারেশন ও প্লেব্যাক"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, currentPiece: Int, totalPieces: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TtsForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("অডিও তৈরি হচ্ছে…")
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (totalPieces > 0) {
            builder.setProgress(totalPieces, currentPiece.coerceIn(0, totalPieces), false)
        } else {
            builder.setProgress(100, 0, true)
        }

        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_media_pause,
            "থামান (Stop)",
            stopPendingIntent
        ).build()
        builder.addAction(stopAction)

        return builder.build()
    }

    private fun startForegroundWithNotification(text: String, currentPiece: Int, totalPieces: Int) {
        val notification = buildNotification(text, currentPiece, totalPieces)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
        }
    }

    private fun updateNotification(text: String, currentPiece: Int, totalPieces: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text, currentPiece, totalPieces))
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        releaseAudioTrack()
        serviceScope.cancel()
        super.onDestroy()
    }
}
