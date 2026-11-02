package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `chunkText keeps words with internal marks whole and splits after trailing marks`() {
        val text = "চো'র ও র'ক্তের দাম ১২,৫০০ টাকা বা ১২.৫ শতাংশ। আপনি কি জানেন? দারুণ খবর! হ্যাঁ, ঠিক।"
        val chunks = BengaliTextChunker.chunkText(text)

        assertEquals(5, chunks.size)
        assertEquals("চো'র ও র'ক্তের দাম ১২,৫০০ টাকা বা ১২.৫ শতাংশ।", chunks[0].text)
        assertEquals(0.40f, chunks[0].silenceAfterSeconds, 0.001f)

        assertEquals("আপনি কি জানেন?", chunks[1].text)
        assertEquals(0.30f, chunks[1].silenceAfterSeconds, 0.001f)

        assertEquals("দারুণ খবর!", chunks[2].text)
        assertEquals(0.30f, chunks[2].silenceAfterSeconds, 0.001f)

        assertEquals("হ্যাঁ,", chunks[3].text)
        assertEquals(0.20f, chunks[3].silenceAfterSeconds, 0.001f)

        assertEquals("ঠিক।", chunks[4].text)
        assertEquals(0.40f, chunks[4].silenceAfterSeconds, 0.001f)
    }

    @Test
    fun `chunkText splits pieces longer than 300 characters only at word boundaries`() {
        val word = "বাংলাদেশ" // 8 chars
        val longSentence = (1..45).joinToString(" ") { word } + "।"
        assertTrue(longSentence.length > 300)

        val chunks = BengaliTextChunker.chunkText(longSentence)
        assertTrue(chunks.size >= 2)
        for (c in chunks) {
            assertTrue("Piece length ${c.text.length} exceeds 300", c.text.length <= 300)
        }
        assertEquals(0.40f, chunks.last().silenceAfterSeconds, 0.001f)
    }

    @Test
    fun `toBengaliDigits converts numbers accurately`() {
        assertEquals("১২৩৪৫৬৭৮৯০", BengaliTextChunker.toBengaliDigits("1234567890"))
        assertEquals("১০০%", BengaliTextChunker.toBengaliDigits("100%"))
    }
}
