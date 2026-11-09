package com.danemadsen.maise.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MaiseTtsServiceTest {

    private val service = MaiseTtsService()

    @Test
    fun testIso2LanguageAvailability() {
        val result = service.availabilityOf("en", "US")
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, result)
    }

    @Test
    fun testIso3LanguageAvailability() {
        val result = service.availabilityOf("eng", "USA")
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, result)
    }

    @Test
    fun testLanguageTagAvailability() {
        val result = service.availabilityOf("en-US", "USA")
        assertTrue("Language tag should report available", result >= TextToSpeech.LANG_AVAILABLE)
    }

    @Test
    fun testUnsupportedLanguageAvailability() {
        val result = service.availabilityOf("xyz", "ABC")
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, result)
    }

    @Test
    fun testIso3VoiceMatching() {
        val voiceId = service.voiceMatching("eng", "USA")
        assertNotNull("Should resolve a valid voice for ISO3 eng/USA", voiceId)
        assertTrue("Voice ID should contain kokoro", voiceId!!.contains("kokoro"))
    }

    @Test
    fun testMatchesLanguageAndCountry() {
        assertTrue("Locale.US matches eng", service.matchesLanguage(Locale.US, "eng"))
        assertTrue("Locale.US matches en", service.matchesLanguage(Locale.US, "en"))
        assertTrue("Locale.US matches USA", service.matchesCountry(Locale.US, "USA"))
        assertTrue("Locale.US matches US", service.matchesCountry(Locale.US, "US"))
    }

    @Test
    fun testSampleTextsWithIso3() {
        val sample = SampleTexts.forLocale("eng", "USA")
        assertNotNull("Sample text should not be null for eng-USA", sample)
        assertTrue("Sample text should contain English text", sample!!.contains("English"))
    }
}
