package com.danemadsen.maise.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveChunkerTest {

    @Test
    fun testEmptyInput() {
        assertTrue(AdaptiveChunker.chunkText("").isEmpty())
        assertTrue(AdaptiveChunker.chunkText("   ").isEmpty())
    }

    @Test
    fun testShortSentenceFirstChunkWordCount() {
        val input = "Hello world!"
        val chunks = AdaptiveChunker.chunkText(input)
        assertEquals(1, chunks.size)
        assertEquals("Hello world!", chunks[0])
    }

    @Test
    fun testMultipleSentencesFirstChunkIsShort() {
        val input = "This is sentence one. This is sentence two which contains slightly more words for testing. And a third sentence."
        val chunks = AdaptiveChunker.chunkText(input)
        assertTrue(chunks.isNotEmpty())
        val firstChunkWords = chunks[0].split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
        assertTrue("First chunk should be <= 18 words for fast TTFA, got $firstChunkWords", firstChunkWords <= 18)
    }

    @Test
    fun testSubsequentChunksWordCountLimit() {
        val input = "First short chunk. This is a very long second sentence that contains many words, and it should be split across clause boundaries into smaller sub-chunks so that each chunk remains short and fast to synthesize without interruption."
        val chunks = AdaptiveChunker.chunkText(input)
        assertTrue(chunks.isNotEmpty())
        for ((idx, chunk) in chunks.withIndex()) {
            val wordCount = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            val maxAllowed = if (idx == 0) 18 else 35
            assertTrue("Chunk #$idx '$chunk' word count $wordCount should be <= $maxAllowed", wordCount <= maxAllowed)
        }
    }

    @Test
    fun testAbbreviationPreservationInChunking() {
        val input = "Mr. Smith went to visit Dr. Jones at 5 p.m. in the e.g. center."
        val chunks = AdaptiveChunker.chunkText(input)
        // Abbreviations expanded by normalizer ("mister Smith...", "doctor Jones...") so no false splits occur
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            val wordCount = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            assertTrue("Chunk '$chunk' word count $wordCount should be <= 35", wordCount <= 35)
        }
    }

    @Test
    fun testNumberAndOrdinalPreservation() {
        val input = "The 1st runner placed 42nd and scored -3.14 points."
        val chunks = AdaptiveChunker.chunkText(input)
        assertTrue(chunks.isNotEmpty())
        val combined = chunks.joinToString(" ")
        assertTrue(combined.contains("first"))
        assertTrue(combined.contains("forty"))
        assertTrue(combined.contains("minus three point one four"))
    }
}
