package com.danemadsen.maise.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenPhonemizerTest {

    @Test
    fun testSplitSentences() {
        val input = "Hello world! This is a test. How are you?"
        val chunks = splitSentences(input)
        assertEquals(1, chunks.size)
        assertEquals("Hello world! This is a test. How are you?", chunks[0])
    }

    @Test
    fun testSplitSentencesLongClause() {
        val input = "This is a very long sentence that contains many words, and it should be split into smaller clause chunks so that each chunk remains short and fast to synthesize."
        val chunks = splitSentences(input)
        assertTrue("Long sentence should be split into chunks if long", chunks.isNotEmpty())
        for (chunk in chunks) {
            val wordCount = chunk.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
            assertTrue("Chunk '$chunk' word count $wordCount should be <= 35", wordCount <= 35)
        }
    }

    @Test
    fun testOutputTokenizerEncoding() {
        val phonemes = "dˈoʊnt"
        val tokens = OpenPhonemizerOutputTokenizer.encode(phonemes)

        // Tokens wrap with SPECIAL_ID (0) on both ends
        assertEquals(0, tokens.first())
        assertEquals(0, tokens.last())
        assertEquals(phonemes.length + 2, tokens.size)

        // Ensure no characters map to SPECIAL_ID (0) in the inner phoneme sequence
        for (i in 1 until tokens.size - 1) {
            assertTrue("Token at position $i should not be SPECIAL_ID", tokens[i] != 0)
        }
    }

    @Test
    fun testOverrideAssetPhonemesValidity() {
        var file = File("open-phonemizer/src/main/assets/pronunciation_overrides.json")
        if (!file.exists()) {
            file = File("../open-phonemizer/src/main/assets/pronunciation_overrides.json")
        }
        assertTrue("pronunciation_overrides.json must exist in assets", file.exists())

        val content = file.readText(Charsets.UTF_8)
        val regex = Regex(""""([^"]+)":\s*"([^"]+)"""")
        val matches = regex.findAll(content).toList()
        assertTrue("Overrides JSON must contain pronunciation entries", matches.isNotEmpty())

        for (match in matches) {
            val key = match.groupValues[1]
            val phonemes = match.groupValues[2]

            if (key == "en_us") continue

            assertTrue("Key '$key' must not be empty", key.isNotBlank())
            assertTrue("Phonemes for '$key' must not be empty", phonemes.isNotBlank())

            val tokens = OpenPhonemizerOutputTokenizer.encode(phonemes)
            for (i in 1 until tokens.size - 1) {
                val charAt = phonemes[i - 1]
                assertTrue(
                    "Phoneme symbol '$charAt' in '$key' -> '$phonemes' must be valid in Kokoro tokenizer",
                    tokens[i] != 0
                )
            }
        }
    }
}
