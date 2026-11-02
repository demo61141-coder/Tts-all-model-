package com.example

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class VoiceModelSpec(
    val id: String,
    val displayName: String,
    val folderName: String,
    val modelUrl: String,
    val expectedModelBytes: Long,
    val tokensUrl: String,
    val expectedTokensBytes: Long,
    val isMandatory: Boolean
) {
    ELEVENLABS(
        id = "elevenlabs",
        displayName = "ElevenLabs",
        folderName = "model_eleven",
        modelUrl = "https://huggingface.co/demoqqq/banglatts-eleven-new/resolve/main/model.onnx",
        expectedModelBytes = 114160150L,
        tokensUrl = "https://huggingface.co/demoqqq/banglatts-eleven-new/resolve/main/tokens.txt",
        expectedTokensBytes = 567L,
        isMandatory = true
    ),
    KIKI_NEWS(
        id = "kiki",
        displayName = "সংবাদ (Kiki)",
        folderName = "model_kiki",
        modelUrl = "https://huggingface.co/demoqqq/banglatts-combined-news/resolve/main/model.onnx",
        expectedModelBytes = 114333643L,
        tokensUrl = "https://huggingface.co/demoqqq/banglatts-combined-news/resolve/main/tokens.txt",
        expectedTokensBytes = 567L,
        isMandatory = false
    );

    fun getDir(context: Context): File = File(context.filesDir, folderName)
    fun getModelFile(context: Context): File = File(getDir(context), "model.onnx")
    fun getTokensFile(context: Context): File = File(getDir(context), "tokens.txt")

    fun isVerifiedPresent(context: Context): Boolean {
        val modelFile = getModelFile(context)
        val tokensFile = getTokensFile(context)
        return modelFile.exists() &&
            modelFile.length() == expectedModelBytes &&
            tokensFile.exists() &&
            tokensFile.length() == expectedTokensBytes
    }
}

object VoiceModelDownloader {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private const val MAX_RETRIES = 4

    /**
     * Downloads both tokens.txt and model.onnx into filesDir/<folderName>/ with HTTP Range resume,
     * automatic retries, and exact byte size verification.
     */
    suspend fun downloadVoiceModel(
        context: Context,
        spec: VoiceModelSpec,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, statusMessageBn: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetDir = spec.getDir(context)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return@withContext Result.failure(
                    Exception("ফোল্ডার তৈরি করা যায়নি: ${targetDir.name}")
                )
            }

            val totalExpected = spec.expectedTokensBytes + spec.expectedModelBytes

            // 1. Download tokens.txt (567 bytes)
            val tokensFile = spec.getTokensFile(context)
            if (!tokensFile.exists() || tokensFile.length() != spec.expectedTokensBytes) {
                val tokenRes = downloadFileWithRangeAndRetry(
                    url = spec.tokensUrl,
                    destFile = tokensFile,
                    expectedSize = spec.expectedTokensBytes,
                    onBytesProgress = { currentBytes ->
                        val pct = ((currentBytes * 100L) / totalExpected).toInt().coerceIn(0, 100)
                        val pctBn = BengaliTextChunker.toBengaliDigits(pct)
                        onProgress(
                            pct,
                            currentBytes,
                            totalExpected,
                            "${spec.displayName} টোকেন ডাউনলোড হচ্ছে… ($pctBn%)"
                        )
                    }
                )
                if (tokenRes.isFailure) {
                    return@withContext tokenRes
                }
            }

            // 2. Download model.onnx (114MB+)
            val modelFile = spec.getModelFile(context)
            if (!modelFile.exists() || modelFile.length() != spec.expectedModelBytes) {
                val modelRes = downloadFileWithRangeAndRetry(
                    url = spec.modelUrl,
                    destFile = modelFile,
                    expectedSize = spec.expectedModelBytes,
                    onBytesProgress = { currentModelBytes ->
                        val combinedBytes = spec.expectedTokensBytes + currentModelBytes
                        val pct = ((combinedBytes * 100L) / totalExpected).toInt().coerceIn(0, 100)
                        val pctBn = BengaliTextChunker.toBengaliDigits(pct)
                        val mbDone = String.format("%.1f", combinedBytes / (1024.0 * 1024.0))
                        val mbTotal = String.format("%.1f", totalExpected / (1024.0 * 1024.0))
                        val mbDoneBn = BengaliTextChunker.toBengaliDigits(mbDone)
                        val mbTotalBn = BengaliTextChunker.toBengaliDigits(mbTotal)
                        onProgress(
                            pct,
                            combinedBytes,
                            totalExpected,
                            "${spec.displayName} মডেল ডাউনলোড হচ্ছে… $pctBn% ($mbDoneBn / $mbTotalBn মেগাবাইট)"
                        )
                    }
                )
                if (modelRes.isFailure) {
                    return@withContext modelRes
                }
            }

            // Final strict verification
            if (!spec.isVerifiedPresent(context)) {
                val actualModelLen = modelFile.length()
                val actualTokensLen = tokensFile.length()
                return@withContext Result.failure(
                    Exception(
                        "মডেল ফাইলের আকার সঠিক নয় (মডেল: ${BengaliTextChunker.toBengaliDigits(actualModelLen)} বাইট, টোকেন: ${BengaliTextChunker.toBengaliDigits(actualTokensLen)} বাইট)। পুনরায় চেষ্টা করুন।"
                    )
                )
            }

            onProgress(
                100,
                totalExpected,
                totalExpected,
                "${spec.displayName} কণ্ঠ সফলভাবে ডাউনলোড ও যাচাই সম্পন্ন হয়েছে (১০০%)"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                Exception("${spec.displayName} ডাউনলোড ব্যর্থ হয়েছে: ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন। (${e.localizedMessage ?: "ত্রুটি"})")
            )
        }
    }

    private suspend fun downloadFileWithRangeAndRetry(
        url: String,
        destFile: File,
        expectedSize: Long,
        onBytesProgress: (Long) -> Unit
    ): Result<Unit> {
        val partFile = File(destFile.parentFile, "${destFile.name}.part")

        // If destFile already matches exact size, we are done
        if (destFile.exists() && destFile.length() == expectedSize) {
            onBytesProgress(expectedSize)
            return Result.success(Unit)
        }
        if (destFile.exists() && destFile.length() != expectedSize) {
            destFile.delete()
        }

        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            if (!coroutineContext.isActive) {
                return Result.failure(Exception("ডাউনলোড বাতিল করা হয়েছে।"))
            }

            try {
                if (partFile.exists() && partFile.length() > expectedSize) {
                    partFile.delete()
                }
                if (partFile.exists() && partFile.length() == expectedSize) {
                    if (destFile.exists()) destFile.delete()
                    if (partFile.renameTo(destFile)) {
                        onBytesProgress(expectedSize)
                        return Result.success(Unit)
                    }
                }

                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val requestBuilder = Request.Builder().url(url)
                if (existingBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful && code != 206) {
                        if (code == 416) {
                            // Range not satisfiable: reset part file
                            partFile.delete()
                        }
                        throw Exception("সার্ভার ত্রুটি কোড: ${BengaliTextChunker.toBengaliDigits(code)}")
                    }

                    val body = response.body ?: throw Exception("সার্ভার থেকে খালি উত্তর পাওয়া গেছে।")
                    val appendMode = (code == 206 && existingBytes > 0L)
                    var writtenBytes = if (appendMode) existingBytes else 0L

                    if (!appendMode && partFile.exists()) {
                        partFile.delete()
                    }

                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (appendMode) {
                            raf.seek(existingBytes)
                        } else {
                            raf.setLength(0)
                        }

                        val buffer = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        var lastReportTime = 0L

                        while (coroutineContext.isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            writtenBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 120L || writtenBytes == expectedSize) {
                                lastReportTime = now
                                onBytesProgress(writtenBytes)
                            }
                        }
                    }
                }

                if (!coroutineContext.isActive) {
                    return Result.failure(Exception("ডাউনলোড বাতিল করা হয়েছে।"))
                }

                val finalLen = partFile.length()
                if (finalLen != expectedSize) {
                    if (finalLen > expectedSize) {
                        partFile.delete()
                    }
                    throw Exception(
                        "ফাইলের আকার মেলেনি (পাওয়া গেছে ${BengaliTextChunker.toBengaliDigits(finalLen)} বাইট, প্রয়োজন ${BengaliTextChunker.toBengaliDigits(expectedSize)} বাইট)"
                    )
                }

                if (destFile.exists()) {
                    destFile.delete()
                }
                val renamed = partFile.renameTo(destFile)
                if (!renamed) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }

                if (destFile.length() == expectedSize) {
                    onBytesProgress(expectedSize)
                    return Result.success(Unit)
                } else {
                    destFile.delete()
                    throw Exception("ফাইল সংরক্ষণের পর আকার যাচাই ব্যর্থ হয়েছে।")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(1200L * attempt)
                }
            }
        }

        return Result.failure(
            Exception(
                "ডাউনলোড সম্পূর্ণ করা যায়নি (${BengaliTextChunker.toBengaliDigits(MAX_RETRIES)} বার চেষ্টা করা হয়েছে): ${lastException?.message ?: "নেটওয়ার্ক ত্রুটি"}"
            )
        )
    }
}
