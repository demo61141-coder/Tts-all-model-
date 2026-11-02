package com.example

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TextChunk(
    val text: String,
    val trailingMark: Char?,
    val silenceAfterSeconds: Float
)

object BengaliTextChunker {

    private val SPLIT_MARKS = setOf('।', '?', '!', '.', ',', '\'')
    private const val MAX_CHUNK_LENGTH = 300

    /**
     * Splits Bengali text strictly according to rules:
     * 1. Split ONLY after these marks: । ? ! . , '
     * 2. AND ONLY when the mark is followed by a space (' ', '\t', '\n', '\r') or by the end of the text.
     *    A mark inside a word must NEVER split it (e.g., চো'র, র'ক্তের, ১২,৫০০, ১২.৫).
     * 3. Keep the mark at the end of its piece.
     * 4. If a piece is longer than 300 characters, split it further ONLY at spaces (word boundaries),
     *    never inside a word.
     * 5. Silence pauses:
     *    after । -> 0.40 seconds
     *    after ? or ! -> 0.30 seconds
     *    after , or . or ' -> 0.20 seconds
     */
    fun chunkText(rawText: String): List<TextChunk> {
        val normalized = rawText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        val initialPieces = mutableListOf<Pair<String, Char?>>()
        val currentBuilder = StringBuilder()

        var i = 0
        val len = normalized.length
        while (i < len) {
            val ch = normalized[i]
            currentBuilder.append(ch)

            if (ch in SPLIT_MARKS) {
                val isAtEnd = (i == len - 1)
                val isFollowedBySpace = (i + 1 < len && normalized[i + 1].isWhitespace())
                if (isAtEnd || isFollowedBySpace) {
                    val piece = currentBuilder.toString().trim()
                    if (piece.isNotEmpty()) {
                        initialPieces.add(piece to ch)
                    }
                    currentBuilder.clear()
                    // Skip following whitespace
                    while (i + 1 < len && normalized[i + 1].isWhitespace()) {
                        i++
                    }
                }
            }
            i++
        }

        val remaining = currentBuilder.toString().trim()
        if (remaining.isNotEmpty()) {
            val lastChar = remaining.last()
            val mark = if (lastChar in SPLIT_MARKS) lastChar else null
            initialPieces.add(remaining to mark)
        }

        val finalChunks = mutableListOf<TextChunk>()
        for ((piece, endMark) in initialPieces) {
            if (piece.length <= MAX_CHUNK_LENGTH) {
                finalChunks.add(
                    TextChunk(
                        text = piece,
                        trailingMark = endMark,
                        silenceAfterSeconds = getSilenceForMark(endMark)
                    )
                )
            } else {
                val subPieces = splitByWordBoundaries(piece, MAX_CHUNK_LENGTH)
                for (idx in subPieces.indices) {
                    val isLastSub = (idx == subPieces.lastIndex)
                    val mark = if (isLastSub) endMark else null
                    val silence = if (isLastSub) getSilenceForMark(endMark) else 0.0f
                    finalChunks.add(
                        TextChunk(
                            text = subPieces[idx],
                            trailingMark = mark,
                            silenceAfterSeconds = silence
                        )
                    )
                }
            }
        }

        return finalChunks
    }

    private fun splitByWordBoundaries(piece: String, maxLen: Int): List<String> {
        val words = piece.split(" ")
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (word in words) {
            if (word.isEmpty()) continue
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxLen) {
                current.append(' ').append(word)
            } else {
                result.add(current.toString())
                current.clear()
                current.append(word)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    fun getSilenceForMark(mark: Char?): Float {
        return when (mark) {
            '।' -> 0.40f
            '?', '!' -> 0.30f
            ',', '.', '\'' -> 0.20f
            else -> 0.0f
        }
    }

    /**
     * Converts standard ASCII digits 0-9 into Bengali digits ০-৯.
     */
    fun toBengaliDigits(number: Number): String {
        return toBengaliDigits(number.toString())
    }

    fun toBengaliDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(('০'.code + (ch.code - '0'.code)).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Creates a concise preview snippet of source text (up to maxChars).
     */
    fun createSnippet(text: String, maxChars: Int = 90): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= maxChars) {
            clean
        } else {
            clean.take(maxChars).trimEnd() + "…"
        }
    }

    /**
     * Converts float[] samples (-1.0f to 1.0f) to 16-bit PCM ShortArray.
     */
    fun floatToPcm16(samples: FloatArray): ShortArray {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            pcm[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return pcm
    }

    /**
     * Creates silence 16-bit PCM ShortArray for given duration and sampleRate.
     */
    fun createSilencePcm16(seconds: Float, sampleRate: Int = 22050): ShortArray {
        if (seconds <= 0f) return ShortArray(0)
        val numSamples = (seconds * sampleRate).toInt().coerceAtLeast(0)
        return ShortArray(numSamples)
    }

    /**
     * Builds a standard 44-byte RIFF WAVE header + 16-bit mono PCM payload.
     */
    fun encodeWavBytes(pcmSamples: ShortArray, sampleRate: Int = 22050): ByteArray {
        val dataSize = pcmSamples.size * 2
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * 2 // mono * 16-bit (2 bytes)

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt subchunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(1) // NumChannels = 1 (Mono)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2) // BlockAlign = NumChannels * BitsPerSample/8
        buffer.putShort(16) // BitsPerSample = 16
        // data subchunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (sample in pcmSamples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    /**
     * Decodes a 16-bit mono WAV byte array back into ShortArray PCM samples and sampleRate.
     */
    fun decodeWavFileToPcm16(wavBytes: ByteArray): Pair<ShortArray, Int>? {
        if (wavBytes.size <= 44) return null
        return try {
            val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = buffer.getInt(24).takeIf { it in 8000..96000 } ?: 22050
            val dataOffset = 44
            val numSamples = (wavBytes.size - dataOffset) / 2
            val pcm = ShortArray(numSamples)
            buffer.position(dataOffset)
            for (i in 0 until numSamples) {
                pcm[i] = buffer.short
            }
            pcm to sampleRate
        } catch (_: Throwable) {
            null
        }
    }
}
