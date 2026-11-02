package com.example

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Manages the Sherpa-ONNX OfflineTts VITS engine in memory.
 * - Loaded LAZILY on first synthesis only (never at app open).
 * - Loads only the active voice; when switching voice, unloads the previous model to save RAM.
 * - Keeps the loaded model in memory across generations so it is not reloaded for every text.
 * - VITS settings: noiseScale = 0.667f, noiseScaleW = 0.8f, lengthScale = 1.0f, CPU, 2 threads.
 */
object TtsEngineHolder {

    private val lock = Any()

    @Volatile
    private var currentVoiceKey: String? = null

    @Volatile
    private var offlineTts: OfflineTts? = null

    fun getLoadedVoiceKey(): String? = currentVoiceKey

    fun unloadCurrentVoice() {
        synchronized(lock) {
            try {
                offlineTts?.release()
            } catch (_: Throwable) {
            } finally {
                offlineTts = null
                currentVoiceKey = null
            }
        }
    }

    /**
     * Ensures the requested model is loaded in memory, unloading any other voice first.
     */
    fun getOrLoadEngine(
        voiceKey: String,
        modelPath: String,
        tokensPath: String,
        noiseScale: Float = 0.667f,
        noiseScaleW: Float = 0.8f,
        lengthScale: Float = 1.0f
    ): Result<OfflineTts> {
        synchronized(lock) {
            try {
                val existing = offlineTts
                if (existing != null && currentVoiceKey == voiceKey) {
                    return Result.success(existing)
                }

                // Unload previous voice to save RAM
                if (existing != null) {
                    try {
                        existing.release()
                    } catch (_: Throwable) {
                    }
                    offlineTts = null
                    currentVoiceKey = null
                }

                val modelFile = File(modelPath)
                val tokensFile = File(tokensPath)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    return Result.failure(
                        Exception("মডেল ফাইল পাওয়া যায়নি (${modelFile.name})। অনুগ্রহ করে প্রথমে ডাউনলোড করুন।")
                    )
                }
                if (!tokensFile.exists() || tokensFile.length() == 0L) {
                    return Result.failure(
                        Exception("টোকেন ফাইল পাওয়া যায়নি (${tokensFile.name})। অনুগ্রহ করে প্রথমে ডাউনলোড করুন।")
                    )
                }

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = "",
                    tokens = tokensFile.absolutePath,
                    dataDir = "",
                    dictDir = "",
                    noiseScale = noiseScale,
                    noiseScaleW = noiseScaleW,
                    lengthScale = lengthScale
                )

                val modelConfig = OfflineTtsModelConfig().apply {
                    vits = vitsConfig
                    numThreads = 2
                    debug = false
                    provider = "cpu"
                }

                val config = OfflineTtsConfig().apply {
                    model = modelConfig
                    ruleFsts = ""
                    ruleFars = ""
                    maxNumSentences = 1
                    silenceScale = 1.0f
                }

                // assetManager = null ensures Sherpa-ONNX loads directly from file paths
                val instance = OfflineTts(assetManager = null, config = config)
                offlineTts = instance
                currentVoiceKey = voiceKey
                return Result.success(instance)
            } catch (t: Throwable) {
                offlineTts = null
                currentVoiceKey = null
                return Result.failure(
                    Exception("ভয়েস ইঞ্জিন লোড করতে সমস্যা হয়েছে: ${t.localizedMessage ?: "অজানা ত্রুটি"}")
                )
            }
        }
    }
}
