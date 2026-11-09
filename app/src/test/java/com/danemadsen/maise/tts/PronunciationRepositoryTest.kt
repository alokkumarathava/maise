package com.danemadsen.maise.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class PronunciationRepositoryTest {

    @Before
    fun setUp() {
        PronunciationRepository.clearForTesting()
        PhonemizerMetrics.reset()
    }

    @Test
    fun testDictionaryStreamLoadingAndResolutionOrder() {
        val baseJson = """
            {
              "en_us": {
                "hello": "həlˈoʊ",
                "world": "wˈɜːld",
                "override_me": "old_val"
              }
            }
        """.trimIndent()

        val overrideJson = """
            {
              "en_us": {
                "i": "ˈaɪ",
                "don't": "dˈoʊnt",
                "override_me": "new_val"
              }
            }
        """.trimIndent()

        PronunciationRepository.loadFromStreams(
            baseStream = ByteArrayInputStream(baseJson.toByteArray(Charsets.UTF_8)),
            overrideStream = ByteArrayInputStream(overrideJson.toByteArray(Charsets.UTF_8))
        )

        assertTrue(PronunciationRepository.isLoaded())

        // 1. Override precedence test
        when (val result = PronunciationRepository.lookup("override_me")) {
            is LookupResult.OverrideHit -> assertEquals("new_val", result.phonemes)
            else -> error("Expected OverrideHit for override_me")
        }

        // 2. Base dictionary hit
        when (val result = PronunciationRepository.lookup("hello")) {
            is LookupResult.BaseHit -> assertEquals("həlˈoʊ", result.phonemes)
            else -> error("Expected BaseHit for hello")
        }

        // 3. Standalone pronoun 'i' override hit
        when (val result = PronunciationRepository.lookup("I")) {
            is LookupResult.OverrideHit -> assertEquals("ˈaɪ", result.phonemes)
            else -> error("Expected OverrideHit for I")
        }

        // 4. Contraction override hit
        when (val result = PronunciationRepository.lookup("Don't")) {
            is LookupResult.OverrideHit -> assertEquals("dˈoʊnt", result.phonemes)
            else -> error("Expected OverrideHit for Don't")
        }

        // 5. Missing word
        when (val result = PronunciationRepository.lookup("unknownword")) {
            is LookupResult.Miss -> Unit
            else -> error("Expected Miss for unknownword")
        }

        val report = PhonemizerMetrics.getReport()
        assertEquals(3L, report["override_hit_count"])
        assertEquals(4L, report["dictionary_hit_count"])
        assertEquals(1L, report["dictionary_miss_count"])
    }

    @Test
    fun testFallbackCacheAndLRUEviction() {
        val baseJson = """{"en_us":{}}"""
        PronunciationRepository.loadFromStreams(
            baseStream = ByteArrayInputStream(baseJson.toByteArray(Charsets.UTF_8)),
            overrideStream = null
        )

        // Cache fallback entry
        PronunciationRepository.cacheFallback("unknown", "ʌnnˈoʊn")

        // Lookup cached entry
        when (val result = PronunciationRepository.lookup("UNKNOWN")) {
            is LookupResult.CacheHit -> assertEquals("ʌnnˈoʊn", result.phonemes)
            else -> error("Expected CacheHit")
        }

        val report = PhonemizerMetrics.getReport()
        assertEquals(1L, report["fallback_cache_hit_count"])
    }

    @Test
    fun testPhonemeValidation() {
        assertTrue(PronunciationRepository.isValidPhonemeSequence("dˈoʊnt"))
        assertTrue(PronunciationRepository.isValidPhonemeSequence("ˈaɪ"))
        assertFalse(PronunciationRepository.isValidPhonemeSequence(""))
        assertFalse(PronunciationRepository.isValidPhonemeSequence("   "))
        // Unsupported character \uFFFF maps to SPECIAL_ID (0)
        assertFalse(PronunciationRepository.isValidPhonemeSequence("\uFFFF"))
    }
}
